/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Sequência da conexão (versão, TLS, autenticação, serviços, canais, foco de áudio/vídeo, sensores,
 * confirmações de mídia) adaptada do Headunit Revived / Open Headunit
 * (https://github.com/andreknieriem/headunit-revived: AapTransport.kt, AapControl.kt,
 * AapMessageHandlerType.kt, HandshakeFrameReader.kt, HandshakeMessagePolicy.kt),
 * Copyright (C) 2011-2015 Michael A. Reid e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

/** O caminho de bytes até o celular (cabo AOA na central; um "celular falso" no teste). */
interface AaTransporte {
    /** Lê até [n] bytes. -1 = caiu; 0 = nada chegou em [esperaMs]. */
    fun ler(buf: ByteArray, n: Int, esperaMs: Int): Int
    /** Escreve o quadro inteiro. false = falhou. */
    fun escrever(b: ByteArray): Boolean
    fun fechar()
    /** USB: pode ter bytes velhos de uma conexão anterior e pode perder o pedido de versão. */
    val usb: Boolean
}

/** O que a sessão conta para fora (a central toca o som e mostra na tela). Tudo pode ser chamado de outra linha. */
interface AaEventos {
    /** Etapas principais ("versão ok", "tls ok", "canais"...): vão para o registro. */
    fun etapa(e: String)
    /** Linha de diagnóstico (nunca com conteúdo sensível). */
    fun registro(linha: String)
    fun audioInicio(canal: Int, hz: Int, canais: Int) {}
    /** PCM 16 bits. COPIE antes de voltar (o buffer é reaproveitado). */
    fun audio(canal: Int, b: ByteArray, ini: Int, n: Int) {}
    fun audioFim(canal: Int) {}
    fun musica(d: Aa.MusicaDados) {}
    fun musicaEstado(e: Aa.MusicaEstado) {}
    /** [frase] vazia = sem navegação. */
    fun navegacao(frase: String, ativa: Boolean) {}
    /** O assistente do Google do celular começou/parou (o "Ok Google" do próprio celular). */
    fun vozSessao(ativa: Boolean) {}
}

/**
 * Uma conexão com o Android Auto do celular. [rodar] bloqueia (linha de leitura) até cair e devolve o motivo.
 * Uma linha própria escreve. Nada de vídeo é decodificado: os quadros que vierem são confirmados e jogados fora.
 */
