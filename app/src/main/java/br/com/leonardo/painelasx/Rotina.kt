package br.com.leonardo.painelasx

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * A rotina de quando o carro liga: volume, musica, GPS.
 * Roda no maximo uma vez a cada 10 minutos (a central acorda varias vezes).
 */
object Rotina {
    private const val PREFS = "rotina"
    private const val INTERVALO_MS = 10 * 60 * 1000L
    private var ultimaVez = 0L

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ligada(ctx: Context) = prefs(ctx).getBoolean("ligada", false)
    fun musica(ctx: Context) = prefs(ctx).getString("musica", null)
    fun navegacao(ctx: Context) = prefs(ctx).getString("navegacao", null)
    fun volume(ctx: Context) = prefs(ctx).getInt("volume", 40)
    fun tocarSozinho(ctx: Context) = prefs(ctx).getBoolean("tocar", true)
    /** Pedido do Leonardo (31/10): ao ligar o carro, a música do celular (Spotify pelo Bluetooth) volta sozinha. */
    const val MUSICA_BT = "com.nwd.bt.music"
    fun musicaBtSozinha(ctx: Context) = prefs(ctx).getBoolean("musica_bt", true)

    fun talvezRodar(ctx: Context, motivo: String) {
        if (!ligada(ctx)) return
        val agora = SystemClock.elapsedRealtime()
        if (ultimaVez != 0L && agora - ultimaVez < INTERVALO_MS) return
        ultimaVez = agora
        Registro.anotar("rotina: comecou ($motivo)")
        rodar(ctx)
    }

    fun rodar(ctx: Context) {
        ultimaVez = SystemClock.elapsedRealtime()
        val h = Handler(Looper.getMainLooper())
        ajustarVolume(ctx, volume(ctx))
        val mus = musica(ctx) ?: if (musicaBtSozinha(ctx)) MUSICA_BT else null
        val nav = navegacao(ctx)
        var espera = 1500L
        // Com o celular no cabo (Android Auto pelo Painel), a música vem pelo Android Auto: a Música BT NÃO abre por
        // cima da tela dele (teste de 01/11 17:11). Conferido na hora de cada passo (o AA liga uns segundos depois).
        val semBt = { mus == MUSICA_BT && (AndroidAuto.ligado || CaboUsb.celularNoCabo(ctx)) }
        if (mus != null) {
            h.postDelayed({
                if (semBt()) Registro.anotar("rotina: celular no cabo (Android Auto): não abri a Música BT")
                else Apps.abrir(ctx, mus)
            }, espera)
            if (tocarSozinho(ctx)) {
                h.postDelayed({ if (!semBt()) teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PLAY) }, espera + 4000)
                // O Bluetooth do celular às vezes conecta uns segundos depois: um segundo "play" mais tarde.
                if (mus == MUSICA_BT) h.postDelayed({
                    if (!semBt() && !Musica.centralTocando(ctx)) teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
                }, espera + 12000)
            }
            espera += 6000
        }
        if (nav != null) {
            h.postDelayed({ Apps.abrir(ctx, nav) }, espera)
        } else if (mus == MUSICA_BT) {
            // A Música BT só precisa ficar como fonte do som: a tela volta para o painel do carro.
            h.postDelayed({ Painel.abrir(ctx) }, espera)
        }
    }

    fun ajustarVolume(ctx: Context, porcento: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (max * porcento.coerceIn(0, 100) / 100.0).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    fun mudarVolume(ctx: Context, passo: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val atual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val delta = (max * passo / 100.0).toInt().let { if (it == 0) (if (passo > 0) 1 else -1) else it }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (atual + delta).coerceIn(0, max), 0)
    }

    fun teclaMidia(ctx: Context, codigo: Int) {
        // Android Auto pelo cabo e nada tocando na própria central: a tecla vai para o celular (comandos do Jarvis).
        if (!Musica.centralTocando(ctx) && AndroidAuto.teclaMidia(codigo)) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, codigo))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, codigo))
    }
}
