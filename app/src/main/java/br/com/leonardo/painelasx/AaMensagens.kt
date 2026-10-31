/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Números de canais, tipos de mensagem, campos e o anúncio de serviços adaptados do Headunit Revived /
 * Open Headunit (https://github.com/andreknieriem/headunit-revived: ServiceDiscoveryResponse.kt,
 * AapControl.kt, VehicleTypePolicy.kt, AudioConfigs.kt e os .proto), Copyright (C) 2011-2015
 * Michael A. Reid e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

/*
 * As mensagens do Android Auto que o Painel usa: montar (central -> celular) e ler (celular -> central).
 * SEM Android aqui: testado no computador (JVM).
 */
object Aa {
    // ---- canais (somos nós que escolhemos os números, no anúncio de serviços; os mesmos do Headunit Revived)
    const val CANAL_CONTROLE = 0
    const val CANAL_SENSORES = 1
    const val CANAL_VIDEO = 2
    const val CANAL_ENTRADA = 3
    const val CANAL_VOZ = 4        // fala do GPS e do assistente (16 kHz mono)
    const val CANAL_SISTEMA = 5    // sons do sistema (16 kHz mono)
    const val CANAL_MIDIA = 6      // música (48 kHz estéreo)
    const val CANAL_MUSICA_INFO = 9
    const val CANAL_NAVEGACAO = 10

    fun nomeCanal(c: Int) = when (c) {
        CANAL_CONTROLE -> "controle"; CANAL_SENSORES -> "sensores"; CANAL_VIDEO -> "vídeo"
        CANAL_ENTRADA -> "entrada"; CANAL_VOZ -> "voz"; CANAL_SISTEMA -> "sistema"; CANAL_MIDIA -> "mídia"
        CANAL_MUSICA_INFO -> "música"; CANAL_NAVEGACAO -> "navegação"; else -> "canal $c"
    }

    fun ehAudio(c: Int) = c == CANAL_VOZ || c == CANAL_SISTEMA || c == CANAL_MIDIA

    // ---- tipos de mensagem do controle
    const val VERSAO_PEDIDO = 1
    const val VERSAO_RESPOSTA = 2
    const val TLS = 3
    const val AUTENTICOU = 4
    const val SERVICOS_PEDIDO = 5
    const val SERVICOS_RESPOSTA = 6
    const val CANAL_ABRIR = 7
    const val CANAL_ABERTO = 8
    const val CANAL_FECHOU = 9
    const val PING_PEDIDO = 11
    const val PING_RESPOSTA = 12
    const val NAV_FOCO_PEDIDO = 13
    const val NAV_FOCO = 14
    const val TCHAU_PEDIDO = 15
    const val TCHAU_RESPOSTA = 16
    const val VOZ_SESSAO = 17
    const val FOCO_AUDIO_PEDIDO = 18
    const val FOCO_AUDIO = 19

    // ---- tipos de mensagem dos canais de mídia (áudio e vídeo)
    const val MIDIA_DADOS = 0
    const val MIDIA_CONFIG_CODEC = 1
    const val MIDIA_PREPARAR = 0x8000
    const val MIDIA_INICIO = 0x8001
    const val MIDIA_FIM = 0x8002
    const val MIDIA_CONFIG = 0x8003
    const val MIDIA_ACK = 0x8004
    const val MIC_PEDIDO = 0x8005
    const val MIC_RESPOSTA = 0x8006
    const val VIDEO_FOCO_PEDIDO = 0x8007
    const val VIDEO_FOCO = 0x8008

    // ---- sensores
    const val SENSOR_PEDIDO = 0x8001
    const val SENSOR_RESPOSTA = 0x8002
    const val SENSOR_EVENTO = 0x8003
    const val SENSOR_NOITE = 10
    const val SENSOR_DIRIGINDO = 13

    // ---- entrada (teclas)
    const val ENTRADA_EVENTO = 0x8001
    const val ENTRADA_PEDIDO = 0x8002
    const val ENTRADA_RESPOSTA = 0x8003

    // ---- informação da música (no código do Headunit Revived: 0x8001 = estado, 0x8003 = dados da música)
    const val MUSICA_ESTADO = 0x8001
    const val MUSICA_DADOS = 0x8003

