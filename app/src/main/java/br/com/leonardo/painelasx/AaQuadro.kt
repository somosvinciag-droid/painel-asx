/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Regras do enquadramento (cabeçalho, flags, fragmentos, tamanho total) adaptadas do Headunit Revived /
 * Open Headunit (https://github.com/andreknieriem/headunit-revived: AapMessageFraming.kt,
 * AapReadMultipleMessages.kt, AapMessageReassembler.kt), Copyright (C) 2011-2015 Michael A. Reid
 * e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

/*
 * Enquadramento do Android Auto (SEM Android: testado no computador).
 *
 * Cada quadro: [canal 1 byte][flags 1 byte][tamanho do corpo 2 bytes, big-endian]
 *              (+ [tamanho total 4 bytes] só no 1º pedaço de uma mensagem partida) + corpo.
 * Flags: 0x01 primeiro pedaço, 0x02 último, 0x04 "mensagem de controle" (vale em qualquer canal),
 *        0x08 cifrado (TLS). 0x0b = mensagem inteira cifrada; 0x09/0x08/0x0a = começo/meio/fim.
 * O corpo cifrado, depois do TLS, começa com o tipo (2 bytes) só no 1º pedaço.
 */
object AaQuadro {
    const val PRIMEIRO = 0x01
    const val ULTIMO = 0x02
    const val CONTROLE = 0x04
    const val CIFRADO = 0x08
    /** Maior corpo de um quadro (o tamanho tem 2 bytes). */
    const val MAX_CORPO = 65535
    /** Maior mensagem remontada que guardamos (capa da música pode ter ~1 MB). */
    const val MAX_MENSAGEM = 4 * 1024 * 1024
    /** Maior buffer de remontagem que fica guardado por canal para a próxima mensagem. */
    const val MAX_REUSO = 512 * 1024

    fun temTotal(flags: Int) = flags and PRIMEIRO != 0 && flags and ULTIMO == 0

    /** Flags para uma mensagem nossa (inteira, cifrada). Tipos de controle (1..26) fora do canal 0 levam 0x04. */
    fun flagsPara(canal: Int, tipo: Int): Int = if (canal != 0 && tipo in 1..26) 0x0f else 0x0b

    /** Quadro aberto (antes do TLS): canal 0, flags 3, tipo + dados. */
    fun aberto(tipo: Int, dados: ByteArray): ByteArray {
        val n = dados.size + 2
        val q = ByteArray(4 + n)
        q[1] = 3; q[2] = (n ushr 8).toByte(); q[3] = n.toByte()
        q[4] = (tipo ushr 8).toByte(); q[5] = tipo.toByte()
        System.arraycopy(dados, 0, q, 6, dados.size)
        return q
    }

    /** Quadro com o corpo já cifrado. */
    fun cifrado(canal: Int, flags: Int, corpo: ByteArray, n: Int = corpo.size): ByteArray {
        require(n <= MAX_CORPO) { "corpo grande demais ($n)" }
        val q = ByteArray(4 + n)
        q[0] = canal.toByte(); q[1] = flags.toByte(); q[2] = (n ushr 8).toByte(); q[3] = n.toByte()
        System.arraycopy(corpo, 0, q, 4, n)
        return q
    }

    /** Texto da mensagem: [tipo 2 bytes][dados]. */
    fun comTipo(tipo: Int, dados: ByteArray): ByteArray {
        val b = ByteArray(2 + dados.size)
        b[0] = (tipo ushr 8).toByte(); b[1] = tipo.toByte()
        System.arraycopy(dados, 0, b, 2, dados.size)
        return b
    }

    /**
     * Separa os quadros de um fluxo de bytes (USB não respeita os limites das mensagens).
     * Buffer reaproveitado. [cada] recebe (canal, flags, total declarado, buffer, início do corpo, tamanho do corpo)
     * e deve usar o corpo ANTES de voltar (o buffer é reaproveitado).
     */
    class Separador(capacidade: Int = 4 + 4 + MAX_CORPO + 16384) {
        private var buf = ByteArray(capacidade)
        private var tam = 0

        val pendentes get() = tam

        fun alimentar(dados: ByteArray, ini: Int, n: Int, cada: (Int, Int, Int, ByteArray, Int, Int) -> Unit) {
            if (n <= 0) return
            if (tam + n > buf.size) buf = buf.copyOf(maxOf(tam + n, buf.size + buf.size / 2))
            System.arraycopy(dados, ini, buf, tam, n)
            tam += n
            var pos = 0
            while (tam - pos >= 4) {
                val canal = buf[pos].toInt() and 0xFF
                val flags = buf[pos + 1].toInt() and 0xFF
                val corpo = ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
                val extra = if (temTotal(flags)) 4 else 0
                if (tam - pos < 4 + extra + corpo) break
                var total = 0
                if (extra == 4) {
                    total = ((buf[pos + 4].toInt() and 0xFF) shl 24) or ((buf[pos + 5].toInt() and 0xFF) shl 16) or
                        ((buf[pos + 6].toInt() and 0xFF) shl 8) or (buf[pos + 7].toInt() and 0xFF)
                }
                val ini0 = pos + 4 + extra
                pos = ini0 + corpo
                cada(canal, flags, total, buf, ini0, corpo)
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, tam - pos)
                tam -= pos
            }
            // Não fica com um buffer gigante por causa de uma mensagem grande (capa de música).
            if (buf.size > 2 * (4 + 4 + MAX_CORPO + 16384) && tam < 16384) buf = buf.copyOf(4 + 4 + MAX_CORPO + 16384)
        }

