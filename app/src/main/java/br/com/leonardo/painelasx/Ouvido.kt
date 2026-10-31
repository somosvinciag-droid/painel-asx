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
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    /**
     * Tamanho certo de cada arquivo (o "ok" guarda "nome=tamanho"). Vazio = "ok" antigo, sem tamanhos.
     * O motor (sherpa-onnx) FECHA o app inteiro se o modelo estiver cortado, por isso confere antes de carregar.
     */
    private fun tamanhosCertos(ctx: Context): Map<String, Long> = try {
        File(dir(ctx), "ok").readLines().mapNotNull { l ->
            val p = l.split("="); if (p.size == 2) p[1].trim().toLongOrNull()?.let { p[0].trim() to it } else null
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    /** Apaga os arquivos que estão faltando ou com tamanho errado. true = estava tudo certo. */
    private fun conferirArquivos(ctx: Context): Boolean {
        val certos = tamanhosCertos(ctx)
        if (certos.isEmpty()) return ARQUIVOS.all { File(dir(ctx), it).exists() }
        var tudoCerto = true
        for (nome in ARQUIVOS) {
            val f = File(dir(ctx), nome)
            if (!f.exists() || f.length() != certos[nome]) {
                Registro.anotar("ERRO ouvido: $nome estragado (${f.length()} de ${certos[nome]} bytes); vou baixar de novo")
                f.delete(); tudoCerto = false
            }
        }
        if (!tudoCerto) File(dir(ctx), "ok").delete()
        return tudoCerto
    }

    /**
     * Quantas vezes o app fechou no meio de carregar o ouvido (nesta versão do app).
     * O marcador "carregando" fica no disco durante a carga: se ainda existe quando o app abre, a carga derrubou o app.
     */
    private fun quebras(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.getInt("ouvido_quebras_versao", 0) != BuildConfig.VERSION_CODE) return 0
        return p.getInt("ouvido_quebras", 0)
    }
    private fun mudarQuebras(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("ouvido_quebras", n).putInt("ouvido_quebras_versao", BuildConfig.VERSION_CODE).commit()
    }
    private fun marcador(ctx: Context) = File(dir(ctx), "carregando")
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
        estado.startsWith("baixando a voz") -> "Estou baixando as bibliotecas da voz, que o meu ouvido também usa, senhor. Fica pronto uma vez só."
        estado.startsWith("baixando") -> "Ainda estou baixando o meu ouvido, senhor. Está em ${estado.substringAfter("baixando ").trim()}. Fica pronto uma vez só."
        estado.startsWith("esperando") -> "Meu ouvido está esperando a internet para baixar, senhor."
        estado.startsWith("erro") -> "Meu ouvido deu um problema, senhor. Peça ao Claude para olhar o registro."
        else -> "Um instante, senhor, estou preparando o meu ouvido."
    }

    // ------------------------------------------------------------------ baixar e carregar

    private var conferiuQuebra = false

    private fun baixarECarregar(ctx: Context) {
        try {
            if (!conferiuQuebra) {
                conferiuQuebra = true
                if (marcador(ctx).exists()) {
                    marcador(ctx).delete()
                    val n = quebras(ctx) + 1
                    mudarQuebras(ctx, n)
                    Registro.anotar("ERRO ouvido: o app fechou enquanto eu carregava o ouvido ($n vez(es)); confiro os arquivos")
                    if (!conferirArquivos(ctx)) mudarQuebras(ctx, 0)  // arquivo cortado: baixa de novo e tenta outra vez
                }
            }
            if (!File(ctx.filesDir, "voz/ok").exists()) {
                // O ouvido usa as bibliotecas do pacote da voz própria ("voz-1"). Com a voz do Google escolhida,
                // a voz própria não baixa nada e o ouvido ficava para sempre esperando: o próprio ouvido baixa
                // o pacote (uma vez só). Se a voz própria já estiver baixando, só espera ela terminar (sem baixar 2 vezes).
                estado = "baixando a voz (o ouvido usa as bibliotecas dela)"
                VozPropria.baixarPacote(ctx)
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
                    if (f.exists()) continue   // só existe depois de baixado inteiro (conferido pelo tamanho)
                    Arquivo.baixar("https://github.com/${BuildConfig.REPO}/releases/download/$PACOTE/$nome", f) { pct ->
                        estado = "baixando ${(i * 100 + pct) / ARQUIVOS.size}%"
                    }
                }
                // "ok" com o tamanho de cada arquivo, gravado com segurança (a central perde energia de repente).
                Arquivo.gravarSeguro(File(d, "ok"), ARQUIVOS.joinToString("\n") { "$it=${File(d, it).length()}" } + "\n")
                Registro.anotar("ouvido: arquivos baixados")
            }
            when {
                quebras(ctx) >= 2 -> {
                    estado = "erro: o ouvido fechou o app 2 vezes ao carregar"
                    Registro.anotar("ERRO ouvido: desligado nesta versão (fechou o app 2 vezes ao carregar)")
                }
                // Já fechou o app uma vez: não carrega sozinho (só quando ele tocar em FALAR).
                quebras(ctx) == 1 -> estado = "pronto (carrega ao falar)"
                // Pré-carrega (se houver memória sobrando) só depois que a partida assentar; senão, no primeiro FALAR.
                memoriaLivreMb(ctx) > 550 -> preCarregarDepoisDaPartida(ctx)
                else -> {
                    estado = "pronto (carrega ao falar)"
                    Registro.anotar("ouvido: pronto; carrego quando o senhor falar (memória curta)")
                }
            }
        } catch (e: Throwable) {
            estado = "esperando a internet"
            Registro.anotar("ouvido: não baixou agora (${e.javaClass.simpleName}: ${e.message}); tento de novo em 5 min")
            h?.postDelayed({ baixarECarregar(ctx) }, 5 * 60_000)
        }
    }

    /**
     * Pré-carga do ouvido (pesada: ~2 s de processador e muita memória). Espera a partida assentar: a saudação
     * terminar e no mínimo 20 s desde que o app começou, o que vier por último (no máximo 60 s esperando a saudação).
     * Se ele tocar em FALAR antes, o ouvir() carrega na hora; quando esta pré-carga rodar, já está carregado.
     * Roda na linha "ouvido" sem prender a linha (só reagenda).
     */
    private fun preCarregarDepoisDaPartida(ctx: Context) {
        val desdeInicio = SystemClock.elapsedRealtime() - App.inicio
        val falta = when {
            desdeInicio < 20_000 -> 20_000 - desdeInicio
            Jarvis.saudando && desdeInicio < 60_000 -> 1_000L
            else -> 0L
        }
        if (falta > 0) {
            estado = "pronto (carrega depois da partida)"
            h?.postDelayed({ preCarregarDepoisDaPartida(ctx) }, falta)
            return
        }
        if (rec != null) { estado = "pronto"; return }
        if (ouvindo) return   // o FALAR está carregando/ouvindo agora
        if (memoriaLivreMb(ctx) > 550) {
            Registro.anotar("ouvido: pré-carga ${desdeInicio / 1000} s depois de o app começar")
            carregar(ctx)
        } else {
            estado = "pronto (carrega ao falar)"
            Registro.anotar("ouvido: pronto; carrego quando o senhor falar (memória curta)")
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
        if (quebras(ctx) >= 2) { estado = "erro: o ouvido fechou o app 2 vezes ao carregar"; return }
        estado = "carregando"
        val t0 = SystemClock.elapsedRealtime()
        try {
            // Arquivo cortado faz o motor fechar o app (não dá para pegar o erro): confere o tamanho antes.
            if (!conferirArquivos(ctx)) throw IllegalStateException("arquivos do ouvido incompletos")
            val voz = File(ctx.filesDir, "voz")
            System.load(File(voz, "libonnxruntime.so").absolutePath)
            System.load(File(voz, "libsherpa-onnx-jni.so").absolutePath)
            val d = dir(ctx)
            Arquivo.gravarSeguro(marcador(ctx), SystemClock.elapsedRealtime().toString())
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
            marcador(ctx).delete()
            if (quebras(ctx) != 0) mudarQuebras(ctx, 0)
            estado = "pronto"
            Registro.anotar("ouvido: pronto (carregou em ${SystemClock.elapsedRealtime() - t0} ms)")
        } catch (e: Throwable) {
            marcador(ctx).delete()
            try { rec?.release() } catch (_: Throwable) { }
            try { vad?.release() } catch (_: Throwable) { }
            rec = null; vad = null
            estado = "erro: ${e.javaClass.simpleName}: ${e.message}".take(200)
            Registro.anotar("ERRO ouvido ao carregar: ${e.javaClass.simpleName}: ${e.message}")
            // Arquivo faltando/cortado: baixa de novo só o que estragou (no máximo 2 vezes por versão,
            // para não gastar a internet do celular num laço).
            if (!baixado(ctx)) {
                val p = prefs(ctx)
                val n = if (p.getInt("ouvido_rebaixou_versao", 0) == BuildConfig.VERSION_CODE) p.getInt("ouvido_rebaixou", 0) else 0
                if (n < 2) {
                    p.edit().putInt("ouvido_rebaixou", n + 1).putInt("ouvido_rebaixou_versao", BuildConfig.VERSION_CODE).commit()
                    h?.postDelayed({ baixarECarregar(ctx) }, 30_000)
                }
            }
        }
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
                    problema = if (estado.startsWith("erro")) explicacao() else "Meu ouvido não carregou, senhor."
                } else {
                    val audio = gravar(app, v) { Rede.naTela { etapa(it) } }
                    if (audio == null) problema = if (pareciaMudo) "Não ouvi nada, senhor. O microfone parece mudo ou ocupado por outro app." else "Não ouvi nada, senhor."
                    else {
                        Rede.naTela { etapa("PENSANDO") }
                        val t0 = SystemClock.elapsedRealtime()
                        val st = r.createStream()
                        try {
                            st.acceptWaveform(audio, 16000)
                            r.decode(st)
                            texto = limpar(r.getResult(st).text)
                        } finally { st.release() }
                        val ms = SystemClock.elapsedRealtime() - t0
                        val bruto = texto
                        // Corrige os erros comuns do Whisper ("oto para" -> "rota para", "cachambu" -> Caxambu, "limite cem" -> "limite 100").
                        if (bruto != null) texto = try {
                            Corretor.corrigir(bruto, Lugares.todos(app).keys().asSequence().toList())
                        } catch (e: Throwable) { bruto }
                        Registro.anotar("ouvido: \"${bruto ?: ""}\"" + (if (texto != bruto) " → \"$texto\"" else "") +
                            " (${audio.size / 16} ms de áudio, entendeu em $ms ms)")
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

    @Volatile private var pareciaMudo = false

    /** Devolve o áudio (16 kHz) da frase, ou null se ele não falou nada. Roda na linha "ouvido". */
    private fun gravar(ctx: Context, v: Vad, etapa: (String) -> Unit): FloatArray? {
        pareciaMudo = false
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
            var amostras = 0          // quantas amostras já gravou
            var inicioFala = -1       // amostra em que a VAD percebeu a fala
            try {
                ar.startRecording()
                if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw Exception("microfone ocupado")
                v.reset()
                etapa("OUVINDO")
                val inicio = SystemClock.elapsedRealtime()
                var ultimaFala = inicio
                val pedaco = ShortArray(512)
                // A VAD (Silero) trabalha com janelas de exatamente 512 amostras: junta as leituras até completar.
                val janela = FloatArray(512)
                var naJanela = 0
                while (true) {
                    val n = ar.read(pedaco, 0, pedaco.size)
                    if (n < 0) throw Exception("microfone falhou ($n)")
                    if (n > 0) {
                        val f = FloatArray(n) { pedaco[it] / 32768f }
                        for (x in f) { val a = if (x < 0) -x else x; if (a > maior) maior = a }
                        todo.add(f)
                        amostras += n
                        var k = 0
                        while (k < n) {
                            val m = minOf(n - k, 512 - naJanela)
                            System.arraycopy(f, k, janela, naJanela, m)
                            naJanela += m; k += m
                            if (naJanela == 512) { v.acceptWaveform(janela.copyOf()); naJanela = 0 }
                        }
                    } else Thread.sleep(5)   // leitura vazia: não fica rodando sem parar
                    val agora = SystemClock.elapsedRealtime()
                    if (v.isSpeechDetected()) {
                        if (!comecou) { comecou = true; inicioFala = amostras; etapa("OUVINDO ●") }
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
            pareciaMudo = maior < 0.002f
            if (pareciaMudo) Registro.anotar("ouvido: o microfone parece mudo (nível ${"%.4f".format(maior)})")
            if (!comecou) return null
            val total = todo.sumOf { it.size }
            val tudo = FloatArray(total)
            var p = 0
            for (f in todo) { System.arraycopy(f, 0, tudo, p, f.size); p += f.size }
            // Corta o silêncio do começo (do bipe até ele começar a falar), deixando 0,9 s antes da fala:
            // o Whisper processa cada segundo de áudio, e na central isso custa ~0,2 s por segundo de silêncio.
            // (Testado: o acerto fica igual.)
            val inicio = (inicioFala - 14_400).coerceIn(0, total)
            return if (inicio > 0) tudo.copyOfRange(inicio, total) else tudo
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
