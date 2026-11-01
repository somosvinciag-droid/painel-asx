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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A tela do Android Auto (decisão do Leonardo, 01/11): o vídeo que o celular manda pelo cabo é decodificado
 * (H.264 pelo MediaCodec, direto na superfície, sem passar pelo processador) e os toques voltam para o celular.
 * Abre sozinha quando o Android Auto liga. "PAINEL" (canto) ou o "sair" do próprio Android Auto voltam para a TelaCarro.
 */
class TelaAndroidAuto : Activity(), SurfaceHolder.Callback {

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

    private var superficie: SurfaceView? = null
    private var decodificador: Decodificador? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        raiz.addView(sv, FrameLayout.LayoutParams(-1, -1))
        // Botão discreto para voltar à tela do Painel (o Android Auto continua ligado: som, música, navegação).
        val painel = TextView(this).apply {
            text = "PAINEL"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(22, 10, 22, 10)
            setOnClickListener { voltarAoPainel() }
        }
        raiz.addView(painel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        superficie = sv
        setContentView(raiz)
        aberta = this
        Registro.anotar("aa: tela do Android Auto aberta")
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

    override fun surfaceCreated(h: SurfaceHolder) {
        soltarDecodificador()
        val (w, l) = AndroidAuto.tamanhoVideo()
        val d = try { Decodificador(h.surface, w, l) } catch (e: Throwable) {
            Registro.anotar("ERRO aa: não abri o decodificador de vídeo: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            null
        } ?: return
        decodificador = d
        AndroidAuto.ligarVideo(d)
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, l: Int) {}

    override fun surfaceDestroyed(h: SurfaceHolder) = soltarDecodificador()

    private fun soltarDecodificador() {
        val d = decodificador ?: return
        decodificador = null
        AndroidAuto.desligarVideo(d)
        d.soltar()
    }

    // ------------------------------------------------------------------ toque

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val v = superficie ?: return false
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
    class Decodificador(superficie: Surface, w: Int, l: Int) : AaVideoSaida {
        private class Pedaco(val b: ByteArray, val config: Boolean)

        private val fila = ArrayBlockingQueue<Pedaco>(60)
        private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        @Volatile private var rodando = true
        @Volatile private var quadros = 0L
        private val linha: Thread

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, l)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            codec.configure(f, superficie, null, 0)
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
                AndroidAuto.pedirImagem()
            }
        }

        private fun laco() {
            val info = MediaCodec.BufferInfo()
            var erros = 0
            while (rodando) {
                try {
                    val p = fila.poll(20, TimeUnit.MILLISECONDS)
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
                        }
                    }
                    soltarSaidas(info)
                } catch (t: Throwable) {
                    if (!rodando) break
                    if (++erros <= 3) Registro.anotar("ERRO aa: decodificador: ${t.javaClass.simpleName}: ${t.message?.take(80)}")
                    if (erros > 50) { Registro.anotar("aa: decodificador desistiu"); break }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
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
