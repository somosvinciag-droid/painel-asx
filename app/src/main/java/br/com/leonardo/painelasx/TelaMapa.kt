package br.com.leonardo.painelasx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela do mapa (desenho aprovado "Mapa do Painel ASX", 1280×720, estilo F3): mapa 3D/2D, cartão da próxima curva,
 * botões 2D/3D, aproximar/afastar/centralizar, velocidade, faixa de baixo com destino, tempo, km, chegada e música.
 * Só abre quando pedido (nunca na partida). Ao sair, solta o desenho e as células da memória.
 */
class TelaMapa : Activity() {
    companion object {
        fun abrir(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, TelaMapa::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (e: Exception) {
                Registro.anotar("ERRO mapa: não abriu a tela (${e.message})")
            }
        }
    }

    private val FUNDO = Color.parseColor("#0D0F0E")
    private val PAINEL = Color.argb(240, 0x14, 0x16, 0x15)
    private val BORDA = Color.parseColor("#262927")
    private val BORDA2 = Color.parseColor("#3A3D3B")
    private val TEXTO = Color.parseColor("#EDEDE8")
    private val CINZA = Color.parseColor("#8C8F8B")
    private val VERDE = Color.parseColor("#009246")
    private val BRANCO_IT = Color.parseColor("#F1F2F1")
    private val VERMELHO = Color.parseColor("#CE2B37")

    private var s = 1f
    private lateinit var forte: Typeface
    private lateinit var fino: Typeface
    private var gl: GLSurfaceView? = null
    private var desenho: MapaDesenho? = null
    private lateinit var rotulos: Rotulos
    private lateinit var cartao: LinearLayout
    private lateinit var seta: SetaManobra
    private lateinit var cartaoDist: TextView
    private lateinit var cartaoAcao: TextView
    private lateinit var cartaoRua: TextView
    private lateinit var b2d: TextView
    private lateinit var b3d: TextView
    private lateinit var vel: TextView
    private lateinit var limite: TextView
    private lateinit var destino: TextView
    private lateinit var destinoSub: TextView
    private lateinit var tempo: TextView
    private lateinit var faltam: TextView
    private lateinit var chegada: TextView
    private lateinit var musica: TextView
    private lateinit var artista: TextView
    private lateinit var aviso: TextView
    private lateinit var area: FrameLayout

    private val h = Handler(Looper.getMainLooper())
    private var tique = 0
    private var registrouGl = false
    private var iniciada = false

    private val ritmo = object : Runnable {
        override fun run() {
            val d = desenho
            val g = gl
            var espera = 500L
            if (d != null && g != null) {
                if (d.animando || SystemClock.uptimeMillis() < d.ocupadoAte) { g.requestRender(); espera = 33L }   // até 30 fps
                else if (tique % 2 == 0) g.requestRender()
                if (!registrouGl && d.rendererGl.isNotEmpty()) { registrouGl = true; Registro.anotar("mapa: GPU ${d.rendererGl}") }
            }
            h.postDelayed(this, espera)
        }
    }
    private val painel = object : Runnable {
        override fun run() {
            tique++
            try { pintar() } catch (_: Exception) { }
            h.postDelayed(this, 500)
        }
    }
    private val mudouRota: () -> Unit = { desenho?.mudarRota(Navegacao.rota); try { pintar() } catch (_: Exception) { } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val dm = resources.displayMetrics
        s = minOf(dm.widthPixels / 1280f, dm.heightPixels / 720f)
        val base = try { Typeface.createFromAsset(assets, "fontes/BigShouldersDisplay.ttf") } catch (_: Exception) { null }
        fun peso(w: Int): Typeface = try {
            Typeface.Builder(assets, "fontes/BigShouldersDisplay.ttf").setFontVariationSettings("'wght' $w").build() ?: base ?: Typeface.DEFAULT
        } catch (_: Exception) { base ?: Typeface.DEFAULT }
        forte = peso(600); fino = peso(300)
        montar()
        Navegacao.telaAberta = true
        MapaPacote.talvezBaixar(this, "abriu o mapa")
    }

    private fun px(v: Float) = Math.round(v * s)
    private fun px(v: Int) = px(v.toFloat())