        fun limpar() { tam = 0 }
    }

    /** Uma mensagem inteira (tipo + dados). [dados] de [ini] até [fim] (sem o tipo). Use antes de voltar. */
    class Mensagem(var canal: Int = 0, var flags: Int = 0, var tipo: Int = 0, var dados: ByteArray = ByteArray(0),
                   var ini: Int = 0, var fim: Int = 0, var descartada: Boolean = false) {
        val tamanho get() = fim - ini
        val controle get() = flags and CONTROLE != 0
    }

    /**
     * Junta os pedaços (já decifrados) de cada canal.
     * - Vídeo (dados/configuração do codec): NÃO guarda nada, só avisa que chegou (para confirmar e descartar).
     * - Mensagem maior que [MAX_MENSAGEM] ou que não fecha com o total declarado: descartada (se era DADOS de
     *   áudio, ainda é avisada como descartada para a confirmação não faltar ao celular).
     */
    class Remontador(private val ehVideo: (Int) -> Boolean = { it == Aa.CANAL_VIDEO }) {
        private class Run(val flags: Int, val total: Int) {
            var bytes: ByteArray? = null
            var usados = 0
            var tipo = 0
            var bytesTipo = 0
            var semGuardar = false
            var ruim = false
        }

        private val runs = arrayOfNulls<Run>(256)
        /**
         * Buffer reaproveitado de cada canal (a mensagem devolvida só vale até a próxima: quem usa já copia). Com a tela
         * do Android Auto aberta, cada quadro de vídeo grande (> 16 KB, partido em pedaços) criava um ByteArray novo.
         */
        private val reuso = arrayOfNulls<ByteArray>(256)
        private val saida = Mensagem()
        var descartes = 0L
            private set

        /** null = ainda falta pedaço (ou foi um pedaço vazio de controle do TLS). */
        fun aceitar(canal: Int, flags: Int, total: Int, b: ByteArray, ini: Int, n: Int): Mensagem? {
            if (canal !in 0..255) return null
            val primeiro = flags and PRIMEIRO != 0
            val ultimo = flags and ULTIMO != 0
            if (primeiro && ultimo) {
                runs[canal] = null
                if (n < 2) return null   // pedaço vazio (só controle do TLS) ou sem tipo
                val tipo = ((b[ini].toInt() and 0xFF) shl 8) or (b[ini + 1].toInt() and 0xFF)
                return preencher(canal, flags, tipo, b, ini + 2, ini + n, ehVideo(canal) && tipo <= 1)
            }
            if (primeiro) {
                if (n == 0) return null
                runs[canal] = Run(flags, total).also {
                    if (total < 2 || total > (if (ehVideo(canal)) Int.MAX_VALUE else MAX_MENSAGEM)) it.ruim = true
                }
            }
            val r = runs[canal] ?: return null   // pedaço do meio sem começo: ignora
            if ((flags and 0x0c) != (r.flags and 0x0c)) { runs[canal] = null; return null }
            // O tipo (2 bytes) pode vir partido entre dois pedaços.
            var k = 0
            while (r.bytesTipo < 2 && k < n) { r.tipo = (r.tipo shl 8) or (b[ini + k].toInt() and 0xFF); r.bytesTipo++; k++ }
            if (r.bytesTipo == 2 && r.usados == 0 && r.bytes == null && !r.semGuardar) {
                r.semGuardar = ehVideo(canal) && r.tipo <= 1 && flags and CONTROLE == 0
                if (!r.semGuardar && !r.ruim) {
                    val inicial = minOf(r.total, 64 * 1024).coerceAtLeast(2)
                    r.bytes = reuso[canal]?.takeIf { it.size >= inicial } ?: ByteArray(inicial)
                }
            }
            val corpo = n - k
            if (!r.semGuardar && !r.ruim && r.bytesTipo == 2) {
                val precisa = r.usados + corpo
                if (precisa > r.total - 2) r.ruim = true
                else {
                    var dst = r.bytes!!
                    if (precisa > dst.size) { dst = dst.copyOf(minOf(r.total - 2, maxOf(precisa, dst.size * 2))); r.bytes = dst }
                    System.arraycopy(b, ini + k, dst, r.usados, corpo)
                }
            }
            r.usados += corpo
            if (!ultimo) return null
            runs[canal] = null
            if (r.bytesTipo < 2) return null
            if (r.semGuardar) return preencher(canal, r.flags or ULTIMO, r.tipo, ByteArray(0), 0, 0, true)
            if (r.ruim || r.usados != r.total - 2) {
                descartes++
                return preencher(canal, r.flags or ULTIMO, r.tipo, ByteArray(0), 0, 0, true)
            }
            val pronto = r.bytes!!
            // Guarda para a próxima mensagem do canal (não guarda buffer enorme, ex.: capa de música de vários MB).
            if (pronto.size <= MAX_REUSO) reuso[canal] = pronto
            return preencher(canal, r.flags or ULTIMO, r.tipo, pronto, 0, r.usados, false)
        }

        private fun preencher(canal: Int, flags: Int, tipo: Int, b: ByteArray, ini: Int, fim: Int, descartada: Boolean): Mensagem {
            saida.canal = canal; saida.flags = flags; saida.tipo = tipo
            saida.dados = b; saida.ini = ini; saida.fim = fim; saida.descartada = descartada
            return saida
        }

        fun limpar() { for (i in runs.indices) runs[i] = null; for (i in reuso.indices) reuso[i] = null }
    }
}
