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
import java.util.zip.ZipInputStream

/**
 * Voz própria do Jarvis: roda dentro do app, sem internet (motor sherpa-onnx + voz masculina Piper pt-BR).
 * O pacote (~80 MB) é baixado UMA vez da release "voz-1" do GitHub e fica guardado na central.
 * Enquanto não estiver pronta, o app usa a voz do Android (Google).
 */
object VozPropria {
    private const val PACOTE = "voz-1"
    private fun p(ctx: Context?) = ctx?.getSharedPreferences("config", Context.MODE_PRIVATE)
    /** < 1 deixa a voz mais grave (toca a voz gerada um pouco mais devagar). */
    fun grave(ctx: Context?) = p(ctx)?.getFloat("voz_grave", 0.86f) ?: 0.86f
    /** Velocidade da fala (1 = normal). */
    fun velocidade(ctx: Context?) = p(ctx)?.getFloat("voz_vel", 0.92f) ?: 0.92f

    /** "voz mais grave", "fala mais rápido"... */
    fun ajustar(ctx: Context, dGrave: Float, dVel: Float, padrao: Boolean = false): String {
        val e = p(ctx)!!.edit()
        if (padrao) e.remove("voz_grave").remove("voz_vel")
        else e.putFloat("voz_grave", (grave(ctx) + dGrave).coerceIn(0.72f, 1.05f))
            .putFloat("voz_vel", (velocidade(ctx) + dVel).coerceIn(0.7f, 1.35f))
        e.commit()
        gravarSaudacoes(ctx)
        return if (padrao) "Voz de volta ao padrão, senhor." else "Assim está melhor, senhor?"
    }

    // ------------------------------------------------------------------ saudação já gravada (toca em 1 s na partida)

    private fun saudacoes() = mapOf(
        "manha" to "Bom dia, Leonardo. Tudo bem? Sistemas online, às suas ordens.",
        "tarde" to "Boa tarde, Leonardo. Tudo bem? Sistemas online, às suas ordens.",
        "noite" to "Boa noite, Leonardo. Tudo bem? Sistemas online, às suas ordens."
    )

    fun gravarSaudacoes(ctx: Context) {
        val hh = h ?: return
        hh.post {
            val t = tts ?: return@post
            for ((k, texto) in saudacoes()) {
                try {
                    val a = t.generate(texto, 0, velocidade(ctx) / grave(ctx))
                    val taxa = (a.sampleRate * grave(ctx)).toInt()
                    // Grava num arquivo temporário, força salvar no disco (a central perde energia de repente) e renomeia.
                    val tmp = File(ctx.filesDir, "voz/saudacao_$k.tmp")
                    val fos = FileOutputStream(tmp)
                    java.io.DataOutputStream(java.io.BufferedOutputStream(fos)).use { o ->
                        o.writeInt(taxa)
                        for (x in a.samples) o.writeShort((x.coerceIn(-1f, 1f) * 32767).toInt())
                        o.flush()
                        fos.fd.sync()
                    }
                    tmp.renameTo(File(ctx.filesDir, "voz/saudacao_$k.pcm"))
                } catch (e: Throwable) {
                    Registro.anotar("voz própria: não gravou a saudação ($k): ${e.message}")
                }
            }
            Registro.anotar("voz própria: saudações gravadas")
        }
    }

    /** Toca a saudação já gravada (não precisa carregar a voz). false = ainda não existe. */
    fun tocarSaudacao(ctx: Context, periodo: String, entao: () -> Unit): Boolean {
        if (appCtx == null) appCtx = ctx.applicationContext   // a saudação vem antes do preparar(): usa o volume escolhido
        val f = File(ctx.filesDir, "voz/saudacao_$periodo.pcm")
        if (!f.exists() || f.length() < 2000 || !ligada(ctx)) return false
        Thread({
            try {
                // Lê o arquivo de uma vez (antes eram ~100 mil leituras de 2 bytes, com o app ainda "frio" na partida).
                val b = java.nio.ByteBuffer.wrap(f.readBytes())   // big-endian, como o DataOutputStream gravou
                val taxa = b.int
                val pcm = ShortArray(b.remaining() / 2)
                b.asShortBuffer().get(pcm)
                tocarPcm(pcm, taxa)
            } catch (e: Throwable) {
                Registro.anotar("ERRO saudação: ${e.message}")
            } finally {
                Rede.naTela(entao)
            }
        }, "saudacao").start()
        return true
    }

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

    private val travaPacote = Any()

