/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Formato das mensagens tirado dos .proto do Headunit Revived / Open Headunit
 * (https://github.com/andreknieriem/headunit-revived), Copyright (C) 2011-2015 Michael A. Reid
 * e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

/*
 * Protobuf mínimo, escrito à mão (sem a biblioteca protobuf: menos memória e nada a mais no APK).
 * Só o que o Android Auto usa: varint, texto/bytes e mensagens dentro de mensagens.
 * SEM Android aqui: este arquivo é testado no computador (JVM).
 */

/** Erro de formato numa mensagem recebida (a sessão ignora a mensagem, não derruba o app). */
class ProtoErro(msg: String) : RuntimeException(msg)

/** Monta uma mensagem protobuf. */
class ProtoEscritor(capacidade: Int = 64) {
    private var b = ByteArray(capacidade)
    var tamanho = 0
        private set

    private fun caber(n: Int) {
        if (tamanho + n <= b.size) return
        b = b.copyOf(maxOf(tamanho + n, b.size * 2))
    }

    private fun byte(v: Int) { caber(1); b[tamanho++] = v.toByte() }

    /** Varint cru (int32 negativo vira 10 bytes, como manda o protobuf). */
    fun cru(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { byte(x.toInt()); return }
            byte(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun etiqueta(campo: Int, tipo: Int) = cru(((campo shl 3) or tipo).toLong())

    fun varint(campo: Int, v: Long): ProtoEscritor { etiqueta(campo, 0); cru(v); return this }
    fun int(campo: Int, v: Int): ProtoEscritor = varint(campo, v.toLong())
    fun bool(campo: Int, v: Boolean): ProtoEscritor = varint(campo, if (v) 1 else 0)

    fun bytes(campo: Int, d: ByteArray, ini: Int = 0, n: Int = d.size): ProtoEscritor {
        etiqueta(campo, 2); cru(n.toLong())
        caber(n); System.arraycopy(d, ini, b, tamanho, n); tamanho += n
        return this
    }

    fun texto(campo: Int, s: String): ProtoEscritor = bytes(campo, s.toByteArray(Charsets.UTF_8))

    fun msg(campo: Int, m: ProtoEscritor): ProtoEscritor = bytes(campo, m.b, 0, m.tamanho)

    /** Mensagem dentro desta: `msg(3) { int(1, 2) }`. */
    inline fun msg(campo: Int, bloco: ProtoEscritor.() -> Unit): ProtoEscritor = msg(campo, ProtoEscritor().apply(bloco))

    fun paraBytes(): ByteArray = b.copyOf(tamanho)
}

/**
 * Lê uma mensagem protobuf campo a campo, sem copiar nada:
 * `while (l.proximo()) when (l.campo) { 1 -> x = l.int() ... }`.
 */
class ProtoLeitor(private val b: ByteArray, private var pos: Int, private val fim: Int) {
    constructor(b: ByteArray) : this(b, 0, b.size)

    init {
        if (pos < 0 || fim > b.size || pos > fim) throw ProtoErro("limites inválidos")
    }

    var campo = 0
        private set
    var tipo = 0
        private set
    /** Valor do campo varint/fixo. */
    var valor = 0L
        private set
    /** Começo e tamanho do campo de tamanho variável (texto, bytes, mensagem). */
    var ini = 0
        private set
    var tam = 0
        private set

    private fun lerCru(): Long {
        var r = 0L
        var desloc = 0
        while (true) {
            if (pos >= fim) throw ProtoErro("varint cortado")
            val x = b[pos++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl desloc)
            if (x and 0x80 == 0) return r
            desloc += 7
            if (desloc >= 70) throw ProtoErro("varint longo demais")
        }
    }

    /** Vai para o próximo campo. false = acabou. */
    fun proximo(): Boolean {
        if (pos >= fim) return false
        val e = lerCru()
        campo = (e ushr 3).toInt()
        tipo = (e and 7).toInt()
        if (campo <= 0) throw ProtoErro("campo $campo")
        when (tipo) {
            0 -> valor = lerCru()
            1 -> {
                if (fim - pos < 8) throw ProtoErro("fixo64 cortado")
                var v = 0L
                for (i in 7 downTo 0) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
                valor = v; pos += 8
            }
            2 -> {
                val n = lerCru()
                if (n < 0 || n > fim - pos) throw ProtoErro("tamanho $n maior que a mensagem")
                ini = pos; tam = n.toInt(); pos += tam
            }
            5 -> {
                if (fim - pos < 4) throw ProtoErro("fixo32 cortado")
                var v = 0L
                for (i in 3 downTo 0) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
                valor = v; pos += 4
            }
            else -> throw ProtoErro("tipo de campo $tipo")
        }
        return true
    }

    fun int(): Int = valor.toInt()
    fun bool(): Boolean = valor != 0L
    fun texto(): String = if (tipo == 2) String(b, ini, tam, Charsets.UTF_8) else ""
    fun bytes(): ByteArray = if (tipo == 2) b.copyOfRange(ini, ini + tam) else ByteArray(0)
    fun sub(): ProtoLeitor = if (tipo == 2) ProtoLeitor(b, ini, ini + tam) else ProtoLeitor(b, 0, 0)
}
