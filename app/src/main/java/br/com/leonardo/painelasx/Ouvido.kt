package br.com.leonardo.painelasx

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ouvido próprio do Jarvis: entende a fala do Leonardo DENTRO da central, sem internet.
 * A central não tem nenhum app de reconhecimento de voz, por isso o botão FALAR não funcionava.
 *
 *  - Whisper "base" multilíngue (sherpa-onnx, int8) em português + Silero VAD (percebe quando ele parou de falar).
 *  - Os arquivos (~160 MB) são baixados UMA vez da release "ouvido-2" (continua de onde parou se a internet cair).
 *  - Usa as bibliotecas que já vieram com a voz própria (pacote "voz-1").
 */
object Ouvido {
    private const val PACOTE = "ouvido-2"
    private val ARQUIVOS = listOf("ouvido-encoder.onnx", "ouvido-decoder.onnx", "ouvido-tokens.txt", "ouvido-vad.onnx")
    const val PEDIDO_MIC = 41

    @Volatile var estado = "desligado"
        private set
    @Volatile var ouvindo = false
        private set
    @Volatile private var rec: OfflineRecognizer? = null
    @Volatile private var vad: Vad? = null
    private var linha: HandlerThread? = null
    private var h: Handler? = null
    @Volatile private var appCtx: Context? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "ouvido")
    /** Os arquivos já estão todos na central. */
    fun baixado(ctx: Context) = File(dir(ctx), "ok").exists()
    fun temPermissao(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun preparar(ctx: Context) {
        appCtx = ctx.applicationContext
        if (linha != null) return
        val t = HandlerThread("ouvido").also { it.start() }
        linha = t
        h = Handler(t.looper).also { it.post { baixarECarregar(ctx.applicationContext) } }
    }

    /** Frase para o Leonardo quando ainda não dá para ouvir. */
    fun explicacao(): String = when {
        estado.startsWith("baixando") -> "Ainda estou baixando o meu ouvido, senhor. Está em ${estado.substringAfter("baixando ").trim()}. Fica pronto uma vez só."
        estado.startsWith("esperando") -> "Meu ouvido está esperando a internet para baixar, senhor."
        estado.startsWith("erro") -> "Meu ouvido deu um problema, senhor. Peça ao Claude para olhar o registro."
        else -> "Um instante, senhor, estou preparando o meu ouvido."
    }

    // ------------------------------------------------------------------ baixar e carregar

    private fun baixarECarregar(ctx: Context) {
        try {
            val vozOk = File(ctx.filesDir, "voz/ok").exists()
            if (!vozOk) {
                estado = "esperando a voz própria"
                h?.postDelayed({ baixarECarregar(ctx) }, 60_000)
                return
            }
            if (!baixado(ctx)) {
                val d = dir(ctx); d.mkdirs()
                if (d.usableSpace < 220L * 1024 * 1024 && ARQUIVOS.none { File(d, it).exists() }) {
                    estado = "erro: pouco espaço na central"
                    Registro.anotar("ERRO ouvido: pouco espaço (${d.usableSpace / 1048576} MB livres)")
                    return
                }
                Registro.anotar("ouvido: baixando (~160 MB, uma vez só)")
                for ((i, nome) in ARQUIVOS.withIndex()) {
                    val f = File(d, nome)
                    if (f.exists()) continue
                    baixar("https://github.com/${BuildConfig.REPO}/releases/download/$PACOTE/$nome", f) { pct ->
                        estado = "baixando ${(i * 100 + pct) / ARQUIVOS.size}%"
                    }
                }
                File(d, "ok").writeText("1")
                Registro.anotar("ouvido: arquivos baixados")
            }
            // Carrega já se houver memória sobrando; senão, carrega no primeiro toque em FALAR.
            if (memoriaLivreMb(ctx) > 550) carregar(ctx) else {
                estado = "pronto (carrega ao falar)"
                Registro.anotar("ouvido: pronto; carrego quando o senhor falar (memória curta)")
            }
        } catch (e: Throwable) {
            estado = "esperando a internet"
            Registro.anotar("ouvido: não baixou agora (${e.javaClass.simpleName}: ${e.message}); tento de novo em 5 min")
            h?.postDelayed({ baixarECarregar(ctx) }, 5 * 60_000)
        }
    }

    private fun memoriaLivreMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    /** Roda na linha "ouvido". */
    private fun carregar(ctx: Context) {
        if (rec != null) return
        estado = "carregando"
        val t0 = SystemClock.elapsedRealtime()
        try {
            val voz = File(ctx.filesDir, "voz")
            System.load(File(voz, "libonnxruntime.so").absolutePath)
            System.load(File(voz, "libsherpa-onnx-jni.so").absolutePath)
            val d = dir(ctx)
            vad = Vad(null, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(d, "ouvido-vad.onnx").absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.2f,
                    windowSize = 512,
                    maxSpeechDuration = 20f,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            ))
            rec = OfflineRecognizer(null, OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(d, "ouvido-encoder.onnx").absolutePath,
                        decoder = File(d, "ouvido-decoder.onnx").absolutePath,
                        language = "pt",
                        task = "transcribe",
                    ),
                    tokens = File(d, "ouvido-tokens.txt").absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper",
                ),
                decodingMethod = "greedy_search",
            ))
            estado = "pronto"
            Registro.anotar("ouvido: pronto (carregou em ${SystemClock.elapsedRealtime() - t0} ms)")
        } catch (e: Throwable) {
            rec = null; vad = null
            estado = "erro: ${e.javaClass.simpleName}: ${e.message}".take(200)
            Registro.anotar("ERRO ouvido ao carregar: ${e.javaClass.simpleName}: ${e.message}")
            // Arquivo estragado (a central perde energia de repente): apaga para baixar de novo.
            if (e !is UnsatisfiedLinkError && e !is OutOfMemoryError) {
                dir(ctx).listFiles()?.forEach { it.delete() }
                h?.postDelayed({ baixarECarregar(ctx) }, 30_000)
            }
        }
    }

    /** Baixa continuando de onde parou (a internet do celular por Bluetooth pode cair no meio). */
    private fun baixar(url: String, destino: File, progresso: (Int) -> Unit) {
        val tmp = File(destino.parentFile, destino.name + ".parte")
        var u = url
        var tentativas = 0
        while (true) {
            val ja = if (tmp.exists()) tmp.length() else 0L
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "painel-asx")
            if (ja > 0) c.setRequestProperty("Range", "bytes=$ja-")
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location"); c.disconnect()
                if (++tentativas > 6) throw Exception("redirecionamentos demais")
                continue
            }
            if (code == 416) { c.disconnect(); break } // já estava completo
            if (code != 200 && code != 206) { c.disconnect(); throw Exception("download deu $code") }
            val continuar = code == 206
            val resto = c.contentLengthLong
            val total = if (resto > 0) (if (continuar) ja + resto else resto) else -1L
            RandomAccessFile(tmp, "rw").use { raf ->
                if (continuar) raf.seek(ja) else raf.setLength(0)
                var feito = if (continuar) ja else 0L
                var ultimo = -1
                val buf = ByteArray(64 * 1024)
                c.inputStream.use { i ->
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); feito += n
                        if (total > 0) {
                            val pct = (feito * 100 / total).toInt()
                            if (pct != ultimo) { ultimo = pct; progresso(pct) }
                        }
                    }
                }
                raf.fd.sync()
                if (total > 0 && feito < total) throw Exception("download incompleto ($feito de $total)")
            }
            c.disconnect()
            break
        }
        if (!tmp.renameTo(destino)) throw Exception("não consegui guardar ${destino.name}")
    }

    // ------------------------------------------------------------------ ouvir

    /**
     * Ouve UMA frase: bipe, grava até o Leonardo parar de falar e transforma em texto.
     * [etapa] e [fim] rodam na linha principal. fim(texto, null) = entendeu; fim(null, frase) = não deu.
     */
    fun ouvir(ctx: Context, etapa: (String) -> Unit, fim: (String?, String?) -> Unit) {
        val app = ctx.applicationContext
        preparar(app)
        if (ouvindo) return
        if (!baixado(app)) { fim(null, explicacao()); return }
        ouvindo = true
        val hh = h ?: run { ouvindo = false; fim(null, explicacao()); return }
        hh.post {
            var texto: String? = null
            var problema: String? = null
            try {
                if (rec == null) {
                    Rede.naTela { etapa("PREPARANDO") }
                    carregar(app)
                }
                val r = rec; val v = vad
                if (r == null || v == null) {
                    problema = "Meu ouvido não carregou, senhor."
                } else {
                    val audio = gravar(app, v) { Rede.naTela { etapa(it) } }
                    if (audio == null) problema = "Não ouvi nada, senhor."
                    else {
                        Rede.naTela { etapa("PENSANDO") }
                        val t0 = SystemClock.elapsedRealtime()
                        val st = r.createStream()
                        try {
                            st.acceptWaveform(audio, 16000)
                            r.decode(st)
                            texto = limpar(r.getResult(st).text)
                        } finally { st.release() }
                        Registro.anotar("ouvido: \"${texto ?: ""}\" (${audio.size / 16} ms de fala, entendeu em ${SystemClock.elapsedRealtime() - t0} ms)")
                        if (texto == null) problema = "Não entendi, senhor. Pode repetir?"
                    }
                }
            } catch (e: SecurityException) {
                problema = "Preciso da permissão do microfone, senhor."
            } catch (e: Throwable) {
                problema = "Meu ouvido falhou agora, senhor."
                Registro.anotar("ERRO ouvido: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                ouvindo = false
                val t = texto; val p = problema
                Rede.naTela { fim(t, p) }
            }
        }
    }

    /** Devolve o áudio (16 kHz) da frase, ou null se ele não falou nada. Roda na linha "ouvido". */
    private fun gravar(ctx: Context, v: Vad, etapa: (String) -> Unit): FloatArray? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        try { am.requestAudioFocus(foco) } catch (_: Exception) { }
        try {
            bipe()
            val taxa = 16000
            val min = AudioRecord.getMinBufferSize(taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            var ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, taxa,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, taxa * 2))
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                ar = AudioRecord(MediaRecorder.AudioSource.MIC, taxa,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, taxa * 2))
            }
            if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); throw Exception("microfone não abriu") }
            val todo = ArrayList<FloatArray>()
            var comecou = false
            var maior = 0f
            try {
                ar.startRecording()
                if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Exception("microfone ocupado")
                v.reset()
                etapa("OUVINDO")
                val inicio = SystemClock.elapsedRealtime()
                var ultimaFala = inicio
                val pedaco = ShortArray(512)
                while (true) {
                    val n = ar.read(pedaco, 0, pedaco.size)
                    if (n < 0) throw Exception("microfone falhou ($n)")
                    if (n == 0) continue
                    val f = FloatArray(n) { pedaco[it] / 32768f }
                    for (x in f) { val a = if (x < 0) -x else x; if (a > maior) maior = a }
                    todo.add(f)
                    if (n == 512) v.acceptWaveform(f)
                    val agora = SystemClock.elapsedRealtime()
                    if (v.isSpeechDetected()) {
                        if (!comecou) { comecou = true; etapa("OUVINDO ●") }
                        ultimaFala = agora
                    }
                    if (comecou && agora - ultimaFala > 900) break      // parou de falar
                    if (!comecou && agora - inicio > 6_000) break        // não falou nada
                    if (agora - inicio > 12_000) break                   // frase longa demais
                }
            } finally {
                try { ar.stop() } catch (_: Exception) { }
                ar.release()
                v.clear()
            }
            if (maior < 0.002f) Registro.anotar("ouvido: o microfone parece mudo (nível ${"%.4f".format(maior)})")
            if (!comecou) return null
            val total = todo.sumOf { it.size }
            val saida = FloatArray(total)
            var p = 0
            for (f in todo) { System.arraycopy(f, 0, saida, p, f.size); p += f.size }
            return saida
        } finally {
            try { am.abandonAudioFocusRequest(foco) } catch (_: Exception) { }
        }
    }

    private fun bipe() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Thread.sleep(220)
            tg.release()
        } catch (_: Exception) { }
    }

    /** O Whisper às vezes "inventa" frases de legenda quando só ouve ruído. */
    private fun limpar(bruto: String): String? {
        val t = bruto.trim().trim('.', ' ', '!', '?', ',', '…')
        if (t.length < 2) return null
        val n = t.lowercase()
        val inventadas = listOf("amara.org", "legendas pela", "legenda por", "inscreva-se", "obrigado por assistir",
            "obrigada por assistir", "se inscreva", "[música]", "(música)")
        if (inventadas.any { n.contains(it) } || n == "música" || n == "musica") return null
        return t
    }

    /** Solta a memória (o ouvido volta a carregar no próximo FALAR). */
    fun liberar() {
        h?.post {
            if (ouvindo) return@post
            rec?.release(); rec = null
            vad?.release(); vad = null
            if (baixado(appCtx ?: return@post)) estado = "pronto (carrega ao falar)"
            Registro.anotar("ouvido: soltei a memória")
        }
    }
}