class AaSessao(
    private val t: AaTransporte,
    private val tlsCtx: SSLContext,
    private val carro: Aa.Carro,
    private val ev: AaEventos,
    private val agora: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Tempos (o teste usa menores). */
    private val esperaVersaoMs: Long = 2_000,
    private val limiteVersaoMs: Long = 10_000,
    private val limiteTlsMs: Long = 15_000,
    private val pingCadaMs: Long = 5_000,
    private val quedaMs: Long = 30_000
) {
    @Volatile var etapa = "começando"
        private set
    /** O celular respondeu à versão (é o Android Auto mesmo, e não outro app com o cabo). */
    @Volatile var respondeu = false
        private set
    /** Canais abertos e serviços combinados: o Android Auto está ligado. */
    @Volatile var conectado = false
        private set
    @Volatile var marcaCelular = ""
        private set
    @Volatile var versaoCelular = ""
        private set
    /** Quadros de vídeo que chegaram mesmo com o foco "nativo" (confirmados e descartados). */
    @Volatile var quadrosVideo = 0L
        private set
    @Volatile var pedidosVideo = 0
        private set

    private val fim = AtomicBoolean(false)
    @Volatile private var motivoFim = ""
    private var motor: AaMotorTls? = null
    private val fila = LinkedBlockingQueue<Saida>(512)
    private val sessoes = IntArray(256)
    private val abertos = BooleanArray(256)
    @Volatile private var noite = false
    @Volatile private var ultimoRecebido = 0L
    private val vistos = HashSet<String>()

    private class Saida(val canal: Int, val flags: Int, val texto: ByteArray)

    private companion object {
        /** Saída que já é um registro TLS cifrado (controle do TLS), mandada como está no canal 0. */
        const val CONTROLE_TLS = -2
    }

    // ------------------------------------------------------------------ comandos de fora

    /** Aperta e solta uma tecla (anterior, play/pausa, próxima...). false = ainda não conectado. */
    fun tecla(codigo: Int): Boolean {
        if (!conectado || fim.get()) return false
        val ns = agora() * 1_000_000
        mandar(Aa.CANAL_ENTRADA, Aa.ENTRADA_EVENTO, Aa.tecla(ns, codigo, true))
        mandar(Aa.CANAL_ENTRADA, Aa.ENTRADA_EVENTO, Aa.tecla(ns + 50_000_000, codigo, false))
        return true
    }

    /** Modo noite do Android Auto (cores escuras). */
    fun noite(n: Boolean) {
        if (n == noite) return
        noite = n
        if (conectado && abertos[Aa.CANAL_SENSORES]) mandar(Aa.CANAL_SENSORES, Aa.SENSOR_EVENTO, Aa.sensorNoite(n))
    }

    /** Encerra educadamente (avisa o celular) e fecha. */
    fun parar(motivo: String) {
        if (fim.get()) return
        if (conectado) {
            mandar(Aa.CANAL_CONTROLE, Aa.TCHAU_PEDIDO, Aa.tchauPedido())
            Thread.sleep(150)
        }
        encerrar(motivo)
    }

    private fun encerrar(motivo: String) {
        if (!fim.compareAndSet(false, true)) return
        motivoFim = motivo
        conectado = false
        fila.clear()
        fila.offer(Saida(-1, 0, ByteArray(0)))   // acorda a linha de escrita
        try { t.fechar() } catch (_: Exception) { }
        try { motor?.fechar() } catch (_: Exception) { }
    }

    private fun mandar(canal: Int, tipo: Int, dados: ByteArray) {
        if (fim.get()) return
        if (!fila.offer(Saida(canal, AaQuadro.flagsPara(canal, tipo), AaQuadro.comTipo(tipo, dados)))) {
            encerrar("fila de envio cheia (o cabo travou?)")
        }
    }

    private fun mudarEtapa(e: String) {
        etapa = e
        ev.etapa(e)
    }

    private fun umaVez(chave: String, linha: String) { if (vistos.add(chave)) ev.registro(linha) }

    // ------------------------------------------------------------------ a conexão

    /** Roda a conexão inteira nesta linha. Devolve o motivo do fim. Nunca lança exceção. */
    fun rodar(): String {
        try {
            if (!versao()) { encerrar(if (motivoFim.isEmpty()) "o celular não respondeu à versão" else motivoFim); return motivoFim }
            if (!tls()) { encerrar(if (motivoFim.isEmpty()) "TLS falhou" else motivoFim); return motivoFim }
            if (!t.escrever(Aa.autenticou())) { encerrar("não consegui mandar a autenticação"); return motivoFim }
            mudarEtapa("tls ok")
            ultimoRecebido = agora()
            Thread({ escrever() }, "aa-escrever").apply { isDaemon = true }.start()
            ler()
        } catch (e: Throwable) {
            encerrar("erro: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
        encerrar(motivoFim.ifEmpty { "fim" })
        return motivoFim
    }

    // ---- quadros abertos (antes do TLS): leitor simples
    private val aberto = ByteArray(4 + 4 + AaQuadro.MAX_CORPO)
    private var abertoTam = 0
    private val pedaco = ByteArray(16384)

    /** Próximo quadro aberto (com cabeçalho). Pula quadros cifrados velhos. null = acabou o tempo. */
    private fun quadroAberto(prazo: Long, paraNoSilencio: Boolean = false, pularCifrados: Boolean = true): ByteArray? {
        while (true) {
            // já tem um quadro inteiro guardado?
            if (abertoTam >= 4) {
                val flags = aberto[1].toInt() and 0xFF
                val corpo = ((aberto[2].toInt() and 0xFF) shl 8) or (aberto[3].toInt() and 0xFF)
                val extra = if (flags and AaQuadro.CIFRADO != 0 && AaQuadro.temTotal(flags)) 4 else 0
                val total = 4 + extra + corpo
                if (abertoTam >= total) {
                    val q = aberto.copyOf(total)
                    System.arraycopy(aberto, total, aberto, 0, abertoTam - total)
                    abertoTam -= total
                    if (flags and AaQuadro.CIFRADO != 0) {
                        if (pularCifrados) continue
                        throw java.io.IOException("quadro cifrado antes da hora")
                    }
                    if (flags != 3 || corpo < 2) throw java.io.IOException("quadro aberto estranho (flags $flags)")
                    return q
                }
            }
            val falta = prazo - agora()
            if (falta <= 0) return null
            val n = t.ler(pedaco, minOf(pedaco.size, aberto.size - abertoTam), minOf(falta, if (paraNoSilencio) 100L else 1000L).toInt().coerceAtLeast(1))
            if (n < 0) { motivoFim = "o cabo caiu"; return null }
            if (n == 0) { if (paraNoSilencio) return null; continue }
            System.arraycopy(pedaco, 0, aberto, abertoTam, n)
            abertoTam += n
        }
    }

    private fun tipoDe(q: ByteArray) = ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF)

    private fun versao(): Boolean {
        mudarEtapa("versão: pedindo")
        if (t.usb) {
            // O Android Auto novo (16.4+) precisa de um respiro logo depois de abrir o cabo (como no Headunit Revived).
            Thread.sleep(500)
            // Bytes velhos de uma conexão anterior: joga fora até o cabo ficar quieto (no máximo 0,5 s).
            val ate = agora() + 500
            var jogados = 0
            while (agora() < ate) {
                val n = t.ler(pedaco, pedaco.size, 100)
                if (n <= 0) break
                jogados += n
            }
            if (jogados > 0) ev.registro("aa: joguei fora $jogados bytes velhos do cabo")
        }
        val limite = agora() + limiteVersaoMs
        val tentativas = if (t.usb) 3 else 1
        for (i in 1..tentativas) {
            if (!t.escrever(Aa.versaoPedido())) { motivoFim = "o cabo não aceitou o pedido de versão"; return false }
            val prazo = if (i < tentativas) minOf(limite, agora() + esperaVersaoMs) else limite
            while (true) {
                val q = try { quadroAberto(prazo) } catch (e: java.io.IOException) { motivoFim = "versão: ${e.message}"; return false } ?: break
                val tipo = tipoDe(q)
                if (q[0].toInt() == 0 && (tipo == 0xFFFF || tipo == Aa.TCHAU_PEDIDO)) { motivoFim = "o celular recusou a conexão"; return false }
                if (q[0].toInt() == 0 && tipo == Aa.VERSAO_RESPOSTA) {
                    val v = Aa.lerVersao(q) ?: continue
                    if (!v.aceita) { motivoFim = "o celular não aceitou a versão ${v.maior}.${v.menor}"; return false }
                    respondeu = true
                    versaoCelular = "${v.maior}.${v.menor}"
                    mudarEtapa("versão ok ($versaoCelular)")
                    return true
                }
            }
            if (motivoFim.isNotEmpty()) return false
            ev.registro("aa: sem resposta de versão (tentativa $i de $tentativas)")
        }
        return false
    }

    private fun tls(): Boolean {
        mudarEtapa("tls: começando")
        val m = AaMotorTls(tlsCtx)
        motor = m
        m.comecar()
        val prazo = agora() + limiteTlsMs
        while (!m.terminouHandshake()) {
            if (agora() > prazo) { motivoFim = "TLS demorou demais"; return false }
            if (m.precisaEnviar()) {
                val out = m.handshakeSaida()
                if (out.isNotEmpty() && !t.escrever(AaQuadro.aberto(Aa.TLS, out))) { motivoFim = "TLS: o cabo não aceitou"; return false }
                continue
            }
            if (m.sobra() > 0) {
                val antes = m.sobra()
                m.handshakeEntrada(ByteArray(0), 0, 0)
                if (m.sobra() != antes || m.precisaEnviar() || m.terminouHandshake()) continue
            }
            val q = try { quadroAberto(prazo, pularCifrados = false) } catch (e: java.io.IOException) { motivoFim = "TLS: ${e.message}"; return false }
                ?: run { if (motivoFim.isEmpty()) motivoFim = "TLS: o celular parou de responder"; return false }
            val tipo = tipoDe(q)
            if (q[0].toInt() == 0 && tipo == Aa.VERSAO_RESPOSTA) continue   // resposta atrasada de um pedido repetido
            if (q[0].toInt() != 0 || tipo != Aa.TLS) { motivoFim = "TLS: mensagem inesperada (tipo $tipo)"; return false }
            m.handshakeEntrada(q, 6, q.size - 6)
        }
        // Sobrou algum quadro já lido? (não deveria: o celular espera a nossa autenticação)
        if (abertoTam > 0) ev.registro("aa: $abertoTam bytes depois do TLS (seguem para a leitura)")
        return true
    }

    // ------------------------------------------------------------------ depois do TLS

    private fun escrever() {
        var ultimoPing = agora()
        try {
            while (!fim.get()) {
                val s = fila.poll(1, TimeUnit.SECONDS)
                if (fim.get()) break
                if (s != null && s.canal == CONTROLE_TLS) {
                    if (!t.escrever(AaQuadro.cifrado(0, 0x0b, s.texto))) { encerrar("escrita: o cabo não aceitou"); return }
                } else if (s != null && s.canal >= 0) {
                    val m = motor ?: break
                    for (c in m.controlePendente()) if (!t.escrever(AaQuadro.cifrado(0, 0x0b, c))) { encerrar("escrita: o cabo não aceitou"); return }
                    val corpo = m.cifrar(s.texto)
                    if (!t.escrever(AaQuadro.cifrado(s.canal, s.flags, corpo))) { encerrar("escrita: o cabo não aceitou"); return }
                }
                val a = agora()
                if (conectado && a - ultimoPing >= pingCadaMs) {
                    ultimoPing = a
                    mandar(Aa.CANAL_CONTROLE, Aa.PING_PEDIDO, ProtoEscritor().varint(1, a * 1000).paraBytes())
                }
                if (a - ultimoRecebido > quedaMs) { encerrar("o celular ficou ${quedaMs / 1000} s sem mandar nada"); return }
            }
        } catch (e: Throwable) {
            encerrar("escrita: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    private val separador = AaQuadro.Separador()
    private val remontador = AaQuadro.Remontador()

    private fun ler() {
        ultimoRecebido = agora()
        val buf = ByteArray(16384)
        mudarEtapa("esperando o pedido de serviços")
        // O que sobrou do leitor aberto vai primeiro.
        if (abertoTam > 0) { separador.alimentar(aberto, 0, abertoTam, ::quadro); abertoTam = 0 }
        while (!fim.get()) {
            val n = t.ler(buf, buf.size, 1000)
            if (n < 0) { encerrar("o cabo caiu"); break }
            if (n == 0) continue
            ultimoRecebido = agora()
            separador.alimentar(buf, 0, n, ::quadro)
        }
    }

    private fun quadro(canal: Int, flags: Int, total: Int, b: ByteArray, ini: Int, n: Int) {
        if (fim.get()) return
        val m = motor ?: return
        if (flags and AaQuadro.CIFRADO == 0) {
            // Aberto depois do TLS. Pedaço atrasado do aperto de mão (ex.: TLS 1.3 manda o "ticket" depois):
            // passa pelo motor TLS para a numeração dos registros não se perder. O resto (versão atrasada) é ignorado.
            if (canal == 0 && n >= 2 && b[ini].toInt() == 0 && b[ini + 1].toInt() == Aa.TLS) {
                m.decifrar(b, ini + 2, n - 2)
                for (c in m.controlePendente()) fila.offer(Saida(CONTROLE_TLS, 0x0b, c))
            } else umaVez("aberto", "aa: quadro aberto depois do TLS ignorado (canal $canal)")
            return
        }
        val nTexto = m.decifrar(b, ini, n)
        // Registros só de controle do TLS (raros): já cifrados, a linha de escrita manda no canal 0.
        for (c in m.controlePendente()) fila.offer(Saida(CONTROLE_TLS, 0x0b, c))
        val msg = remontador.aceitar(canal, flags, total, m.buffer(), 0, nTexto) ?: return
        try {
            tratar(msg)
        } catch (e: ProtoErro) {
            umaVez("proto${msg.canal}:${msg.tipo}", "aa: mensagem mal formada no canal ${Aa.nomeCanal(msg.canal)} tipo ${msg.tipo} (${e.message})")
        }
    }

    // ------------------------------------------------------------------ mensagens

    private fun tratar(m: AaQuadro.Mensagem) {
        val c = m.canal
        if (m.tipo == Aa.CANAL_ABRIR) { abrirCanal(m); return }
        if (m.controle || c == Aa.CANAL_CONTROLE) { controle(m); return }
        when {
            c == Aa.CANAL_VIDEO -> video(m)
            Aa.ehAudio(c) -> audio(m)
            c == Aa.CANAL_SENSORES -> sensores(m)
            c == Aa.CANAL_ENTRADA -> if (m.tipo == Aa.ENTRADA_PEDIDO) mandar(c, Aa.ENTRADA_RESPOSTA, Aa.status(0))
            c == Aa.CANAL_MUSICA_INFO -> musica(m)
            c == Aa.CANAL_NAVEGACAO -> navegacao(m)
            else -> umaVez("c$c", "aa: mensagem num canal desconhecido ($c, tipo ${m.tipo})")
        }
    }

    private fun abrirCanal(m: AaQuadro.Mensagem) {
        val c = m.canal
        mandar(c, Aa.CANAL_ABERTO, Aa.status(0))
        abertos[c] = true
        ev.registro("aa: canal ${Aa.nomeCanal(c)} aberto")
        if (c == Aa.CANAL_SENSORES) mandar(c, Aa.SENSOR_EVENTO, Aa.sensorDirigindo(0))
        if (!conectado) {
            conectado = true
            mudarEtapa("canais")
        }
    }

    private fun controle(m: AaQuadro.Mensagem) {
        val c = m.canal
        when (m.tipo) {
            Aa.SERVICOS_PEDIDO -> {
                // Só a marca do celular vai para o registro (o nome pode ser o do dono).
                marcaCelular = try { Aa.campoTexto(m.dados, m.ini, m.fim, 5).take(30) } catch (_: ProtoErro) { "" }
                mandar(Aa.CANAL_CONTROLE, Aa.SERVICOS_RESPOSTA, Aa.servicos(carro))
                mudarEtapa("serviços (celular ${marcaCelular.ifBlank { "?" }})")
            }
            Aa.PING_PEDIDO -> mandar(c, Aa.PING_RESPOSTA, Aa.pingResposta(Aa.campoInt(m.dados, m.ini, m.fim, 1, 0)))
            Aa.PING_RESPOSTA -> { }
            Aa.NAV_FOCO_PEDIDO -> mandar(c, Aa.NAV_FOCO, Aa.navFoco())
            Aa.TCHAU_PEDIDO -> {
                mandar(c, Aa.TCHAU_RESPOSTA, Aa.tchauResposta())
                ev.registro("aa: o celular encerrou (motivo ${Aa.campoInt(m.dados, m.ini, m.fim, 1)})")
                Thread.sleep(200)
                encerrar("o celular encerrou o Android Auto")
            }
            Aa.TCHAU_RESPOSTA -> encerrar("encerrado")
            Aa.VOZ_SESSAO -> ev.vozSessao(Aa.campoInt(m.dados, m.ini, m.fim, 1) == 1L)
            Aa.FOCO_AUDIO_PEDIDO -> {
                // Sempre concede (senão o celular toca no próprio alto-falante). O som da central é quem decide o volume.
                val pedido = Aa.campoInt(m.dados, m.ini, m.fim, 1, 0).toInt()
                val estado = when (pedido) { 1 -> Aa.FOCO_GANHO; 2 -> Aa.FOCO_GANHO_PASSAGEIRO; 3 -> Aa.FOCO_SO_GUIA; 4 -> Aa.FOCO_PERDA; else -> 0 }
                if (estado != 0) mandar(c, Aa.FOCO_AUDIO, Aa.focoAudio(estado, false))
                umaVez("foco$pedido", "aa: foco de áudio pedido ($pedido) e concedido")
            }
            Aa.CANAL_FECHOU -> ev.registro("aa: o celular fechou o canal ${Aa.nomeCanal(c)}")
            Aa.VERSAO_RESPOSTA -> { }
            else -> umaVez("ctl${m.tipo}", "aa: mensagem de controle ${m.tipo} ignorada")
        }
    }

    private fun video(m: AaQuadro.Mensagem) {
        val c = m.canal
        when (m.tipo) {
            Aa.MIDIA_DADOS -> {
                quadrosVideo++
                if (quadrosVideo == 1L) ev.registro("aa: o celular mandou vídeo mesmo com a tela nativa (confirmo e jogo fora)")
                mandar(c, Aa.MIDIA_ACK, Aa.midiaAck(sessoes[c]))
            }
            Aa.MIDIA_CONFIG_CODEC -> { }
            Aa.MIDIA_PREPARAR -> {
                mandar(c, Aa.MIDIA_CONFIG, Aa.midiaConfig(2))
                // A tela é NOSSA: foco de vídeo "nativo" (o celular não desenha nem manda vídeo).
                mandar(c, Aa.VIDEO_FOCO, Aa.videoFoco(Aa.VIDEO_NATIVO, true))
                ev.registro("aa: vídeo combinado e recusado (tela do Painel)")
            }
            Aa.MIDIA_INICIO -> { sessoes[c] = Aa.campoInt(m.dados, m.ini, m.fim, 1, 0).toInt(); umaVez("vinicio", "aa: o celular abriu o vídeo (sessão)") }
            Aa.MIDIA_FIM -> { }
            Aa.VIDEO_FOCO_PEDIDO -> {
                pedidosVideo++
                val modo = Aa.campoInt(m.dados, m.ini, m.fim, 2, 0)
                if (pedidosVideo <= 3 || pedidosVideo % 20 == 0) ev.registro("aa: o celular pediu a tela (modo $modo, pedido $pedidosVideo); continua a do Painel")
                mandar(c, Aa.VIDEO_FOCO, Aa.videoFoco(Aa.VIDEO_NATIVO, false))
            }
            else -> umaVez("v${m.tipo}", "aa: vídeo tipo ${m.tipo} ignorado")
        }
    }

    private fun configAudio(c: Int) = when (c) { Aa.CANAL_MIDIA -> 48000 to 2; else -> 16000 to 1 }

    private fun audio(m: AaQuadro.Mensagem) {
        val c = m.canal
        when (m.tipo) {
            Aa.MIDIA_DADOS -> {
                if (!m.descartada && m.tamanho > 8) {
                    try { ev.audio(c, m.dados, m.ini + 8, m.tamanho - 8) } catch (_: Exception) { }
                }
                // Confirma SEMPRE (mesmo descartado): o celular só manda mais com confirmação.
                mandar(c, Aa.MIDIA_ACK, Aa.midiaAck(sessoes[c]))
            }
            Aa.MIDIA_CONFIG_CODEC -> { }
            Aa.MIDIA_PREPARAR -> {
                mandar(c, Aa.MIDIA_CONFIG, Aa.midiaConfig(16))
                mandar(Aa.CANAL_CONTROLE, Aa.FOCO_AUDIO, Aa.focoAudio(Aa.FOCO_GANHO, true))
            }
            Aa.MIDIA_INICIO -> {
                sessoes[c] = Aa.campoInt(m.dados, m.ini, m.fim, 1, 0).toInt()
                val (hz, ch) = configAudio(c)
                umaVez("ainicio$c", "aa: som ${Aa.nomeCanal(c)} começou")
                ev.audioInicio(c, hz, ch)
            }
            Aa.MIDIA_FIM -> ev.audioFim(c)
            Aa.MIC_PEDIDO -> {
                // Não anunciamos microfone (o celular usa o dele). Se pedir mesmo assim: recusa.
                umaVez("mic", "aa: o celular pediu o microfone da central (recusado: ele usa o próprio)")
                mandar(c, Aa.MIC_RESPOSTA, Aa.micResposta(-7, 0))
            }
            Aa.MIDIA_ACK -> { }
            else -> umaVez("a${m.tipo}", "aa: áudio tipo ${m.tipo} ignorado")
        }
    }

    private fun sensores(m: AaQuadro.Mensagem) {
        if (m.tipo != Aa.SENSOR_PEDIDO) return
        val tipo = Aa.campoInt(m.dados, m.ini, m.fim, 1, 0).toInt()
        mandar(m.canal, Aa.SENSOR_RESPOSTA, Aa.status(0))
        when (tipo) {
            Aa.SENSOR_NOITE -> mandar(m.canal, Aa.SENSOR_EVENTO, Aa.sensorNoite(noite))
            Aa.SENSOR_DIRIGINDO -> mandar(m.canal, Aa.SENSOR_EVENTO, Aa.sensorDirigindo(0))
        }
        ev.registro("aa: sensor $tipo pedido e ligado")
    }

    private var ultimaMusica = ""

    private fun musica(m: AaQuadro.Mensagem) {
        if (m.descartada) return
        when (m.tipo) {
            Aa.MUSICA_DADOS -> {
                val d = Aa.lerMusicaDados(m.dados, m.ini, m.fim)
                if (ultimaMusica.isEmpty()) mudarEtapa("mídia")
                ultimaMusica = d.titulo
                ev.musica(d)
            }
            Aa.MUSICA_ESTADO -> ev.musicaEstado(Aa.lerMusicaEstado(m.dados, m.ini, m.fim))
        }
    }

    private var curva: Aa.Curva? = null
    private var distancia: Aa.Distancia? = null
    private var navegando = false

    private fun navegacao(m: AaQuadro.Mensagem) {
        if (m.descartada) return
        when (m.tipo) {
            Aa.NAV_INICIO -> { curva = null; distancia = null }
            Aa.NAV_PAROU -> { curva = null; distancia = null; navegando = false; ev.navegacao("", false); return }
            Aa.NAV_STATUS -> {
                val s = Aa.campoInt(m.dados, m.ini, m.fim, 1, 0).toInt()
                if (s != 1 && s != 3) { curva = null; distancia = null; navegando = false; ev.navegacao("", false); return }
            }
            Aa.NAV_CURVA -> curva = Aa.lerCurvaAntiga(m.dados, m.ini, m.fim)
            Aa.NAV_DISTANCIA -> distancia = Aa.lerDistanciaAntiga(m.dados, m.ini, m.fim)
            Aa.NAV_ESTADO -> Aa.lerNavEstado(m.dados, m.ini, m.fim)?.let { curva = it }
            Aa.NAV_POSICAO -> Aa.lerNavPosicao(m.dados, m.ini, m.fim)?.let { distancia = it }
            else -> { umaVez("n${m.tipo}", "aa: navegação tipo ${m.tipo} ignorada"); return }
        }
        val frase = Aa.fraseNavegacao(curva, distancia)
        if (frase.isNotEmpty()) {
            if (!navegando) { navegando = true; mudarEtapa("navegação") }
            ev.navegacao(frase, true)
        }
    }
}
