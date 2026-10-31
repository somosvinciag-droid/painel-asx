package br.com.leonardo.painelasx.celular

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * A música que toca no celular (Spotify, YouTube Music...): título, artista, capa e controles.
 * Precisa do "acesso às notificações" ligado para este app. Roda na linha principal.
 */
object MusicaCel {
    private var msm: MediaSessionManager? = null
    private var atual: MediaController? = null
    @Volatile var ligada = false
        private set
    private var capaChave = ""
    private var capaMsg: JSONObject? = null
    private var capaId = ""

    private val aoMudarSessoes = MediaSessionManager.OnActiveSessionsChangedListener { escolher(it ?: emptyList()) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = mandarTudo()
        override fun onPlaybackStateChanged(s: PlaybackState?) = mandarMusica()
        override fun onSessionDestroyed() { reescolher() }
    }

    fun iniciar(ctx: Context) {
        if (ligada) return
        val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(ctx, Ouvinte::class.java)
        try {
            m.addOnActiveSessionsChangedListener(aoMudarSessoes, comp, Ponte.h)
            msm = m
            ligada = true
            escolher(m.getActiveSessions(comp))
        } catch (_: SecurityException) {
            // acesso às notificações ainda desligado: a tela do app mostra o passo
        } catch (_: Exception) {
        }
    }

    private var appCtx: Context? = null
    fun contexto(ctx: Context) { appCtx = ctx.applicationContext }

    private fun reescolher() {
        val m = msm ?: return
        val c = appCtx ?: return
        try { escolher(m.getActiveSessions(ComponentName(c, Ouvinte::class.java))) } catch (_: Exception) { escolher(emptyList()) }
    }

    private fun escolher(lista: List<MediaController>) {
        val s = lista.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: lista.firstOrNull()
        if (s != null && s.sessionToken == atual?.sessionToken) return
        try { atual?.unregisterCallback(cb) } catch (_: Exception) { }
        atual = s
        try { s?.registerCallback(cb, Ponte.h) } catch (_: Exception) { }
        mandarTudo()
    }

    private fun nomeDoApp(pacote: String) = when {
        pacote.contains("spotify") -> "Spotify"
        pacote.contains("youtube") -> "YouTube Music"
        pacote.contains("deezer") -> "Deezer"
        pacote.contains("amazon") -> "Amazon Music"
        pacote.contains("soundcloud") -> "SoundCloud"
        pacote.contains("tidal") -> "Tidal"
        pacote.contains("podcast") -> "Podcasts"
        else -> "Música"
    }

    /** Estado atual da música (para mandar ao carro). */
    fun mensagem(): JSONObject {
        val c = atual ?: return JSONObject().put("t", "musica").put("ativa", false)
        val m = c.metadata
        val st = c.playbackState
        var pos = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        return JSONObject().put("t", "musica").put("ativa", m != null)
            .put("app", nomeDoApp(c.packageName))
            .put("titulo", m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artista", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            .put("album", m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("duracao_ms", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            .put("posicao_ms", pos.coerceAtLeast(0))
            .put("tocando", st?.state == PlaybackState.STATE_PLAYING)
            .put("seek", st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L)
            .put("capa", capaId)
    }

    fun tocando() = atual?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Música mudou: prepara a capa (só se mudou) e manda tudo. */
    private fun mandarTudo() {
        val m = atual?.metadata
        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (m == null || bmp == null) {
            capaChave = ""; capaId = ""; capaMsg = null
        } else {
            val chave = (m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") + "|" +
                (m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "") + "|" + bmp.width + "x" + bmp.height
            if (chave != capaChave) {
                capaChave = chave
                val jpg = try { capaJpeg(bmp) } catch (_: Exception) { null }
                if (jpg == null) { capaId = ""; capaMsg = null } else {
                    val id = Integer.toHexString(jpg.contentHashCode())
                    if (id != capaId) {
                        capaId = id
                        capaMsg = JSONObject().put("t", "capa").put("id", id).put("jpg", Base64.encodeToString(jpg, Base64.NO_WRAP))
                        capaMsg?.let { Ponte.enviar(it) }
                    }
                }
            }
        }
        mandarMusica()
    }

    private fun mandarMusica() = Ponte.enviar(mensagem())

    /** Conexão nova: manda a música e a capa atuais. */
    fun reenviarTudo() {
        capaMsg?.let { Ponte.enviar(it) }
        mandarMusica()
    }

    /** A cada 5 s, com música tocando: corrige a posição na tela do carro. */
    fun tique() { if (tocando()) mandarMusica() }

    fun comando(acao: String, ms: Long) {
        val c = atual ?: return
        val tc = c.transportControls
        when (acao) {
            "play" -> tc.play()
            "pausa" -> tc.pause()
            "alternar" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            "proxima" -> tc.skipToNext()
            "anterior" -> tc.skipToPrevious()
            "ir" -> if (ms >= 0) tc.seekTo(ms)
        }
    }

    /** Capa em JPEG de até 320 px (qualidade 75): ~15–40 KB. */
    private fun capaJpeg(b0: Bitmap): ByteArray {
        val b = if (Build.VERSION.SDK_INT >= 26 && b0.config == Bitmap.Config.HARDWARE) b0.copy(Bitmap.Config.ARGB_8888, false) else b0
        val lado = maxOf(b.width, b.height)
        val esc = if (lado > 320) {
            val f = 320f / lado
            Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
        } else b
        val out = ByteArrayOutputStream()
        esc.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray()
    }
}