    /**
     * Baixa e instala o pacote "voz-1" (bibliotecas do motor + voz), se ainda não estiver na central.
     * Também é usado pelo ouvido (ele usa as mesmas bibliotecas), mesmo com a voz própria desligada.
     * A trava impede que a voz e o ouvido baixem o pacote ao mesmo tempo. O download continua de onde parou.
     */
    fun baixarPacote(ctx: Context) = synchronized(travaPacote) {
        val dir = File(ctx.filesDir, "voz")
        val ok = File(dir, "ok")
        if (ok.exists()) return@synchronized
        estado = "baixando"
        try {
            Registro.anotar("voz própria: baixando o pacote (${abi()})")
            dir.deleteRecursively(); dir.mkdirs()
            val zip = File(ctx.filesDir, "voz.zip")
            Arquivo.baixar("https://github.com/${BuildConfig.REPO}/releases/download/$PACOTE/voz-${abi()}.zip", zip)
            estado = "instalando"
            try {
                ZipInputStream(zip.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(dir, e.name)
                        if (!f.canonicalPath.startsWith(dir.canonicalPath)) throw Exception("zip estranho")
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            // fsync: a central perde energia de repente; sem isso o "ok" podia ficar com um modelo cortado.
                            FileOutputStream(f).use { z.copyTo(it); it.fd.sync() }
                        }
                        e = z.nextEntry
                    }
                }
            } finally {
                zip.delete()   // zip estragado: na próxima tentativa baixa de novo, do zero
            }
            Arquivo.gravarSeguro(ok, "1")
            Registro.anotar("voz própria: pacote instalado")
        } finally {
            estado = when {
                ok.exists() && ligada(ctx) -> "carregando"
                ok.exists() -> "desligada (pacote baixado)"
                ligada(ctx) -> "esperando a internet"
                else -> "desligada"
            }
        }
    }

    private fun carregar(ctx: Context) {
        if (!ligada(ctx)) { linha?.quitSafely(); linha = null; h = null; return }  // desligada enquanto esperava
        try {
            val dir = File(ctx.filesDir, "voz")
            baixarPacote(ctx)
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
            Rede.naTela { Voz.esvaziarPendentes() }
            if (!File(dir, "saudacao_noite.pcm").exists()) gravarSaudacoes(ctx)
        } catch (e: Throwable) {
            estado = "erro: ${e.javaClass.simpleName}: ${e.message}".take(200)
            Registro.anotar("ERRO voz própria: ${e.javaClass.simpleName}: ${e.message}")
            if (e is UnsatisfiedLinkError) File(ctx.filesDir, "voz/ok").delete()
            tts = null
            if (e !is UnsatisfiedLinkError && !File(ctx.filesDir, "voz/ok").exists() && ligada(ctx)) {
                // Não baixou (internet fraca): tenta de novo em 5 min. Antes desistia até o app reiniciar.
                estado = "esperando a internet"
                h?.postDelayed({ carregar(ctx) }, 5 * 60_000)
            } else {
                linha?.quitSafely(); linha = null; h = null
            }
        }
    }

    /** Fala com a voz própria (fila, uma frase de cada vez). [entao] roda no fim, na linha principal. */
    fun falar(texto: String, entao: (() -> Unit)?) {
        val hh = h ?: run { entao?.let { Rede.naTela(it) }; return }
        hh.post {
            try {
                val t = tts ?: return@post
                val g = grave(appCtx)
                val audio = t.generate(texto.replace(Regex("[*_#`]"), ""), 0, velocidade(appCtx) / g)
                tocar(audio.samples, (audio.sampleRate * g).toInt())
            } catch (e: Throwable) {
                Registro.anotar("ERRO voz própria ao falar: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                entao?.let { Rede.naTela(it) }
            }
        }
    }

    private fun tocar(amostras: FloatArray, taxa: Int) {
        if (amostras.isEmpty()) return
        tocarPcm(ShortArray(amostras.size) { i -> (amostras[i].coerceIn(-1f, 1f) * 32767).toInt().toShort() }, taxa)
    }

    /** Volume da voz do Jarvis (1 = normal, até 3). "voz mais alta" / "voz mais baixa". */
    fun volume(ctx: Context?) = p(ctx)?.getFloat("voz_volume", 1.6f) ?: 1.6f
    fun mudarVolume(ctx: Context, delta: Float): String {
        val v = (volume(ctx) + delta).coerceIn(0.6f, 3f)
        p(ctx)!!.edit().putFloat("voz_volume", v).apply()
        return if (delta > 0) "Assim está mais alto, senhor?" else "Assim está mais baixo, senhor?"
    }

    /**
     * Deixa a voz mais alta: o Piper gera o áudio bem abaixo do máximo. Sobe até [volume] vezes, sem nunca passar
     * do pico (não distorce). Vale também para a saudação já gravada.
     */
    private fun reforcar(pcm: ShortArray): ShortArray {
        var pico = 1
        for (x in pcm) { val a = if (x < 0) -x.toInt() else x.toInt(); if (a > pico) pico = a }
        val limite = 0.95f * 32767f / pico
        val g = minOf(volume(appCtx), limite)
        if (g in 0.99f..1.01f) return pcm   // abaixo de 1 abaixa ("voz mais baixa"), acima reforça
        return ShortArray(pcm.size) { i -> (pcm[i] * g).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun tocarPcm(original: ShortArray, taxa: Int) {
        if (original.isEmpty()) return
        val pcm = reforcar(original)
        // Pede para os outros sons abaixarem enquanto o Jarvis fala (saudação inclusive) e devolve no fim.
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val foco = try {
            android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
                .also { am?.requestAudioFocus(it) }
        } catch (_: Exception) { null }
        try { tocarComTrilha(pcm, taxa) } finally { try { if (foco != null) am?.abandonAudioFocusRequest(foco) } catch (_: Exception) { } }
    }

    private fun tocarComTrilha(pcm: ShortArray, taxa: Int) {
        // Pelo canal da MÚSICA: segue o botão de volume que o Leonardo gira (o canal de navegação da NWD fica baixo).
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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
