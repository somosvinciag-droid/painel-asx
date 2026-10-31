/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Ligação do receptor com a central inspirada no Headunit Revived / Open Headunit
 * (https://github.com/andreknieriem/headunit-revived), Copyright (C) 2011-2015 Michael A. Reid
 * e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import org.json.JSONObject
import javax.net.ssl.SSLContext

/**
 * "ZLink nosso": o Painel ASX como receptor de Android Auto pelo cabo (modo do cabo "Android Auto pelo Painel").
 *
 *  - A tela continua a do Painel (TelaCarro): o foco de vídeo fica "nativo" e nenhum vídeo é decodificado.
 *  - Som pelo cabo: música e voz do GPS no alto-falante do carro ([AaSom]).
 *  - Música (título, artista, capa, tempo) e próxima curva aparecem na TelaCarro; os botões mandam teclas.
 *  - Microfone: a central NÃO anuncia microfone e se diz "moto" -> o "Ok Google" do celular usa o microfone
 *    do celular. O FALAR do painel é SEMPRE do Jarvis (decisão do Leonardo), ver [FonteDeVoz].
 *  - Ligações: o Android Auto não leva a ligação pelo cabo (só por Bluetooth). Fica no celular.
 *  - Volante/comandos de voz do Jarvis: uma sessão de mídia na central manda as teclas para o celular.
 */
object AndroidAuto {
    @Volatile var ligado = false
        private set
    @Volatile var etapa = "desligado"
        private set
    @Volatile private var sessao: AaSessao? = null
    @Volatile private var aparelho = ""
    @Volatile private var som: AaSom? = null
    @Volatile private var app: Context? = null
    private var tls: SSLContext? = null
    @Volatile var conexoes = 0
        private set
    @Volatile var ultimoFim = ""
        private set

    // ---- música
    @Volatile private var titulo = ""
    @Volatile private var artista = ""
    @Volatile private var duracaoMs = 0L
    @Volatile private var capa: Bitmap? = null
    @Volatile private var tocandoAgora = false
    @Volatile private var posicaoMs = 0L
    @Volatile private var posicaoEm = 0L
    @Volatile var appTocando = ""
        private set

    // ---- navegação
    @Volatile var navegando = false
        private set
    @Volatile private var frase = ""
    @Volatile private var fraseEm = 0L

    @Volatile var vozCelular = false
        private set

    private var midia: MediaSession? = null

    @Synchronized private fun contexto(ctx: Context): SSLContext {
        tls?.let { return it }
        val c = ctx.assets.open("aa/cert.pem").use { cert -> ctx.assets.open("aa/chave.pem").use { chave -> AaTls.contexto(cert, chave) } }
        tls = c
        return c
    }

    /** O cabo (modo "Android Auto pelo Painel") abriu o acessório: começa uma conversa nova numa linha própria. */
    fun entregarCabo(ctx: Context, canal: CanalAoa) {
        val a = ctx.applicationContext
        app = a
        sessao?.let { velha -> Thread({ try { velha.parar("cabo reaberto") } catch (_: Throwable) { } }, "aa-parar").start() }
        Thread({ rodar(a, canal) }, "aa-ler").start()
    }

    /** O celular foi solto: fecha na hora (sem esperar o tempo de queda). */
    fun soltouCabo(nome: String) {
        val s = sessao ?: return
        if (aparelho != nome) return
        Thread({ try { s.parar("cabo solto") } catch (_: Throwable) { } }, "aa-parar").start()
    }

    /** Modo do cabo mudou (ex.: para ZLink): encerra educadamente. */
    fun parar(motivo: String) {
        val s = sessao ?: return
        Thread({ try { s.parar(motivo) } catch (_: Throwable) { } }, "aa-parar").start()
    }

    private fun rodar(ctx: Context, canal: CanalAoa) {
        val inicio = SystemClock.elapsedRealtime()
        var s: AaSessao? = null
        var sm: AaSom? = null
        try {
            val ctxTls = contexto(ctx)
            sm = AaSom(ctx) { tecla(Aa.TECLA_PAUSA) }
            val versao = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1" } catch (_: Exception) { "1" }
            val evs = Eventos(ctx, sm)
            s = AaSessao(TransporteCabo(canal), ctxTls, Aa.Carro(versao = versao), evs)
            evs.dona = s
            synchronized(this) {
                som = sm
                sessao = s
                aparelho = canal.nomeAparelho
                conexoes++
                etapa = "começando"
                ligado = false
                limparEstado()
            }
            Registro.anotar("aa: cabo aberto, falando com o Android Auto do celular")
            noiteAgora(s)
            val motivo = s.rodar()
            ultimoFim = motivo
            Registro.anotar("aa: desligou ($motivo)")
        } catch (t: Throwable) {
            Registro.anotar("ERRO aa: ${t.javaClass.simpleName}: ${t.message?.take(100)}")
        }
        // Os tocadores DESTA conversa sempre são soltos (mesmo se outra conversa já começou no lugar).
        try { sm?.fecharTudo() } catch (_: Throwable) { }
        try { canal.fechar() } catch (_: Throwable) { }
        val eraAtual = synchronized(this) {
            if (s != null && sessao === s) {
                sessao = null
                som = null
                ligado = false
                etapa = "desligado"
                limparEstado()
                true
            } else s == null && sessao == null   // nem começou (erro) e ninguém mais está no cabo
        }
        if (!eraAtual) return   // outra conversa assumiu o cabo: ela cuida de reabrir
        Rede.naTela { soltarMidia() }
        // Cabo ainda plugado (o celular fechou, a central dormiu...): abre de novo. Nunca respondeu = outro app.
        try { CaboUsb.caboTerminou(ctx, canal.nomeAparelho, s?.respondeu == true, SystemClock.elapsedRealtime() - inicio) } catch (_: Throwable) { }
    }

    private fun limparEstado() {
        titulo = ""; artista = ""; duracaoMs = 0; capa = null; tocandoAgora = false; appTocando = ""
        navegando = false; frase = ""; vozCelular = false
    }

    /** Modo noite do Android Auto: faróis (se a central contar) ou pôr do sol. De 1 em 1 min. */
    private fun noiteAgora(s: AaSessao) {
        if (sessao !== s) return
        try { s.noite(LeitorCarro.faroisAcesos() ?: Clima.ehNoite()) } catch (_: Throwable) { }
        Rede.principal.postDelayed({ noiteAgora(s) }, 60_000)
    }

    private class TransporteCabo(private val c: CanalAoa) : AaTransporte {
        override val usb = true
        override fun ler(buf: ByteArray, n: Int, esperaMs: Int) = c.lerBruto(buf, n, esperaMs)
        override fun escrever(b: ByteArray) = c.escreverBruto(b)
        override fun fechar() = c.fechar()
    }

    private class Eventos(private val ctx: Context, private val som: AaSom) : AaEventos {
        /** A conversa dona destes avisos: uma conversa velha (substituída) não mexe mais na tela. */
        @Volatile var dona: AaSessao? = null
        private val atual get() = dona != null && sessao === dona

        override fun etapa(e: String) {
            Registro.anotar("aa: $e")
            if (!atual) return
            etapa = e
            if (e == "canais") ligado = true
            if (e == "canais") Rede.naTela { criarMidia(ctx) }
        }
        override fun registro(linha: String) = Registro.anotar(linha)
        override fun audioInicio(canal: Int, hz: Int, canais: Int) = som.inicio(canal, hz, canais)
        override fun audio(canal: Int, b: ByteArray, ini: Int, n: Int) = som.dados(canal, b, ini, n)
        override fun audioFim(canal: Int) = som.fim(canal)

        override fun musica(d: Aa.MusicaDados) {
            if (!atual) return
            val mudou = d.titulo != titulo || d.artista != artista
            titulo = d.titulo.take(200); artista = d.artista.take(200)
            duracaoMs = d.duracaoS * 1000L
            if (mudou) { posicaoMs = 0; posicaoEm = SystemClock.elapsedRealtime() }
            val c = d.capa
            if (c != null) capa = decodificarCapa(c) else if (mudou) capa = null
            Rede.naTela { atualizarMidia() }
        }

        override fun musicaEstado(e: Aa.MusicaEstado) {
            if (!atual) return
            e.estado?.let { tocandoAgora = it == 2 }
            e.app?.let { if (it.isNotBlank() && it != appTocando) { appTocando = it.take(40); Registro.anotar("aa: tocando pelo $appTocando") } }
            e.segundos?.let { posicaoMs = it * 1000; posicaoEm = SystemClock.elapsedRealtime() }
            Rede.naTela { atualizarMidia() }
        }

        override fun navegacao(frase: String, ativa: Boolean) {
            if (!atual) return
            AndroidAuto.frase = frase
            fraseEm = SystemClock.elapsedRealtime()
            if (ativa != navegando) Registro.anotar("aa: navegação " + if (ativa) "ligada" else "desligada")
            navegando = ativa
        }

        override fun vozSessao(ativa: Boolean) {
            if (!atual) return
            vozCelular = ativa
            Registro.anotar("aa: assistente do celular " + if (ativa) "ouvindo" else "terminou")
        }
    }

    /** Capa pequena (no máximo ~320 px): a central tem pouca memória. */
    private fun decodificarCapa(b: ByteArray): Bitmap? = try {
        val dim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, dim)
        if (dim.outWidth <= 0 || dim.outHeight <= 0 || dim.outWidth > 4096 || dim.outHeight > 4096) null
        else {
            var amostra = 1
            while (maxOf(dim.outWidth, dim.outHeight) / (amostra * 2) >= 320) amostra *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = amostra })
        }
    } catch (_: Throwable) { null }

    // ------------------------------------------------------------------ para a tela, o Jarvis e o conector

    fun temMusica() = ligado && titulo.isNotBlank()

    /** Música do celular pelo Android Auto, no formato da tela do carro. */
    fun tocando(): Musica.Tocando? {
        if (!temMusica()) return null
        var pos = posicaoMs
        if (tocandoAgora) pos += SystemClock.elapsedRealtime() - posicaoEm
        if (duracaoMs > 0) pos = pos.coerceAtMost(duracaoMs)
        val fonte = (if (appTocando.isNotBlank()) appTocando.uppercase() else "MÚSICA") + " · ANDROID AUTO"
        return Musica.Tocando(titulo, artista, capa, pos, duracaoMs, tocandoAgora, fonte)
    }

    fun tituloAgora(): String? = if (!temMusica()) null else if (artista.isBlank()) titulo else "$titulo — $artista"

    /** Próxima curva ("Em 300 m, vire à direita · Av. Brasil"), ou "" (sem navegação ou parada há 1 min). */
    fun navegacaoTexto(): String {
        if (!ligado || !navegando) return ""
        if (SystemClock.elapsedRealtime() - fraseEm > 60_000) return ""
        return frase
    }

    /** Botões da tela do carro: "anterior", "alternar", "proxima". false = Android Auto desligado. */
    fun controle(acao: String): Boolean {
        if (!ligado) return false
        return tecla(when (acao) {
            "anterior" -> Aa.TECLA_ANTERIOR
            "proxima" -> Aa.TECLA_PROXIMA
            "play" -> Aa.TECLA_PLAY
            "pausa" -> Aa.TECLA_PAUSA
            else -> Aa.TECLA_PLAY_PAUSE
        })
    }

    /** Tecla de mídia do Android (KEYCODE_MEDIA_*) para o celular. false = não é tecla de mídia ou AA desligado. */
    fun teclaMidia(codigo: Int): Boolean {
        if (!ligado) return false
        return when (codigo) {
            Aa.TECLA_ANTERIOR, Aa.TECLA_PROXIMA, Aa.TECLA_PLAY, Aa.TECLA_PAUSA, Aa.TECLA_PLAY_PAUSE -> tecla(codigo)
            else -> false
        }
    }

    private fun tecla(codigo: Int): Boolean = try { sessao?.tecla(codigo) == true } catch (_: Throwable) { false }

    /** Para o conector do Claude. */
    fun estadoConector(): JSONObject = JSONObject()
        .put("ligado", ligado)
        .put("etapa", etapa)
        .put("app_tocando", if (ligado) appTocando else "")
        .put("navegando", ligado && navegando)

    fun resumo(): String = when {
        ligado -> "Android Auto ligado pelo cabo" + (if (appTocando.isNotBlank()) " · $appTocando" else "") + (if (navegando) " · navegando" else "")
        sessao != null -> "Android Auto: $etapa"
        else -> "Android Auto desligado" + if (ultimoFim.isNotEmpty()) " (último: $ultimoFim)" else ""
    }

    fun diagnostico(): String = buildString {
        val s = sessao
        append("estado: ").append(resumo()).append('\n')
        append("etapa: ").append(etapa).append(" · conexões desde que o app abriu: ").append(conexoes).append('\n')
        if (s != null) {
            append("celular: ").append(s.marcaCelular.ifBlank { "?" }).append(" · versão do protocolo ").append(s.versaoCelular.ifBlank { "?" }).append('\n')
            append("vídeo recebido e descartado: ").append(s.quadrosVideo).append(" quadros · pedidos de tela recusados: ").append(s.pedidosVideo).append('\n')
        }
        append("som: ").append(som?.estado() ?: "nenhum").append('\n')
        append("música: ").append(if (temMusica()) (if (tocandoAgora) "tocando" else "pausada") + " (" + appTocando.ifBlank { "?" } + ")" else "nada").append('\n')
        append("navegação: ").append(if (navegando) "ligada" else "desligada").append(" · assistente do celular: ").append(if (vozCelular) "ouvindo" else "parado").append('\n')
        if (ultimoFim.isNotEmpty()) append("último fim: ").append(ultimoFim).append('\n')
    }

    // ------------------------------------------------------------------ sessão de mídia (volante e Jarvis)

    private fun criarMidia(ctx: Context) {
        if (midia != null) return
        try {
            val m = MediaSession(ctx, "PainelASX-AndroidAuto")
            m.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { tecla(Aa.TECLA_PLAY) }
                override fun onPause() { tecla(Aa.TECLA_PAUSA) }
                override fun onStop() { tecla(Aa.TECLA_PAUSA) }
                override fun onSkipToNext() { tecla(Aa.TECLA_PROXIMA) }
                override fun onSkipToPrevious() { tecla(Aa.TECLA_ANTERIOR) }
            }, Rede.principal)
            m.isActive = true
            midia = m
            atualizarMidia()
        } catch (e: Throwable) {
            Registro.anotar("aa: sessão de mídia não abriu (${e.javaClass.simpleName})")
        }
    }

    private fun atualizarMidia() {
        val m = midia ?: return
        try {
            m.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artista)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duracaoMs)
                .build())
            var pos = posicaoMs
            if (tocandoAgora) pos += SystemClock.elapsedRealtime() - posicaoEm
            m.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (tocandoAgora) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, 1f)
                .build())
        } catch (_: Throwable) { }
    }

    private fun soltarMidia() {
        val m = midia ?: return
        midia = null
        try { m.isActive = false; m.release() } catch (_: Throwable) { }
    }
}
