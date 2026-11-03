package br.com.leonardo.painelasx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A tela do Android Auto (decisão do Leonardo, 01/11): o vídeo que o celular manda pelo cabo é decodificado
 * (H.264 pelo MediaCodec, direto na superfície, sem passar pelo processador) e os toques voltam para o celular.
 * Abre sozinha quando o Android Auto liga. O "sair" do próprio Android Auto fecha (fica a tela de fábrica por baixo).
 */
class TelaAndroidAuto : Activity(), TextureView.SurfaceTextureListener {

    companion object {
        @Volatile private var aberta: TelaAndroidAuto? = null

        fun abrir(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, TelaAndroidAuto::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (e: Exception) {
                Registro.anotar("aa: não abri a tela do Android Auto (${e.javaClass.simpleName})")
            }
        }

        /** O Android Auto desligou ou o motorista saiu dele: fecha a tela (a TelaCarro fica por baixo). */
        fun fechar() {
            Rede.naTela { aberta?.let { if (!it.isFinishing) it.finish() } }
        }

        val estaAberta get() = aberta != null
    }

    private var superficie: TextureView? = null
    private var saida: Surface? = null
    private var decodificador: Decodificador? = null
    private var botao3d: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val raiz = FrameLayout(this).apply {
            // "Céu" que aparece atrás do mapa inclinado (visão em primeira pessoa).
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#05080D"), Color.parseColor("#0D1B2A"), Color.parseColor("#24364A")))
        }
        // TextureView (e não SurfaceView): só ela pode ser inclinada em 3D como uma vista comum.
        val tv = TextureView(this)
        tv.surfaceTextureListener = this
        tv.setOnTouchListener { v, e -> tocar(v, e) }
        raiz.addView(tv, FrameLayout.LayoutParams(-1, -1))
        // Botão pequeno (pedido do Leonardo, 03/11): liga/desliga a visão inclinada ("primeira pessoa").
        val d = resources.displayMetrics.density
        val b3 = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(0x88000000.toInt()); setStroke((1 * d).toInt(), 0x55FFFFFF) }
            setOnClickListener { mudarVisao() }
        }
        raiz.addView(b3, FrameLayout.LayoutParams((44 * d).toInt(), (30 * d).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (6 * d).toInt(); rightMargin = (6 * d).toInt()
        })
        botao3d = b3
        // Botão discreto para voltar à tela do Painel (só se a F3 existir; o Leonardo a apagou em 01/11).
        if (Painel.F3_LIGADA) {
            val painel = TextView(this).apply {
                text = "PAINEL"
                textSize = 13f
                setTextColor(Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                setPadding(22, 10, 22, 10)
                setOnClickListener { voltarAoPainel() }
            }
            raiz.addView(painel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        superficie = tv
        setContentView(raiz)
        aberta = this
        tv.post { aplicarVisao(animar = false) }
        Registro.anotar("aa: tela do Android Auto aberta" + if (primeiraPessoa()) " (visão inclinada)" else "")
    }

    // ------------------------------------------------------------------ visão inclinada ("primeira pessoa")

    private fun prefs() = getSharedPreferences("painel", Context.MODE_PRIVATE)
    private fun primeiraPessoa() = prefs().getBoolean("aa_primeira_pessoa", false)

    private fun mudarVisao() {
        val nova = !primeiraPessoa()
        prefs().edit().putBoolean("aa_primeira_pessoa", nova).apply()
        Registro.anotar("aa: visão " + if (nova) "inclinada (primeira pessoa)" else "normal")
        aplicarVisao(animar = true)
    }

    /**
     * Inclina a imagem do Android Auto como um chão visto da altura do carro: gira em X com o eixo no pé da tela e
     * aumenta um pouco (a parte de baixo passa das bordas, a de cima some no horizonte, com o "céu" atrás).
     * Os toques continuam certos: o Android desfaz a inclinação antes de entregar o toque à vista ([tocar]).
     */
    private fun aplicarVisao(animar: Boolean) {
        val v = superficie ?: return
        val inclinada = primeiraPessoa()
        botao3d?.text = if (inclinada) "2D" else "3D"
        if (v.width == 0) return
        val d = resources.displayMetrics.density
        v.cameraDistance = 1400 * d
        v.pivotX = v.width / 2f
        v.pivotY = v.height.toFloat()
        val rot = if (inclinada) 52f else 0f
        val esc = if (inclinada) 1.45f else 1f
        if (animar) v.animate().rotationX(rot).scaleX(esc).scaleY(esc).setDuration(450).start()
        else { v.rotationX = rot; v.scaleX = esc; v.scaleY = esc }
    }

    /**
     * Já aberta e o Android Auto ligou de novo (conversa nova no mesmo cabo, sem a tela fechar): a superfície continua
     * a mesma, então o surfaceCreated não volta. Liga o decodificador à conversa nova (configuração + imagem inteira).
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        decodificador?.let { AndroidAuto.ligarVideo(it) }
    }

    private fun voltarAoPainel() {
        try { startActivity(Intent(this, TelaCarro::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        finish()
    }

    override fun onDestroy() {
        if (aberta === this) aberta = null
        soltarDecodificador()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ superfície

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        soltarDecodificador()
        val (vw, vl) = AndroidAuto.tamanhoVideo()
        st.setDefaultBufferSize(vw, vl)
        val sup = Surface(st)
        val d = try { Decodificador(sup, vw, vl) } catch (e: Throwable) {
            Registro.anotar("ERRO aa: não abri o decodificador de vídeo: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            null
        }
        if (d == null) { sup.release(); return }
        saida = sup
        decodificador = d
        AndroidAuto.ligarVideo(d)
        aplicarVisao(animar = false)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { aplicarVisao(animar = false) }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        soltarDecodificador()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun soltarDecodificador() {
        val d = decodificador
        decodificador = null
        if (d != null) { AndroidAuto.desligarVideo(d); d.soltar() }
        saida?.let { try { it.release() } catch (_: Throwable) { } }
        saida = null
    }

    // ------------------------------------------------------------------ toque

    /** Toque na vista do vídeo: as coordenadas já vêm "desinclinadas" pelo Android (locais da vista). */
    private fun tocar(v: View, e: MotionEvent): Boolean {
        if (v.width <= 0 || v.height <= 0) return false
        val (w, l) = AndroidAuto.tamanhoVideo()
        val acao = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_UP -> 1
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_CANCEL -> 3
            MotionEvent.ACTION_POINTER_DOWN -> 5
            MotionEvent.ACTION_POINTER_UP -> 6
            else -> return true
        }
        val n = e.pointerCount.coerceAtMost(5)
        val pontos = IntArray(n * 3)
        for (i in 0 until n) {
            pontos[i * 3] = (e.getX(i) * w / v.width).toInt().coerceIn(0, w - 1)
            pontos[i * 3 + 1] = (e.getY(i) * l / v.height).toInt().coerceIn(0, l - 1)
            pontos[i * 3 + 2] = e.getPointerId(i)
        }
        AndroidAuto.toque(acao, e.actionIndex.coerceAtMost(n - 1), pontos)
        return true
    }

    // ------------------------------------------------------------------ decodificador

    /**
     * H.264 por hardware direto na superfície. A linha de leitura do cabo só COPIA o quadro para a fila
     * (nunca espera o decodificador); uma linha própria entrega ao MediaCodec e mostra.
     */
    class Decodificador(private val superficie: Surface, w: Int, l: Int) : AaVideoSaida {
        private class Pedaco(val b: ByteArray, val config: Boolean)

        private companion object {
            /** Quanto tempo esperar uma imagem inteira depois de perder quadros (passou: mostra o que vier). */
            const val ESPERA_CHAVE_MS = 3_000L
        }

        private val fila = ArrayBlockingQueue<Pedaco>(60)
        private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        private val formato = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, l).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
        }
        @Volatile private var rodando = true
        @Volatile private var quadros = 0L
        /**
         * Depois de jogar quadros fora (fila cheia, decodificador sem vaga, reinício), os quadros seguintes dependem dos
         * que sumiram: mostrá-los dá "borrão". Espera o próximo quadro inteiro (IDR/SPS) por até [ESPERA_CHAVE_MS]
         * (o pedido de imagem nova vai junto); passou disso, segue como antes.
         */
        @Volatile private var esperandoChaveDesde = 0L
        private var reinicios = 0
        private var pulados = 0L
        private val linha: Thread

        init {
            codec.configure(formato, superficie, null, 0)
            codec.start()
            Registro.anotar("aa: decodificador de vídeo pronto (${codec.name}, ${w}x$l)")
            linha = Thread({ laco() }, "aa-video").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        }

        override fun config(b: ByteArray, ini: Int, n: Int) = por(b, ini, n, true)
        override fun quadro(b: ByteArray, ini: Int, n: Int) = por(b, ini, n, false)

        private fun por(b: ByteArray, ini: Int, n: Int, config: Boolean) {
            if (!rodando || n <= 0) return
            if (!fila.offer(Pedaco(b.copyOfRange(ini, ini + n), config))) {
                // Atrasou demais (central ocupada): joga a fila fora e pede uma imagem inteira nova.
                fila.clear()
                esperarChave()
            }
        }

        private fun esperarChave() {
            if (esperandoChaveDesde == 0L) esperandoChaveDesde = System.nanoTime()
            AndroidAuto.pedirImagem()   // no máximo 1 pedido a cada 2 s (AaSessao)
        }

        /** Quadro que começa uma imagem inteira: tem SPS (7) ou IDR (5) entre os primeiros NAL. */
        private fun ehChave(b: ByteArray): Boolean {
            val fim = minOf(b.size - 4, 512)
            var i = 0
            while (i < fim) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                    val tipo = b[i + 3].toInt() and 0x1F
                    if (tipo == 5 || tipo == 7) return true
                    i += 3
                } else i++
            }
            return false
        }

        private fun laco() {
            val info = MediaCodec.BufferInfo()
            var erros = 0
            while (rodando) {
                try {
                    val p = fila.poll(20, TimeUnit.MILLISECONDS)
                    if (p != null && !p.config && esperandoChaveDesde != 0L) {
                        if (ehChave(p.b) || System.nanoTime() - esperandoChaveDesde > ESPERA_CHAVE_MS * 1_000_000) {
                            if (pulados > 0) Registro.anotar("aa: vídeo voltou com imagem inteira ($pulados quadros pulados)")
                            esperandoChaveDesde = 0L; pulados = 0
                        } else {
                            pulados++
                            AndroidAuto.pedirImagem()
                            soltarSaidas(info)
                            continue
                        }
                    }
                    if (p != null) {
                        var i = -1
                        val limite = System.nanoTime() + 200_000_000L
                        while (rodando && i < 0 && System.nanoTime() < limite) {
                            i = codec.dequeueInputBuffer(10_000)
                            if (i < 0) soltarSaidas(info)
                        }
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            buf.clear()
                            val n = minOf(p.b.size, buf.capacity())
                            buf.put(p.b, 0, n)
                            codec.queueInputBuffer(i, 0, n, System.nanoTime() / 1000,
                                if (p.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
                            if (!p.config && ++quadros == 1L) Registro.anotar("aa: primeiro quadro na tela")
                        } else if (rodando && !p.config) {
                            // Sem vaga no decodificador por 200 ms: este quadro se perdeu; os próximos dependem dele.
                            fila.clear()
                            esperarChave()
                        }
                    }
                    soltarSaidas(info)
                    erros = 0
                } catch (t: Throwable) {
                    if (!rodando) break
                    if (++erros <= 3) Registro.anotar("ERRO aa: decodificador: ${t.javaClass.simpleName}: ${t.message?.take(80)}")
                    // Vários erros seguidos: o MediaCodec quebrou. Reinicia (até 3 vezes) e pede a configuração e uma
                    // imagem inteira de novo; antes ficava parado (tela preta) copiando o vídeo à toa até fechar a tela.
                    if (erros >= 5) {
                        if (reinicios >= 3 || !reiniciar()) {
                            Registro.anotar("aa: decodificador desistiu (a tela volta quando ela for aberta de novo)")
                            rodando = false
                            fila.clear()
                            AndroidAuto.desligarVideo(this)
                            break
                        }
                        erros = 0
                    }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
        }

        private fun reiniciar(): Boolean = try {
            reinicios++
            codec.reset()
            codec.configure(formato, superficie, null, 0)
            codec.start()
            fila.clear()
            esperandoChaveDesde = System.nanoTime()
            Registro.anotar("aa: decodificador reiniciado (vez $reinicios)")
            AndroidAuto.ligarVideo(this)   // manda a configuração guardada e pede uma imagem inteira
            true
        } catch (t: Throwable) {
            Registro.anotar("ERRO aa: não reiniciei o decodificador: ${t.javaClass.simpleName}")
            false
        }

        private fun soltarSaidas(info: MediaCodec.BufferInfo) {
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o >= 0) codec.releaseOutputBuffer(o, true) else break
            }
        }

        fun soltar() {
            rodando = false
            try { linha.join(500) } catch (_: Throwable) { }
            try { codec.stop() } catch (_: Throwable) { }
            try { codec.release() } catch (_: Throwable) { }
            fila.clear()
        }
    }
}
