package br.com.leonardo.painelasx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * A voz do Jarvis: português do Brasil, masculina e grave quando a central tiver.
 *  - RESPOSTA: resposta a um pedido seu (sempre fala).
 *  - IMPORTANTE: alerta (velocidade, emergência, manutenção): abaixa a música e fala.
 *  - INFO: informação comum: se estiver tocando música, NÃO fala (só aparece na tela).
 */
object Voz {
    enum class Tipo { RESPOSTA, IMPORTANTE, INFO }

    private var tts: TextToSpeech? = null
    private var pronta = false
    private class Pendente(val texto: String, val tipo: Tipo, val entao: (() -> Unit)?)
    private val pendentes = ArrayList<Pendente>()
    private var am: AudioManager? = null
    private var foco: AudioFocusRequest? = null
    private val depois = HashMap<String, () -> Unit>()
    @Volatile var ultimaFala = ""
        private set

    fun iniciar(ctx: Context) {
        if (tts != null) return
        val app = ctx.applicationContext
        am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        VozPropria.preparar(app)
        // Prefere a voz do Google, se estiver instalada (a central pode ter outro motor padrão).
        val google = try { app.packageManager.getPackageInfo("com.google.android.tts", 0); true } catch (_: Exception) { false }
        val aoIniciar = TextToSpeech.OnInitListener { status ->
            Registro.anotar("voz do android: " + (if (status == TextToSpeech.SUCCESS) "ok" else "falhou ($status)") +
                " · motor " + (tts?.defaultEngine ?: "?") + " · instalados: " + (tts?.engines?.joinToString { it.name } ?: "nenhum"))
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@OnInitListener
                t.setLanguage(Locale("pt", "BR"))
                escolherVozMasculina(t)
                t.setPitch(0.82f)       // mais grave
                t.setSpeechRate(0.97f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { terminou(id) }
                    @Deprecated("antigo") override fun onError(id: String?) { terminou(id) }
                })
                pronta = true
                esvaziarPendentes()
            }
        }
        tts = if (google) TextToSpeech(app, aoIniciar, "com.google.android.tts") else TextToSpeech(app, aoIniciar)
    }

    /** Reinicia a voz do Android (ex.: depois de instalar a voz do Google). */
    fun reiniciar(ctx: Context) {
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        pronta = false
        iniciar(ctx)
    }

    fun qualVoz(): String = when {
        VozPropria.pronta() -> "voz própria"
        pronta -> "voz do Android (${tts?.defaultEngine ?: "?"})"
        else -> "sem voz (${VozPropria.estado})"
    }

    private fun escolherVozMasculina(t: TextToSpeech) {
        try {
            val vozes = t.voices?.filter { it.locale.language == "pt" && (it.locale.country == "BR" || it.locale.country.isEmpty()) } ?: return
            val preferida = vozes.firstOrNull { it.name.contains("ptd", true) && !it.isNetworkConnectionRequired }
                ?: vozes.firstOrNull { it.name.contains("ptd", true) }
                ?: vozes.firstOrNull { (it.name.contains("male", true) && !it.name.contains("female", true)) }
                ?: vozes.firstOrNull { !it.isNetworkConnectionRequired }
            if (preferida != null) t.voice = preferida
        } catch (_: Exception) {
        }
    }

    private fun terminou(id: String?) {
        try { foco?.let { am?.abandonAudioFocusRequest(it) } } catch (_: Exception) { }
        foco = null
        val acao = synchronized(depois) { depois.remove(id ?: "") }
        acao?.invoke()
    }

    fun tocandoMusica() = am?.isMusicActive == true

    /** Fala o que ficou esperando a voz ficar pronta (chamar na linha principal). */
    fun esvaziarPendentes() {
        val fila = ArrayList(pendentes); pendentes.clear()
        fila.forEach { falar(it.texto, it.tipo, it.entao) }
    }

    /** Fala. [entao] roda quando terminar de falar. */
    fun falar(texto: String, tipo: Tipo = Tipo.RESPOSTA, entao: (() -> Unit)? = null) {
        if (texto.isBlank()) { entao?.invoke(); return }
        if (!pronta && !VozPropria.pronta()) {
            val pd = Pendente(texto, tipo, entao)
            pendentes.add(pd)
            // Se em 40 s nenhuma voz ficar pronta, desiste da fala (mas segue com o que vinha depois).
            Rede.principal.postDelayed({ if (pendentes.remove(pd)) pd.entao?.invoke() }, 40_000)
            return
        }
        if (tipo == Tipo.INFO && tocandoMusica()) {
            Registro.anotar("jarvis (mudo, música tocando): $texto")
            entao?.invoke()
            return
        }
        ultimaFala = texto
        val id = "j" + System.nanoTime()
        if (entao != null) synchronized(depois) { depois[id] = entao }
        try {
            if (tipo != Tipo.RESPOSTA || tocandoMusica()) {
                val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build()
                am?.requestAudioFocus(f)
                foco = f
            }
        } catch (_: Exception) {
        }
        if (VozPropria.pronta()) {
            synchronized(depois) { depois.remove(id) }
            VozPropria.falar(texto) { terminou(null); entao?.invoke() }
            return
        }
        tts?.speak(texto, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }
}