    private fun texto(t: String, tam: Float, cor: Int, tf: Typeface = Typeface.DEFAULT, espaco: Float = 0f) = TextView(this).apply {
        text = t; setTextColor(cor); typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tam * s)
        letterSpacing = espaco
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun caixa(cor: Int = PAINEL, borda: Int? = BORDA) = GradientDrawable().apply {
        setColor(cor); cornerRadius = 4 * s
        if (borda != null) setStroke(maxOf(1, px(1)), borda)
    }

    private fun fl(w: Int, hh: Int, grav: Int, l: Int = 0, t: Int = 0, r: Int = 0, bm: Int = 0) =
        FrameLayout.LayoutParams(w, hh, grav).apply { leftMargin = l; topMargin = t; rightMargin = r; bottomMargin = bm }

    private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

    private fun montar() {
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(FUNDO) }
        setContentView(raiz)
        val faixa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in listOf(VERDE, BRANCO_IT, VERMELHO)) faixa.addView(View(this).apply { setBackgroundColor(c) }, LinearLayout.LayoutParams(0, MATCH, 1f))
        raiz.addView(faixa, LinearLayout.LayoutParams(MATCH, maxOf(1, px(6))))
        area = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#101211")) }
        raiz.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))

        rotulos = Rotulos(this)
        area.addView(rotulos, FrameLayout.LayoutParams(MATCH, MATCH))

        // aviso no meio (mapa ainda não baixado / calculando)
        aviso = texto("", 26f, TEXTO, forte, 0.04f).apply {
            gravity = Gravity.CENTER; maxLines = 3; background = caixa(); setPadding(px(28), px(18), px(28), px(18)); visibility = View.GONE
        }
        area.addView(aviso, fl(WRAP, WRAP, Gravity.CENTER))

        // cartão da próxima curva
        cartao = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = caixa(); setPadding(px(22), px(18), px(22), px(18))
        }
        seta = SetaManobra(this)
        cartao.addView(seta, LinearLayout.LayoutParams(px(76), px(76)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cartaoDist = texto("", 52f, TEXTO, forte)
        cartaoAcao = texto("", 19f, TEXTO, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
        cartaoRua = texto("", 16f, CINZA)
        col.addView(cartaoDist); col.addView(cartaoAcao, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(2) })
        col.addView(cartaoRua, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(2) })
        cartao.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(16) })
        area.addView(cartao, fl(px(380), WRAP, Gravity.TOP or Gravity.START, l = px(28), t = px(24)))

        // 2D / 3D
        val modo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = caixa(borda = BORDA2) }
        b2d = texto("2D", 24f, TEXTO, forte, 0.12f).apply { gravity = Gravity.CENTER; setOnClickListener { mudarModo(false) } }
        b3d = texto("3D", 24f, TEXTO, forte, 0.12f).apply { gravity = Gravity.CENTER; setOnClickListener { mudarModo(true) } }
        modo.addView(b2d, LinearLayout.LayoutParams(0, MATCH, 1f)); modo.addView(b3d, LinearLayout.LayoutParams(0, MATCH, 1f))
        modo.clipToOutline = true
        area.addView(modo, fl(px(176), px(64), Gravity.TOP or Gravity.END, t = px(24), r = px(28)))

        // aproximar / afastar / centralizar
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun botao(tipo: Int, cor: Int, f: () -> Unit) = Icone(this, tipo, cor).apply {
            background = caixa(borda = BORDA2); setOnClickListener { f() }
        }
        botoes.addView(botao(0, TEXTO) { zoom(0.7) }, LinearLayout.LayoutParams(px(64), px(64)))
        botoes.addView(botao(1, TEXTO) { zoom(1 / 0.7) }, LinearLayout.LayoutParams(px(64), px(64)).apply { topMargin = px(8) })
        botoes.addView(botao(2, VERMELHO) { desenho?.centralizar() }, LinearLayout.LayoutParams(px(64), px(64)).apply { topMargin = px(8) })
        area.addView(botoes, fl(WRAP, WRAP, Gravity.TOP or Gravity.END, t = px(112), r = px(28)))

        // velocidade
        val velCaixa = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = caixa(); setPadding(px(18), px(12), px(18), px(12)) }
        val linhaVel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        vel = texto("0", 72f, TEXTO, fino)
        linhaVel.addView(vel)
        linhaVel.addView(texto("km/h", 20f, CINZA, forte), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = px(8); bottomMargin = px(4) })
        velCaixa.addView(linhaVel)
        limite = texto("LIMITE 110", 13f, CINZA, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 0.2f)
        velCaixa.addView(limite, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(2) })
        area.addView(velCaixa, fl(WRAP, WRAP, Gravity.BOTTOM or Gravity.START, l = px(28), bm = px(112)))

        // faixa de baixo
        val faixaBaixo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = caixa(); setPadding(px(22), 0, px(22), 0)
        }
        val dest = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        destino = texto("Sem rota", 26f, TEXTO, forte); destinoSub = texto("Fale: rota para…", 14f, CINZA)
        dest.addView(destino); dest.addView(destinoSub)
        // segurar no destino encerra a rota
        dest.setOnLongClickListener { val r = Navegacao.encerrar(); Voz.falar(r); true }
        faixaBaixo.addView(dest, LinearLayout.LayoutParams(0, WRAP, 1f))
        fun numero(rot: String): TextView {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val v = texto("–", 28f, TEXTO, forte)
            c.addView(v); c.addView(texto(rot, 12f, CINZA, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 0.2f))
            faixaBaixo.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = px(28) })
            return v
        }
        tempo = numero("TEMPO"); faltam = numero("FALTAM"); chegada = numero("CHEGADA")
        val mus = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        val musTxt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        musica = texto("", 15f, TEXTO, Typeface.create(Typeface.DEFAULT, Typeface.BOLD)).apply { gravity = Gravity.END }
        artista = texto("", 13f, CINZA).apply { gravity = Gravity.END }
        musTxt.addView(musica, LinearLayout.LayoutParams(WRAP, WRAP)); musTxt.addView(artista, LinearLayout.LayoutParams(WRAP, WRAP))
        mus.addView(musTxt, LinearLayout.LayoutParams(0, WRAP, 1f))
        val play = Icone(this, 3, FUNDO).apply { background = caixa(TEXTO, null); setOnClickListener { alternarMusica() } }
        mus.addView(play, LinearLayout.LayoutParams(px(48), px(48)).apply { leftMargin = px(12) })
        faixaBaixo.addView(mus, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = px(28) })
        area.addView(faixaBaixo, fl(MATCH, px(72), Gravity.BOTTOM, l = px(28), r = px(28), bm = px(24)))

        Navegacao.ouvir(mudouRota)
        pintarModo()
        pintar()
    }

    /** O desenho do mapa só nasce quando o pacote existe (e é solto ao sair da tela). */
    private fun ligarDesenho() {
        if (gl != null) return
        val d = Navegacao.dados(this) ?: return
        val r = MapaDesenho(d, s)
        r.tres = Config.mapa3d(this)
        r.mudarRota(Navegacao.rota)
        val v = GLSurfaceView(this)
        v.setEGLContextClientVersion(2)
        v.setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        v.preserveEGLContextOnPause = false
        v.setRenderer(r)
        v.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        var ux = 0f; var uy = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ux = e.x; uy = e.y }
                MotionEvent.ACTION_MOVE -> { r.arrastar(e.x - ux, e.y - uy); ux = e.x; uy = e.y }
            }
            true
        }
        area.addView(v, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        gl = v; desenho = r
        rotulos.desenho = r
        r.ocupadoAte = SystemClock.uptimeMillis() + 2000
    }

    private fun desligarDesenho() {
        val v = gl ?: return
        try { v.onPause() } catch (_: Exception) { }
        area.removeView(v)
        desenho?.encerrar()
        gl = null; desenho = null; rotulos.desenho = null
    }

    override fun onStart() {
        super.onStart()
        Navegacao.telaAberta = true
        iniciada = true
        ligarDesenho()
        gl?.onResume()
        h.post(ritmo); h.post(painel)
    }

    override fun onStop() {
        h.removeCallbacks(ritmo); h.removeCallbacks(painel)
        iniciada = false
        // solta a placa de vídeo e as células (a central tem pouca memória)
        desligarDesenho()
        Navegacao.telaAberta = false
        Navegacao.talvezSoltar()
        super.onStop()
    }

    override fun onDestroy() {
        Navegacao.parar(mudouRota)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun zoom(f: Double) {
        val r = desenho ?: return
        r.zoom = (r.zoom * f).coerceIn(0.35, 8.0)
        r.ocupadoAte = SystemClock.uptimeMillis() + 1200
    }

    private fun mudarModo(tres: Boolean) {
        Config.mudarMapa3d(this, tres)
        desenho?.let { it.tres = tres; it.ocupadoAte = SystemClock.uptimeMillis() + 1500 }
        rotulos.tres = tres
        pintarModo()
    }

    private fun pintarModo() {
        val tres = Config.mapa3d(this)
        desenho?.tres = tres
        rotulos.tres = tres
        b3d.setBackgroundColor(if (tres) TEXTO else Color.TRANSPARENT); b3d.setTextColor(if (tres) FUNDO else TEXTO)
        b2d.setBackgroundColor(if (!tres) TEXTO else Color.TRANSPARENT); b2d.setTextColor(if (!tres) FUNDO else TEXTO)
    }

    private fun alternarMusica() {
        if (!Musica.pararRadio() && (Musica.centralTocando(this) || !(AndroidAuto.controle("alternar") || Celular.controle("alternar"))))
            Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    private fun pintar() {
        if (gl == null && iniciada && MapaPacote.pronto(this)) { ligarDesenho(); gl?.onResume() }
        pintarModo()
        // aviso no meio
        val avisoTxt = when {
            gl == null -> MapaPacote.explicacao() + ".\nEle baixa sozinho, uma vez só, quando houver internet."
            Navegacao.estado == "calculando" -> "Calculando a rota…"
            else -> ""
        }
        aviso.text = avisoTxt
        aviso.visibility = if (avisoTxt.isEmpty()) View.GONE else View.VISIBLE

        vel.text = Carro.velocidadeKmh.toInt().toString()
        limite.text = "LIMITE ${Config.limite(this)}"

        val g = Navegacao.guia
        if (g == null) {
            cartao.visibility = View.GONE
            destino.text = "Sem rota"; destinoSub.text = "Fale: rota para…"
            tempo.text = "–"; faltam.text = "–"; chegada.text = "–"
        } else {
            val m = g.proximaManobra()
            cartao.visibility = if (m != null) View.VISIBLE else View.GONE
            if (m != null) {
                seta.manobra = m
                cartaoDist.text = if (g.chegou) "Chegou" else MapaFrases.distanciaTela(g.distAteProxima().coerceAtLeast(0.0))
                cartaoAcao.text = when (m.tipo) {
                    Manobra.Tipo.CHEGADA -> if (g.chegou) "Você chegou" else "Chegada"
                    Manobra.Tipo.PARADA -> "Parada"
                    else -> MapaFrases.acao(m)
                }
                cartaoRua.text = when (m.tipo) {
                    Manobra.Tipo.CHEGADA, Manobra.Tipo.PARADA -> m.nome
                    else -> m.nome.ifBlank { " " }
                }
            }
            destino.text = g.rota.destino
            destinoSub.text = if (g.distDaRotaM > 40) "Fora da rota" else "Segure aqui para encerrar"
            tempo.text = MapaFrases.duracaoTela(g.restanteS)
            faltam.text = MapaFrases.distanciaTela(g.restanteM)
            chegada.text = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(System.currentTimeMillis() + (g.restanteS * 1000).toLong()))
        }
        if (tique % 2 == 0) {
            val t = try { Musica.tocando(this) } catch (_: Exception) { null }
            musica.text = t?.titulo ?: ""
            artista.text = t?.artista ?: ""
        }
    }

    // ================================================================ peças desenhadas

    /** Rótulos das ruas perto do carro e a sombra de cima (3D), por cima do mapa. */
    private inner class Rotulos(ctx: Context) : View(ctx) {
        @Volatile var desenho: MapaDesenho? = null
        var tres = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CINZA; textSize = 15 * s; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.12f
            setShadowLayer(4 * s, 0f, 0f, Color.parseColor("#101211"))
        }
        private val sombra = Paint()
        private var lista: List<Triple<String, Double, Double>> = emptyList()
        private var listaX = Double.NaN; private var listaY = 0.0
        private var calculando = false
        private val mvp = FloatArray(16)

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        private fun abreviar(n: String) = n.replace(Regex("^Rua "), "R. ").replace(Regex("^Avenida "), "Av. ")
            .replace(Regex("^Travessa "), "Tv. ").replace(Regex("^Praça "), "Pç. ").replace(Regex("^Rodovia "), "Rod. ").uppercase(Locale("pt", "BR"))

        private fun recalcular(dd: MapaDesenho, d: MapaDados) {
            if (calculando) return
            val cx = dd.carroX; val cy = dd.carroY
            if (!listaX.isNaN() && Math.hypot(cx - listaX, cy - listaY) < 800) return
            calculando = true
            listaX = cx; listaY = cy
            Thread({
                try {
                    val melhor = HashMap<String, Triple<String, Double, Double>>()
                    val comp = HashMap<String, Int>()
                    for (v in d.viasPerto(cx, cy, 6000.0)) {
                        val nome = d.nome(d.viaNome(v.via)); if (nome.isBlank()) continue
                        val c = d.viaComp(v.via)
                        if (c < 300) continue
                        if (c > (comp[nome] ?: 0)) {
                            comp[nome] = c
                            val p0 = d.viaPrimPonto(v.via); val n = d.viaNPontos(v.via)
                            val k = n / 2
                            val mx = if (n % 2 == 0) (d.pontoX(p0 + k - 1) + d.pontoX(p0 + k)) / 2.0 else d.pontoX(p0 + k).toDouble()
                            val my = if (n % 2 == 0) (d.pontoY(p0 + k - 1) + d.pontoY(p0 + k)) / 2.0 else d.pontoY(p0 + k).toDouble()
                            melhor[nome] = Triple(abreviar(nome), mx, my)
                        }
                    }
                    lista = melhor.values.sortedByDescending { comp[it.first] ?: 0 }.take(14)
                } catch (_: Exception) { } finally { calculando = false }
            }, "mapa-rotulos").start()
        }

        override fun onDraw(c: Canvas) {
            val dd = desenho
            if (dd != null) {
                Navegacao.dados(context)?.let { recalcular(dd, it) }
                val cx: Double; val cy: Double
                synchronized(dd.mvpPublica) { System.arraycopy(dd.mvpPublica, 0, mvp, 0, 16); cx = dd.centroPublicoX; cy = dd.centroPublicoY }
                val usados = ArrayList<RectF>()
                val v = FloatArray(4); val o = FloatArray(4)
                for ((t, x, y) in lista) {
                    if (usados.size >= 7) break
                    v[0] = ((x - cx) / 10).toFloat(); v[1] = ((y - cy) / 10).toFloat(); v[2] = 0f; v[3] = 1f
                    android.opengl.Matrix.multiplyMV(o, 0, mvp, 0, v, 0)
                    if (o[3] <= 0) continue
                    val sx = (o[0] / o[3] + 1) / 2 * width; val sy = (1 - o[1] / o[3]) / 2 * height
                    if (sy < height * (if (tres) 0.3f else 0.08f) || sy > height * 0.82f || sx < 0 || sx > width) continue
                    val w = p.measureText(t)
                    val r = RectF(sx - w / 2 - 6, sy - p.textSize - 4, sx + w / 2 + 6, sy + 6)
                    if (usados.any { RectF.intersects(it, r) }) continue
                    usados.add(r)
                    c.drawText(t, sx - w / 2, sy, p)
                }
            }
            if (tres) {
                sombra.shader = LinearGradient(0f, 0f, 0f, 170 * s, FUNDO, Color.argb(0, 13, 15, 14), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, width.toFloat(), 170 * s, sombra)
            }
            postInvalidateDelayed(if (dd != null && dd.animando) 250L else 1000L)
        }
    }

    /** Ícone da curva (traço branco, como o desenho aprovado). */
    private inner class SetaManobra(ctx: Context) : View(ctx) {
        var manobra: Manobra? = null
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = BRANCO_IT; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(c: Canvas) {
            val m = manobra ?: return
            val k = width / 24f
            p.strokeWidth = 2.2f * k
            c.save()
            c.scale(k, k)
            val espelho = m.lado < 0
            if (espelho) { c.translate(24f, 0f); c.scale(-1f, 1f) }
            val path = Path()
            when (m.tipo) {
                Manobra.Tipo.VIRE, Manobra.Tipo.ACENTUADA -> {
                    // M8 21V11a3 3 0 0 1 3-3h9 ; M16 4l4 4-4 4
                    path.moveTo(8f, 21f); path.lineTo(8f, 11f); path.quadTo(8f, 8f, 11f, 8f); path.lineTo(20f, 8f)
                    path.moveTo(16f, 4f); path.lineTo(20f, 8f); path.lineTo(16f, 12f)
                    if (m.tipo == Manobra.Tipo.ACENTUADA) { path.reset(); path.moveTo(7f, 21f); path.lineTo(7f, 6f); path.lineTo(17f, 17f); path.moveTo(17f, 11.5f); path.lineTo(17f, 17f); path.lineTo(11.5f, 17f) }
                }
                Manobra.Tipo.LEVE, Manobra.Tipo.SAIDA, Manobra.Tipo.MANTENHA -> {
                    path.moveTo(9f, 21f); path.lineTo(9f, 14f); path.lineTo(17f, 6f)
                    path.moveTo(12f, 5f); path.lineTo(17f, 5f); path.lineTo(17f, 10f)
                    if (m.tipo == Manobra.Tipo.MANTENHA) { path.moveTo(9f, 14f); path.lineTo(5f, 9f) }
                }
                Manobra.Tipo.RETORNO -> {
                    path.moveTo(16f, 21f); path.lineTo(16f, 10f); path.quadTo(16f, 5f, 12f, 5f); path.quadTo(8f, 5f, 8f, 10f); path.lineTo(8f, 15f)
                    path.moveTo(4.5f, 11.5f); path.lineTo(8f, 15f); path.lineTo(11.5f, 11.5f)
                }
                Manobra.Tipo.ROTATORIA -> {
                    path.addCircle(12f, 10f, 4.2f, Path.Direction.CW)
                    path.moveTo(12f, 21f); path.lineTo(12f, 14.2f)
                    path.moveTo(15f, 7f); path.lineTo(20f, 3f); path.moveTo(16f, 3f); path.lineTo(20f, 3f); path.lineTo(20f, 7f)
                }
                Manobra.Tipo.CHEGADA, Manobra.Tipo.PARADA -> {
                    path.moveTo(7f, 21f); path.lineTo(7f, 3.5f); path.lineTo(18f, 7.5f); path.lineTo(7f, 11.5f)
                }
                else -> {
                    path.moveTo(12f, 21f); path.lineTo(12f, 4f); path.moveTo(7f, 9f); path.lineTo(12f, 4f); path.lineTo(17f, 9f)
                }
            }
            c.drawPath(path, p)
            if (m.tipo == Manobra.Tipo.ROTATORIA) {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRANCO_IT; textSize = 6.5f; typeface = forte; textAlign = Paint.Align.CENTER }
                if (espelho) { c.scale(-1f, 1f); c.drawText(m.saida.toString(), -12f, 12.4f, t) } else c.drawText(m.saida.toString(), 12f, 12.4f, t)
            }
            c.restore()
        }
    }

    /** Ícones dos botões: 0 mais, 1 menos, 2 centralizar (seta), 3 pausa. */
    private inner class Icone(ctx: Context, private val tipo: Int, private val cor: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(c: Canvas) {
            val tam = if (tipo == 3) 20 * s else 26 * s
            val k = tam / 24f
            c.save()
            c.translate((width - tam) / 2, (height - tam) / 2); c.scale(k, k)
            p.color = cor; p.strokeWidth = if (tipo == 3) 2.6f else 2f
            when (tipo) {
                0 -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
                1 -> c.drawLine(5f, 12f, 19f, 12f, p)
                2 -> { val pa = Path(); pa.moveTo(12f, 3f); pa.lineTo(19f, 20f); pa.lineTo(12f, 16f); pa.lineTo(5f, 20f); pa.close(); c.drawPath(pa, p) }
                3 -> { c.drawLine(8f, 5f, 8f, 19f, p); c.drawLine(16f, 5f, 16f, 19f, p) }
            }
            c.restore()
        }
    }
}