    // ---- navegação (próxima curva)
    const val NAV_INICIO = 0x8001
    const val NAV_PAROU = 0x8002
    const val NAV_STATUS = 0x8003
    const val NAV_CURVA = 0x8004
    const val NAV_DISTANCIA = 0x8005
    const val NAV_ESTADO = 0x8006
    const val NAV_POSICAO = 0x8007

    // ---- foco de vídeo
    const val VIDEO_PROJETADO = 1
    const val VIDEO_NATIVO = 2

    // ---- foco de áudio
    const val FOCO_GANHO = 1
    const val FOCO_GANHO_PASSAGEIRO = 2
    const val FOCO_PERDA = 3
    const val FOCO_SO_GUIA = 7

    /** Teclas que anunciamos (códigos do Android). Sem a tecla de voz: o FALAR é do Jarvis. */
    const val TECLA_PLAY_PAUSE = 85
    const val TECLA_PROXIMA = 87
    const val TECLA_ANTERIOR = 88
    const val TECLA_PLAY = 126
    const val TECLA_PAUSA = 127
    val TECLAS = intArrayOf(TECLA_PLAY_PAUSE, TECLA_PROXIMA, TECLA_ANTERIOR, TECLA_PLAY, TECLA_PAUSA)

    /** Tipo de veículo "moto" (3): o celular usa o PRÓPRIO microfone (a central não anuncia microfone). */
    const val VEICULO_MOTO = 3

    // ------------------------------------------------------------------ fora do TLS (texto aberto)

