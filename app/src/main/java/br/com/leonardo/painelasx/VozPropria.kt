package br.com.leonardo.painelasx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Voz própria do Jarvis: roda dentro do app, sem internet (motor sherpa-onnx + voz masculina Piper pt-BR).
 * O pacote (~80 MB) é baixado UMA vez da release "voz-1" do GitHub e fica guardado na central.
 * Enquanto não estiver pronta, o app usa a voz do Android (Google).
 */
object VozPropria {
    private const val PACOTE = "voz-1"
    /** < 1 deixa a voz mais grave (toca um pouco mais devagar). */
    private const val GRAVE = 0.90f

    @Volatile var estado = "desligada"
        private set
    @Volatile private var tts: OfflineTts? = null
    private var linha: HandlerThread? = null
    private var h: Handler? = null

    fun pronta() = tts != null && ligada(appCtx)

    @Volatile private var appCtx: Context? = null

    fun ligada(ctx: Context?) = ctx?.getSharedPreferences("config", Context.MODE_PRIVATE)?.getBoolean("voz_propria", true) ?: true
    fun mudarLigada(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putBoolean("voz_propria", v).apply()
        if (v) preparar(ctx)
    }

    private fun abi(): String {
        val a = Build.SUPPORTED_ABIS.firstOrNull() ?: "armeabi-v7a"
        return if (a.startsWith("arm64")) "arm64-v8a" else "armeabi-v7a"
    }

    fun preparar(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!ligada(ctx) || tts != null || linha != null) return
        val t = HandlerThread("voz-propria").also { it.start() }
        linha = t
        h = Handler(t.looper).also { it.post { carregar(ctx.applicationContext) } }
    }

    private fun carregar(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "voz")
            val ok = File(dir, "ok")
            if (!ok.exists()) {
                estado = "baixando"
                Registro.anotar("voz própria: baixando o pacote (${abi()})")
                dir.deleteRecursively(); dir.mkdirs()
                val zip = File(ctx.filesDir, "voz.zip")
                baixar("https://github.com/${BuildConfig.REPO}/releases/download/$PACOTE/voz-${abi()}.zip", zip)
                estado = "instalando"
                ZipInputStream(zip.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(dir, e.name)
                        if (!f.canonicalPath.startsWith(dir.canonicalPath)) throw Exception("zip estranho")
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { z.copyTo(it) }
                        }
                        e = z.nextEntry
                    }
                }
                zip.delete()
                ok.writeText("1")
                Registro.anotar("voz própria: pacote instalado")
            }
            estado = "carregando"
            System.load(File(dir, "libonnxruntime.so").absolutePath)
            System.load(File(dir, "libsherpa-onnx-jni.so").absolutePath)
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, "model.onnx").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            tts = OfflineTts(null, cfg)
            estado = "pronta"
            Registro.anotar("voz própria: pronta (${tts?.sampleRate()} Hz)")
        } catch (e: Throwable) {
            estado = "erro: ${e.javaClass.simpleName}: ${e.message}".take(200)
            Registro.anotar("ERRO voz própria: ${e.javaClass.simpleName}: ${e.message}")
            if (e is UnsatisfiedLinkError) File(ctx.filesDir, "voz/ok").delete()
            tts = null
            linha?.quitSafely(); linha = null; h = null
        }
    }

    private fun baixar(url: String, destino: File) {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "painel-asx")
            val code = c.responseCode
            if (code in 300..399) { u = c.getHeaderField("Location"); c.disconnect(); return@repeat }
            if (code != 200) throw Exception("download deu $code")
            c.inputStream.use { i -> FileOutputStream(destino).use { i.copyTo(it, 64 * 1024) } }
            c.disconnect()
            return
        }
        throw Exception("redirecionamentos demais")
    }

    /** Fala com a voz própria (fila, uma frase de cada vez). [entao] roda no fim, na linha principal. */
    fun falar(texto: String, entao: (() -> Unit)?) {
        val hh = h ?: run { entao?.let { Rede.naTela(it) }; return }
        hh.post {
            try {
                val t = tts ?: return@post
                val audio = t.generate(texto.replace(Regex("[*_#`]"), ""), 0, 1.0f / GRAVE * 0.98f)
                tocar(audio.samples, (audio.sampleRate * GRAVE).toInt())
            } catch (e: Throwable) {
                Registro.anotar("ERRO voz própria ao falar: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                entao?.let { Rede.naTela(it) }
            }
        }
    }

    private fun tocar(amostras: FloatArray, taxa: Int) {
        if (amostras.isEmpty()) return
        val pcm = ShortArray(amostras.size) { i -> (amostras[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(taxa)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            at.write(pcm, 0, pcm.size)
            at.play()
            Thread.sleep(pcm.size * 1000L / taxa + 250)
        } finally {
            try { at.stop() } catch (_: Exception) { }
            at.release()
        }
    }
}
