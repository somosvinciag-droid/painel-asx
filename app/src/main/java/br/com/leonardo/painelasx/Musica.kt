package br.com.leonardo.painelasx

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import org.json.JSONArray

/**
 * Música por voz:
 *  - Spotify: "toca a playlist Rock", "toca Iron Maiden", "toca Iron Man do Black Sabbath", "continua a música";
 *  - rádio pela internet: "toca a rádio Itatiaia" (lista aberta Radio Browser, sem custo);
 *  - o que está tocando agora (para a tela do carro), se o "acesso às notificações" estiver liberado.
 */
object Musica {
    const val SPOTIFY = "com.spotify.music"
    private var radio: MediaPlayer? = null
    @Volatile var radioTocando: String? = null
        private set

    private fun abrirBusca(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Registro.anotar("música: não abriu a busca (${e.javaClass.simpleName})")
        false
    }

    /** Busca e toca no Spotify. [tipo]: "playlist", "artista", "musica" ou "" (qualquer). */
    fun spotify(ctx: Context, busca: String, tipo: String = "", artista: String? = null): String {
        pararRadio()
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY)
            .putExtra(SearchManager.QUERY, if (artista != null) "$busca $artista" else busca)
        when (tipo) {
            "playlist" -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
                .putExtra("android.intent.extra.playlist", busca)
            "artista" -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, busca)
            "musica" -> {
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    .putExtra(MediaStore.EXTRA_MEDIA_TITLE, busca)
                if (artista != null) i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artista)
            }
            else -> i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        if (ctx.packageManager.getLaunchIntentForPackage(SPOTIFY) == null) return "O Spotify não está instalado na central."
        if (!abrirBusca(ctx, i)) return "Não consegui pedir ao Spotify."
        // Se o Spotify só abrir sem tocar, dá o play.
        Rede.principal.postDelayed({
            if (!Voz.tocandoMusica()) Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
        }, 6000)
        val o = if (artista != null) "$busca, de $artista" else busca
        return when (tipo) { "playlist" -> "Tocando a playlist $o."; "artista" -> "Tocando $o."; else -> "Tocando $o." }
    }

    fun continuar(ctx: Context): String {
        pararRadio()
        if (ctx.packageManager.getLaunchIntentForPackage(SPOTIFY) != null) {
            Apps.abrir(ctx, SPOTIFY)
            Rede.principal.postDelayed({ Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PLAY) }, 3500)
        } else {
            Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        return "Continuando de onde parou."
    }

    // ------------------------------------------------------------------ rádio online

    private val SERVIDORES = listOf("de1.api.radio-browser.info", "fi1.api.radio-browser.info", "all.api.radio-browser.info")

    fun radioOnline(ctx: Context, nome: String): String {
        val app = ctx.applicationContext
        Rede.emSegundoPlano("radio") {
            var estacao: org.json.JSONObject? = null
            for (s in SERVIDORES) {
                for (pais in listOf("&countrycode=BR", "")) {
                    val t = Rede.get("https://$s/json/stations/search?limit=5&hidebroken=true&order=votes&reverse=true$pais&name=" + Rede.enc(nome), 8000) ?: continue
                    val a = try { JSONArray(t) } catch (_: Exception) { JSONArray() }
                    if (a.length() > 0) { estacao = a.getJSONObject(0); break }
                }
                if (estacao != null) break
            }
            val e = estacao
            if (e == null) {
                Rede.naTela { Voz.falar("Não achei a rádio $nome.") }
                return@emSegundoPlano
            }
            val url = e.optString("url_resolved").ifBlank { e.optString("url") }
            val titulo = e.optString("name").trim().ifBlank { nome }
            Rede.naTela { tocarRadio(app, url, titulo) }
        }
        return "Procurando a rádio $nome…"
    }

    private fun tocarRadio(ctx: Context, url: String, titulo: String) {
        pararRadio()
        Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, w, x ->
                Registro.anotar("rádio: erro $w/$x em $titulo")
                if (radio === mp) {
                    Voz.falar("A rádio $titulo não abriu agora.")
                    pararRadio()
                }
                true
            }
            // A transmissão acabou/caiu sem erro: a tela não fica mostrando "Rádio online" em silêncio.
            mp.setOnCompletionListener {
                if (radio === mp) { Registro.anotar("rádio: parou ($titulo)"); pararRadio() }
            }
            mp.prepareAsync()
            radio = mp
            radioTocando = titulo
            Voz.falar("Rádio $titulo.")
            Registro.anotar("rádio: $titulo ($url)")
            val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            val antes = (p.getString("radios", "") ?: "").split('|').filter { it.isNotBlank() && it != titulo }
            p.edit().putString("radios", (listOf(titulo) + antes).take(8).joinToString("|")).apply()
        } catch (e: Exception) {
            Registro.anotar("rádio: falhou (${e.message})")
        }
    }

    fun pararRadio(): Boolean {
        val r = radio ?: return false
        radio = null
        radioTocando = null
        try { r.stop() } catch (_: Exception) { }
        try { r.release() } catch (_: Exception) { }
        return true
    }

    // ------------------------------------------------------------------ o que está tocando

    fun podeVerMusica(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(ctx.packageName)
    }

    /** O que está tocando, com capa e tempo (para a tela do carro F3). */
    data class Tocando(val titulo: String, val artista: String, val capa: android.graphics.Bitmap?, val posicaoMs: Long, val duracaoMs: Long, val tocando: Boolean, val fonte: String)

    // ---- sessão de mídia acompanhada: os dados da música (com a capa) só vêm quando mudam.
    // Antes, a tela do carro pedia a cada 2 s os dados completos ao Android, e cada pedido copiava a capa
    // inteira (uma imagem de centenas de KB) de outro app para o nosso: memória e processador à toa.
    private val travaSessao = Any()
    private var sessao: MediaController? = null
    @Volatile private var meta: MediaMetadata? = null
    private var metaLidaEm = 0L
    private val avisosSessao = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { meta = m }
        override fun onSessionDestroyed() { synchronized(travaSessao) { soltarSessao() } }
    }

    private fun soltarSessao() {
        try { sessao?.unregisterCallback(avisosSessao) } catch (_: Exception) { }
        sessao = null
        meta = null
    }

    /** A sessão que está tocando (ou a primeira) e os dados guardados dela. */
    private fun sessaoAtual(ctx: Context): Pair<MediaController, MediaMetadata?>? {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessoes = msm.getActiveSessions(ComponentName(ctx, Ouvinte::class.java))
        val s = sessoes.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessoes.firstOrNull()
        synchronized(travaSessao) {
            if (s == null) { soltarSessao(); return null }
            val agora = android.os.SystemClock.elapsedRealtime()
            val atual = sessao
            if (atual == null || atual.sessionToken != s.sessionToken) {
                soltarSessao()
                sessao = s
                meta = s.metadata
                metaLidaEm = agora
                try { s.registerCallback(avisosSessao, Rede.principal) } catch (_: Exception) { }
            } else if (agora - metaLidaEm > 60_000) {
                // Reserva: se algum aviso de "música mudou" se perder, no máximo 1 min depois a tela se acerta.
                meta = atual.metadata
                metaLidaEm = agora
            }
            return sessao!! to meta
        }
    }

    fun tocando(ctx: Context): Tocando? {
        radioTocando?.let { return Tocando(it, "Rádio online", null, 0, 0, true, "RÁDIO") }
        // Celular ligado (app Painel ASX Celular): a música dele vem primeiro, com a capa.
        Celular.tocando()?.let { return it }
        if (!podeVerMusica(ctx)) return null
        return try {
            val (s, m) = sessaoAtual(ctx) ?: return null
            if (m == null) return null
            val titulo = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artista = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val capa = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val st = s.playbackState
            var pos = st?.position ?: 0L
            if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
                pos += ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            }
            val fonte = when {
                s.packageName.contains("spotify") -> "SPOTIFY"
                s.packageName.contains("youtube") -> "YOUTUBE MUSIC"
                s.packageName.contains("deezer") -> "DEEZER"
                else -> "MÚSICA"
            }
            Tocando(titulo, artista, capa, pos, m.getLong(MediaMetadata.METADATA_KEY_DURATION), st?.state == PlaybackState.STATE_PLAYING, fonte)
        } catch (_: Exception) {
            null
        }
    }

    /** "Música — Artista" do app que está tocando agora, ou null. */
    fun tocandoAgora(ctx: Context): String? {
        radioTocando?.let { return "📻 $it" }
        Celular.tituloAgora()?.let { return it }
        if (!podeVerMusica(ctx)) return null
        return try {
            val m = sessaoAtual(ctx)?.second ?: return null
            val titulo = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artista = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (artista.isNullOrBlank()) titulo else "$titulo — $artista"
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * "Anjo da guarda" (v19). Além de deixar o app ver a música, este serviço é RELIGADO pelo próprio Android
 * depois que a central fecha o Painel ASX (o que não acontece com a Acessibilidade). Quando ele volta,
 * religamos o serviço fixo e, se a central dormiu desde a última saudação, o Jarvis dá as boas-vindas.
 */
class Ouvinte : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        val app = applicationContext
        val idade = (android.os.SystemClock.elapsedRealtime() - App.inicio) / 1000
        Registro.anotar("anjo da guarda: religado pelo sistema (app aberto há ${idade}s)")
        ServicoFixo.iniciar(app)
        Thread {
            val estado = Despertador.estadoMcu()
            if (estado != "3" && Jarvis.devoSaudar(app)) {
                Registro.anotar("anjo da guarda: central acordada e ainda sem saudação → Jarvis")
                Rede.naTela { Voz.iniciar(app); Jarvis.partida(app) }
            }
        }.start()
    }
}