    /** Pedido de versão 1.2 (o celular escolhe a dele): canal 0, flags 3, tamanho 6, tipo 1, 1, 2. */
    fun versaoPedido(): ByteArray = byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 2)

    /** "Autenticação terminada, tudo certo" (depois do TLS, ainda aberto): status 0. */
    fun autenticou(): ByteArray = byteArrayOf(0, 3, 0, 4, 0, 4, 8, 0)

    class Versao(val maior: Int, val menor: Int, val status: Int) {
        /** Só -1 recusa. Outros status também funcionam (visto no Headunit Revived). */
        val aceita get() = status != -1
    }

    /** Lê a resposta de versão (quadro inteiro, com o cabeçalho de 4 bytes). null = não é. */
    fun lerVersao(q: ByteArray, n: Int = q.size): Versao? {
        if (n < 12) return null
        fun u16(i: Int) = ((q[i].toInt() and 0xFF) shl 8) or (q[i + 1].toInt() and 0xFF)
        if (q[0].toInt() != 0 || u16(4) != VERSAO_RESPOSTA) return null
        val st = u16(10)
        return Versao(u16(6), u16(8), if (st >= 0x8000) st - 0x10000 else st)
    }

    // ------------------------------------------------------------------ anúncio de serviços

    class Carro(
        val marca: String = "Mitsubishi",
        val modelo: String = "ASX",
        val ano: String = "2012",
        /** Id próprio do tipo "moto": o celular guarda o tipo por id, e um id novo não herda "carro". */
        val idVeiculo: String = "painel-asx-2012-moto",
        val marcaCentral: String = "Painel ASX",
        val modeloCentral: String = "K4811",
        val versao: String = "1",
        val nome: String = "Painel ASX"
    )

    private fun audio(id: Int, tipoAudio: Int, hz: Int, canais: Int) = ProtoEscritor()
        .int(1, id)
        .msg(3) {
            int(1, 1)            // PCM
            int(2, tipoAudio)    // 1 voz, 2 sistema, 3 mídia
            msg(3) { int(1, hz); int(2, 16); int(3, canais) }
        }

    /**
     * O que a central diz que tem (resposta ao pedido de serviços).
     * - Vídeo 800x480 H.264 é obrigatório (sem vídeo o celular desiste), mas nunca damos o foco de vídeo a ele.
     * - SEM microfone e tipo "moto": o celular grava com o próprio microfone ("Ok Google" do celular).
     * - SEM Bluetooth: o celular não recebe endereço para parear (as ligações ficam no celular).
     */
    fun servicos(c: Carro): ByteArray {
        val m = ProtoEscritor(512)
        // sensores: dirigindo + noite
        m.msg(1, ProtoEscritor().int(1, CANAL_SENSORES).msg(2) {
            msg(1) { int(1, SENSOR_DIRIGINDO) }
            msg(1) { int(1, SENSOR_NOITE) }
        })
        // vídeo (obrigatório; não é mostrado)
        m.msg(1, ProtoEscritor().int(1, CANAL_VIDEO).msg(3) {
            int(1, 3)       // H.264
            int(2, 0)       // sem áudio
            msg(4) {
                int(1, 1)   // 800x480
                int(2, 2)   // 30 quadros
                int(3, 0); int(4, 0)
                int(5, 160) // densidade
                int(10, 3)  // H.264
            }
            bool(5, true)
        })
        // entrada: só as teclas de música (+ tela de toque do tamanho do vídeo, que o celular exige)
        m.msg(1, ProtoEscritor().int(1, CANAL_ENTRADA).msg(4) {
            for (t in TECLAS) int(1, t)
            msg(2) { int(1, 800); int(2, 480) }
        })
        m.msg(1, audio(CANAL_SISTEMA, 2, 16000, 1))
        m.msg(1, audio(CANAL_VOZ, 1, 16000, 1))
        m.msg(1, audio(CANAL_MIDIA, 3, 48000, 2))
        // informação da música (vazio)
        m.msg(1, ProtoEscritor().int(1, CANAL_MUSICA_INFO).msg(9) { })
        // navegação: só códigos (sem imagens), no máximo 1 por segundo
        m.msg(1, ProtoEscritor().int(1, CANAL_NAVEGACAO).msg(8) { int(1, 1000); int(2, 2) })

        m.texto(2, c.marca).texto(3, c.modelo).texto(4, c.ano).texto(5, c.idVeiculo)
        m.int(6, 0)   // motorista à esquerda
        m.texto(7, c.marcaCentral).texto(8, c.modeloCentral).texto(9, "1").texto(10, c.versao)
        m.bool(11, false)
        m.bool(12, false)
        m.int(13, 0)
        m.texto(14, c.nome)
        m.msg(17) {
            texto(1, c.marca); texto(2, c.modelo); texto(3, c.ano); texto(4, c.idVeiculo)
            texto(5, c.marcaCentral); texto(6, c.modeloCentral); texto(7, "1"); texto(8, c.versao)
            int(9, VEICULO_MOTO)
        }
        return m.paraBytes()
    }

    // ------------------------------------------------------------------ respostas pequenas

    fun status(s: Int = 0) = ProtoEscritor().int(1, s).paraBytes()
    fun pingResposta(ts: Long) = ProtoEscritor().varint(1, ts).paraBytes()
    fun focoAudio(estado: Int, semPedido: Boolean) = ProtoEscritor().int(1, estado).apply { if (semPedido) bool(2, true) }.paraBytes()
    fun navFoco() = ProtoEscritor().int(1, 2).paraBytes()
    fun tchauPedido() = ProtoEscritor().int(1, 1).paraBytes()   // USER_SELECTION
    fun tchauResposta() = ByteArray(0)
    /** Resposta ao "preparar" de um canal de mídia: pronto, até [maxSemAck] pacotes sem confirmação, configuração 0. */
    fun midiaConfig(maxSemAck: Int) = ProtoEscritor().int(1, 2).int(2, maxSemAck).int(3, 0).paraBytes()
    fun midiaAck(sessao: Int) = ProtoEscritor().int(1, sessao).int(2, 1).paraBytes()
    fun videoFoco(modo: Int, semPedido: Boolean) = ProtoEscritor().int(1, modo).bool(2, semPedido).paraBytes()
    fun micResposta(status: Int, sessao: Int) = ProtoEscritor().int(1, status).int(2, sessao).paraBytes()
    fun sensorDirigindo(status: Int = 0) = ProtoEscritor().msg(13) { int(1, status) }.paraBytes()
    fun sensorNoite(noite: Boolean) = ProtoEscritor().msg(10) { bool(1, noite) }.paraBytes()

    /** Tecla apertada/solta no canal de entrada. [tsNs] em nanossegundos. */
    fun tecla(tsNs: Long, codigo: Int, apertada: Boolean) = ProtoEscritor()
        .varint(1, tsNs)
        .msg(4) { msg(1) { int(1, codigo); bool(2, apertada); int(3, 0); bool(4, false) } }
        .paraBytes()

    // ------------------------------------------------------------------ leitura

    /** Lê um campo inteiro simples (o primeiro com esse número), ou [padrao]. */
    fun campoInt(b: ByteArray, ini: Int, fim: Int, campo: Int, padrao: Long = -1): Long {
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) if (l.campo == campo && l.tipo == 0) return l.valor
        return padrao
    }

    fun campoTexto(b: ByteArray, ini: Int, fim: Int, campo: Int): String {
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) if (l.campo == campo && l.tipo == 2) return l.texto()
        return ""
    }

    // ---- música
    class MusicaDados(val titulo: String, val artista: String, val album: String, val duracaoS: Int, val capa: ByteArray?)

    fun lerMusicaDados(b: ByteArray, ini: Int, fim: Int, querCapa: Boolean = true): MusicaDados {
        var t = ""; var a = ""; var al = ""; var d = 0; var capa: ByteArray? = null
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) when (l.campo) {
            1 -> t = l.texto()
            2 -> a = l.texto()
            3 -> al = l.texto()
            4 -> if (querCapa && l.tam in 1..(2 * 1024 * 1024)) capa = l.bytes()
            6 -> d = l.int()
        }
        return MusicaDados(t, a, al, d, capa)
    }

    /** Estado: 1 parado, 2 tocando, 3 pausado (null = não veio). Campos opcionais: o que não veio fica como estava. */
    class MusicaEstado(val estado: Int?, val app: String?, val segundos: Long?)

    fun lerMusicaEstado(b: ByteArray, ini: Int, fim: Int): MusicaEstado {
        var e: Int? = null; var app: String? = null; var s: Long? = null
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) when (l.campo) {
            1 -> e = l.int()
            2 -> app = l.texto()
            3 -> s = l.valor and 0xFFFFFFFFL
        }
        return MusicaEstado(e, app, s)
    }

    // ---- navegação
    /** A próxima manobra em palavras simples (pt-BR), já pronta para a tela. */
    class Curva(val manobra: String, val rua: String)

    /** Mensagem antiga (0x8004): rua, lado (1 esq, 2 dir), tipo de evento. */
    fun lerCurvaAntiga(b: ByteArray, ini: Int, fim: Int): Curva {
        var rua = ""; var lado = 3; var ev = 0; var saida = 0
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) when (l.campo) {
            1 -> rua = l.texto()
            2 -> lado = l.int()
            3 -> ev = l.int()
            5 -> saida = l.int()
        }
        val esq = lado == 1
        val m = when (ev) {
            1 -> "Siga"
            2, 14 -> "Siga em frente"
            3 -> if (esq) "Mantenha à esquerda" else "Mantenha à direita"
            4 -> if (esq) "Vire à esquerda" else "Vire à direita"
            5 -> if (esq) "Curva fechada à esquerda" else "Curva fechada à direita"
            6 -> "Faça o retorno"
            7 -> "Pegue a entrada"
            8 -> "Pegue a saída"
            9 -> if (esq) "Fique à esquerda" else "Fique à direita"
            10 -> "Entre na via"
            11, 13 -> if (saida > 0) "Na rotatória, ${saida}ª saída" else "Entre na rotatória"
            12 -> "Saia da rotatória"
            16, 17 -> "Balsa"
            19 -> "Chegando ao destino"
            else -> ""
        }
        return Curva(m, rua)
    }

    /** Mensagem nova (0x8006): a 1ª etapa (manobra + rua). null se não tiver etapas. */
    fun lerNavEstado(b: ByteArray, ini: Int, fim: Int): Curva? {
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) {
            if (l.campo != 1 || l.tipo != 2) continue
            var tipo = 0; var saida = 0; var rua = ""; var dica = ""
            val e = l.sub()
            while (e.proximo()) when (e.campo) {
                1 -> { val m = e.sub(); while (m.proximo()) when (m.campo) { 1 -> tipo = m.int(); 2 -> saida = m.int() } }
                2 -> { val r = e.sub(); while (r.proximo()) if (r.campo == 1) rua = r.texto() }
                4 -> { val c = e.sub(); while (c.proximo()) if (c.campo == 1 && dica.isEmpty()) dica = c.texto() }
            }
            return Curva(manobraNova(tipo, saida), rua.ifBlank { dica })
        }
        return null
    }

    fun manobraNova(t: Int, saida: Int): String = when (t) {
        1 -> "Siga"
        2, 36 -> "Siga em frente"
        3 -> "Mantenha à esquerda"
        4 -> "Mantenha à direita"
        5 -> "Vire levemente à esquerda"
        6 -> "Vire levemente à direita"
        7 -> "Vire à esquerda"
        8 -> "Vire à direita"
        9 -> "Curva fechada à esquerda"
        10 -> "Curva fechada à direita"
        11, 12 -> "Faça o retorno"
        in 13..20 -> "Pegue a entrada"
        in 21..24 -> "Pegue a saída"
        25 -> "Fique à esquerda"
        26 -> "Fique à direita"
        in 27..29 -> "Entre na via"
        30 -> "Entre na rotatória"
        31 -> "Saia da rotatória"
        in 32..35 -> if (saida > 0) "Na rotatória, ${saida}ª saída" else "Na rotatória"
        37, 38 -> "Balsa"
        in 39..42 -> "Chegando ao destino"
        else -> ""
    }

    /** Distância até a manobra: metros e o texto que o celular sugere ("300", "1,2"). */
    class Distancia(val metros: Int, val texto: String, val unidade: Int)

    /** Mensagem antiga (0x8005). */
    fun lerDistanciaAntiga(b: ByteArray, ini: Int, fim: Int): Distancia {
        var m = -1; var e3 = -1; var u = 0
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) when (l.campo) { 1 -> m = l.int(); 3 -> e3 = l.int(); 4 -> u = l.int() }
        val txt = if (e3 >= 0 && u != 0) {
            val v = e3 / 1000.0
            if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',')
        } else ""
        return Distancia(m, txt, u)
    }

    /** Mensagem nova (0x8007): distância da etapa. */
    fun lerNavPosicao(b: ByteArray, ini: Int, fim: Int): Distancia? {
        val l = ProtoLeitor(b, ini, fim)
        while (l.proximo()) {
            if (l.campo != 1 || l.tipo != 2) continue
            val s = l.sub()
            while (s.proximo()) {
                if (s.campo != 1 || s.tipo != 2) continue
                var m = -1; var txt = ""; var u = 0
                val d = s.sub()
                while (d.proximo()) when (d.campo) { 1 -> m = d.int(); 2 -> txt = d.texto(); 3 -> u = d.int() }
                return Distancia(m, txt, u)
            }
        }
        return null
    }

    /** "300 m", "1,2 km" (pelo texto do celular quando há; senão pelos metros). */
    fun distanciaTexto(d: Distancia?): String {
        if (d == null) return ""
        val unid = when (d.unidade) { 1 -> "m"; 2, 3 -> "km"; 4, 5 -> "mi"; 6 -> "pés"; 7 -> "jd"; else -> "" }
        if (d.texto.isNotBlank() && unid.isNotEmpty()) return d.texto.replace('.', ',') + " " + unid
        if (d.metros < 0) return ""
        if (d.metros < 1000) return "${(d.metros / 10) * 10} m"
        return String.format(java.util.Locale.ROOT, "%.1f km", d.metros / 1000.0).replace('.', ',')
    }

    /** Frase discreta para a tela do carro: "Em 300 m, vire à direita · Av. Brasil". */
    fun fraseNavegacao(c: Curva?, d: Distancia?): String {
        if (c == null) return ""
        val dist = distanciaTexto(d)
        val manobra = c.manobra
        val base = when {
            manobra.isEmpty() -> if (dist.isEmpty()) "" else "Em $dist"
            dist.isEmpty() -> manobra
            else -> "Em $dist, " + manobra.replaceFirstChar { it.lowercase() }
        }
        val rua = c.rua.trim().take(60)
        return when {
            base.isEmpty() -> rua
            rua.isEmpty() -> base
            else -> "$base · $rua"
        }
    }
}
