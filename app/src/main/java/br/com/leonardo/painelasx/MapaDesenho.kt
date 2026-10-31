package br.com.leonardo.painelasx

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Desenha o mapa com OpenGL ES 2.0 (qualquer Android tem): chão escuro, ruas cinza, prédios em blocos brancos
 * (subindo de 0 a altura real ao passar de 2D para 3D), rota vermelha com borda branca e a seta do carro.
 * Só carrega as células de 500 m perto do carro, montadas numa linha própria ("mapa-carga"); as que ficam
 * longe saem da memória. Coordenadas na placa de vídeo em metros perto da câmera (sem tremer).
 */
class MapaDesenho(private val d: MapaDados, private val escalaTela: Float) : GLSurfaceView.Renderer {
    // ------------------------------------------------ o que a tela pede (linha principal)
    @Volatile var tres = true
    @Volatile var zoom = 1.0
    @Volatile var seguindo = true
    @Volatile private var panX = 0.0   // dm
    @Volatile private var panY = 0.0
    /** Até quando desenhar a 30 fps (depois de um toque, posição nova...). */
    @Volatile var ocupadoAte = 0L
    @Volatile var animando = true
        private set
    @Volatile var rendererGl = ""
        private set

    // para os rótulos (Canvas por cima): a última matriz e o centro usado
    val mvpPublica = FloatArray(16)
    @Volatile var centroPublicoX = 0.0
    @Volatile var centroPublicoY = 0.0
    @Volatile var largura = 1
    @Volatile var altura = 1
    @Volatile var metrosPorPixel = 1.0
    @Volatile var carroTelaX = -1f
    @Volatile var carroTelaY = -1f
    @Volatile var carroX = 0.0  // dm (onde a câmera acha que o carro está)
    @Volatile var carroY = 0.0

    // ------------------------------------------------ câmera (linha do GL)
    private var camX = Double.NaN; private var camY = 0.0; private var camRumo = 0.0
    private var inclinacao = 0.0; private var alturaPredios = 0.0
    private var ultimoQuadro = 0L
    private var queridasCache: Set<Int>? = null
    private var qX = 0.0; private var qY = 0.0; private var qR = 0.0

    // ------------------------------------------------ células
    private class Pronta(val c: Int, val ox: Double, val oy: Double, val maiores: FloatBuffer?, val nMaiores: Int,
                         val menores: FloatBuffer?, val nMenores: Int, val predios: FloatBuffer?, val nPredios: Int)
    private class NaPlaca(val c: Int, val ox: Double, val oy: Double, val vbo: IntArray, val nMaiores: Int, val nMenores: Int, val nPredios: Int)

    private val prontas = ConcurrentLinkedQueue<Pronta>()
    private val pedidas = ConcurrentHashMap.newKeySet<Int>()
    private val naPlaca = HashMap<Int, NaPlaca>()
    private val carga = HandlerThread("mapa-carga").also { it.start() }
    private val cargaH = Handler(carga.looper)
    @Volatile private var geracao = 0   // muda quando a placa é recriada (descarta o que estava a caminho)

    // ------------------------------------------------ rota
    @Volatile private var rotaNova: Rota? = null
    @Volatile private var rotaTrocou = true
    private var rotaVbo = 0; private var rotaN = 0; private var rotaOx = 0.0; private var rotaOy = 0.0
    private var rotaAtual: Rota? = null

    // ------------------------------------------------ shaders
    private var progLinha = 0; private var progPredio = 0; private var progTela = 0
    private var setaVbo = 0

    fun mudarRota(r: Rota?) { rotaNova = r; rotaTrocou = true; ocupadoAte = SystemClock.uptimeMillis() + 1500 }

    fun arrastar(dxPx: Float, dyPx: Float) {
        if (seguindo) { panX = carroX; panY = carroY; seguindo = false }
        val r = Math.toRadians(camRumo)
        val mpp = metrosPorPixel * 10
        // direita da tela no mundo = (cos, -sin); frente = (sin, cos)
        panX += (-dxPx * Math.cos(r) + dyPx * Math.sin(r)) * mpp
        panY += (dxPx * Math.sin(r) + dyPx * Math.cos(r)) * mpp
        ocupadoAte = SystemClock.uptimeMillis() + 800
    }

    fun centralizar() { seguindo = true; ocupadoAte = SystemClock.uptimeMillis() + 1500 }

    /** Sai da tela: solta tudo (a placa some junto com o contexto do GL). */
    fun liberar() {
        geracao++
        queridasCache = null
        prontas.clear(); pedidas.clear()
        naPlaca.clear()
        rotaVbo = 0; rotaAtual = null; rotaTrocou = true
    }

    fun encerrar() {
        liberar()
        carga.quitSafely()
    }

    // ================================================================ GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        geracao++
        queridasCache = null
        naPlaca.clear(); prontas.clear(); pedidas.clear()
        rotaVbo = 0; rotaAtual = null; rotaTrocou = true
        rendererGl = "${GLES20.glGetString(GLES20.GL_RENDERER)} · ${GLES20.glGetString(GLES20.GL_VERSION)}"
        progLinha = programa(VS_LINHA, FS_LINHA)
        progPredio = programa(VS_PREDIO, FS_PREDIO)
        progTela = programa(VS_TELA, FS_TELA)
        // seta do carro (do desenho aprovado: M28 4 L52 58 L28 46 L4 58 Z, centro em 28,36)
        val seta = floatArrayOf(28f, 4f, 52f, 58f, 28f, 46f, 28f, 4f, 28f, 46f, 4f, 58f).also {
            for (i in it.indices step 2) { it[i] -= 28f; it[i + 1] -= 36f }
        }
        setaVbo = vbo(buffer(seta))
        GLES20.glClearColor(0x15 / 255f, 0x18 / 255f, 0x16 / 255f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        largura = w; altura = h
    }

    override fun onDrawFrame(gl: GL10?) {
        val agora = SystemClock.uptimeMillis()
        val dt = if (ultimoQuadro == 0L) 0.033 else ((agora - ultimoQuadro) / 1000.0).coerceIn(0.0, 0.5)
        ultimoQuadro = agora
        var mexendo = false

        // ---- onde está o carro (GPS + previsão até 1,2 s; colado na rota se estiver nela)
        val l = Carro.ultimaPosicao
        var alvoX: Double; var alvoY: Double; var alvoRumo = camRumo
        var kmh = 0.0
        if (l != null) {
            alvoX = d.x(l.longitude); alvoY = d.y(l.latitude)
            val g = Navegacao.guia
            if (g != null && g.distDaRotaM < 25) { alvoX = g.coladoX; alvoY = g.coladoY }
            kmh = l.speed * 3.6
            if (l.hasBearing() && l.speed > 1.5f) alvoRumo = l.bearing.toDouble()
            val idade = ((SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1e9).coerceIn(0.0, 1.2)
            if (l.speed > 1.5f) {
                val r = Math.toRadians(alvoRumo)
                alvoX += Math.sin(r) * l.speed * idade * 10; alvoY += Math.cos(r) * l.speed * idade * 10
            }
        } else {
            alvoX = d.x(-44.9330); alvoY = d.y(-21.9770)   // Caxambu, até o GPS chegar
            if (!d.dentro(alvoX, alvoY)) { alvoX = 0.0; alvoY = 0.0 }
        }
        if (camX.isNaN()) { camX = alvoX; camY = alvoY; camRumo = alvoRumo; carroX = alvoX; carroY = alvoY }
        val k = 1 - Math.exp(-dt / 0.35)
        carroX += (alvoX - carroX) * k; carroY += (alvoY - carroY) * k
        if (Math.hypot(alvoX - carroX, alvoY - carroY) > 2) mexendo = true
        if (Math.hypot(alvoX - carroX, alvoY - carroY) > 3000) { carroX = alvoX; carroY = alvoY }
        val giro = Geo.giro(camRumo, alvoRumo)
        camRumo = (camRumo + giro * (1 - Math.exp(-dt / 0.6)) + 360) % 360
        if (Math.abs(giro) > 0.5) mexendo = true
        if (seguindo) { camX = carroX; camY = carroY } else { camX = panX; camY = panY }

        // ---- 2D <-> 3D (0,8 s)
        val alvoInc = if (tres) 58.0 else 0.0
        val alvoAlt = if (tres) 1.0 else 0.0
        val passo = dt / 0.8
        inclinacao += (alvoInc - inclinacao).coerceIn(-58 * passo, 58 * passo)
        alturaPredios += (alvoAlt - alturaPredios).coerceIn(-passo, passo)
        if (Math.abs(alvoInc - inclinacao) > 0.01 || Math.abs(alvoAlt - alturaPredios) > 0.001) mexendo = true

        // ---- câmera
        val dist = (if (tres) 330.0 else 520.0) * zoom   // metros até o ponto olhado
        val rr = Math.toRadians(camRumo)
        val fx = Math.sin(rr).toFloat(); val fy = Math.cos(rr).toFloat()
        val adiante = dist * (if (tres) 0.42 else 0.18)
        val tx = (fx * adiante).toFloat(); val ty = (fy * adiante).toFloat()
        val inc = Math.toRadians(inclinacao)
        val ex = tx - fx * (dist * Math.sin(inc)).toFloat()
        val ey = ty - fy * (dist * Math.sin(inc)).toFloat()
        val ez = (dist * Math.cos(inc)).toFloat()
        val view = FloatArray(16); val proj = FloatArray(16); val mvp = FloatArray(16)
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, 0f, fx, fy, 0f)
        val asp = largura.toFloat() / altura
        Matrix.perspectiveM(proj, 0, 45f, asp, (dist * 0.05).toFloat(), (dist * 8).toFloat())
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        val mpp = 2 * dist * Math.tan(Math.toRadians(22.5)) / altura
        metrosPorPixel = mpp

        // ---- células: perto do ponto olhado
        val raio = if (tres) (dist * 4.2).coerceIn(700.0, 2600.0) else (dist * 1.9 * maxOf(1.0, asp.toDouble())).coerceIn(500.0, 4500.0)
        val centroX = camX + tx * 10; val centroY = camY + ty * 10
        val menores = dist < 1600
        if (queridasCache == null || Math.hypot(centroX - qX, centroY - qY) > 500 || Math.abs(raio - qR) > qR * 0.05) {
            queridasCache = celulasPerto(centroX, centroY, raio * 10); qX = centroX; qY = centroY; qR = raio
        }
        val queridas = queridasCache!!
        for (c in queridas) if (!naPlaca.containsKey(c) && pedidas.add(c)) pedir(c)
        // sobe no máximo 3 por quadro (não trava)
        var subiu = 0
        while (subiu < 3) {
            val p = prontas.poll() ?: break
            pedidas.remove(p.c)
            if (p.c !in queridas) continue
            naPlaca[p.c]?.let { apagar(it) }
            naPlaca[p.c] = subir(p); subiu++
        }
        if (prontas.isNotEmpty() || pedidas.isNotEmpty()) mexendo = true
        // solta as que ficaram longe
        if (naPlaca.size > queridas.size + 24) {
            val it = naPlaca.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key !in queridas) { apagar(e.value); it.remove() } }
        }

        // ---- rota
        if (rotaTrocou) {
            rotaTrocou = false
            val r = rotaNova ?: Navegacao.rota
            if (rotaVbo != 0) { GLES20.glDeleteBuffers(1, intArrayOf(rotaVbo), 0); rotaVbo = 0 }
            rotaAtual = r
            if (r != null && r.n >= 2) {
                rotaOx = r.xs[0]; rotaOy = r.ys[0]
                val fb = montarRota(r)
                rotaN = fb.limit() / 6
                rotaVbo = vbo(fb)
            }
        } else if (Navegacao.rota !== rotaAtual) { rotaNova = Navegacao.rota; rotaTrocou = true; mexendo = true }

        // ---- desenhar
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        val carroRelX = ((carroX - camX) / 10).toFloat(); val carroRelY = ((carroY - camY) / 10).toFloat()
        val nevoa = (raio * 0.97).toFloat()

        // ruas
        GLES20.glUseProgram(progLinha)
        uni(progLinha, "uMvp") { GLES20.glUniformMatrix4fv(it, 1, false, mvp, 0) }
        uni(progLinha, "uNevoa") { GLES20.glUniform3f(it, carroRelX + tx, carroRelY + ty, nevoa) }
        uni(progLinha, "uChao") { GLES20.glUniform3f(it, 0x15 / 255f, 0x18 / 255f, 0x16 / 255f) }
        uni(progLinha, "uProgresso") { GLES20.glUniform1f(it, -1e9f) }
        uni(progLinha, "uFeito") { GLES20.glUniform4f(it, 0.353f, 0.369f, 0.357f, 1f) }
        for (pl in naPlaca.values) {
            val ox = ((pl.ox - camX) / 10).toFloat(); val oy = ((pl.oy - camY) / 10).toFloat()
            uni(progLinha, "uOrigem") { GLES20.glUniform2f(it, ox, oy) }
            if (pl.nMenores > 0 && menores) linhas(pl.vbo[1], pl.nMenores, 1f, (1.6 * mpp).toFloat(), 0x2B, 0x2E, 0x2C)
            if (pl.nMaiores > 0) linhas(pl.vbo[0], pl.nMaiores, 1f, (2.4 * mpp).toFloat(), 0x33, 0x37, 0x35)
        }
        // rota: borda branca e depois o vermelho (o que já passou fica cinza)
        val g = Navegacao.guia
        if (rotaVbo != 0) {
            val ox = ((rotaOx - camX) / 10).toFloat(); val oy = ((rotaOy - camY) / 10).toFloat()
            uni(progLinha, "uOrigem") { GLES20.glUniform2f(it, ox, oy) }
            val feito = if (g != null && g.rota === rotaAtual) g.feitoM.toFloat() else -1f
            uni(progLinha, "uProgresso") { GLES20.glUniform1f(it, feito) }
            uni(progLinha, "uFeito") { GLES20.glUniform4f(it, 0f, 0f, 0f, 0f) }
            linhas(rotaVbo, rotaN, 7.5f, (7.5 * escalaTela * mpp).toFloat(), 0xF1, 0xF2, 0xF1)
            uni(progLinha, "uFeito") { GLES20.glUniform4f(it, 0.353f, 0.369f, 0.357f, 1f) }
            linhas(rotaVbo, rotaN, 5f, (5 * escalaTela * mpp).toFloat(), 0xCE, 0x2B, 0x37)
        }

        // prédios (com profundidade)
        if (alturaPredios > 0.0 || inclinacao < 30) {
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glUseProgram(progPredio)
            uni(progPredio, "uMvp") { GLES20.glUniformMatrix4fv(it, 1, false, mvp, 0) }
            uni(progPredio, "uAltura") { GLES20.glUniform1f(it, alturaPredios.toFloat()) }
            uni(progPredio, "uNevoa") { GLES20.glUniform3f(it, carroRelX + tx, carroRelY + ty, nevoa) }
            uni(progPredio, "uChao") { GLES20.glUniform3f(it, 0x15 / 255f, 0x18 / 255f, 0x16 / 255f) }
            val aPos = GLES20.glGetAttribLocation(progPredio, "aPos")
            val aCor = GLES20.glGetAttribLocation(progPredio, "aCor")
            for (pl in naPlaca.values) {
                if (pl.nPredios == 0) continue
                val ox = ((pl.ox - camX) / 10).toFloat(); val oy = ((pl.oy - camY) / 10).toFloat()
                uni(progPredio, "uOrigem") { GLES20.glUniform2f(it, ox, oy) }
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pl.vbo[2])
                GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 16, 0)
                GLES20.glEnableVertexAttribArray(aCor); GLES20.glVertexAttribPointer(aCor, 1, GLES20.GL_FLOAT, false, 16, 12)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, pl.nPredios)
            }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        }

        // seta do carro (na tela, sempre "em pé")
        val pos = floatArrayOf(carroRelX, carroRelY, 0f, 1f)
        val sc = FloatArray(4)
        Matrix.multiplyMV(sc, 0, mvp, 0, pos, 0)
        if (sc[3] > 0) {
            val sx = (sc[0] / sc[3] + 1) / 2 * largura
            val sy = (1 - sc[1] / sc[3]) / 2 * altura
            carroTelaX = sx; carroTelaY = sy
            val rumoCarro = Geo.giro(camRumo, alvoRumo).toFloat()
            seta(sx, sy, rumoCarro, 1.22f * escalaTela, 0xCE, 0x2B, 0x37)
            seta(sx, sy, rumoCarro, 1.0f * escalaTela, 0xF1, 0xF2, 0xF1)
        } else { carroTelaX = -1f; carroTelaY = -1f }

        synchronized(mvpPublica) { System.arraycopy(mvp, 0, mvpPublica, 0, 16); centroPublicoX = camX; centroPublicoY = camY }
        if (kmh > 1.5) mexendo = true
        animando = mexendo
    }

    // ================================================================ peças

    private fun celulasPerto(x: Double, y: Double, raio: Double): Set<Int> {
        val s = HashSet<Int>()
        val c0x = ((x - raio - d.minX) / d.celula).toInt().coerceIn(0, d.gradeL - 1)
        val c1x = ((x + raio - d.minX) / d.celula).toInt().coerceIn(0, d.gradeL - 1)
        val c0y = ((y - raio - d.minY) / d.celula).toInt().coerceIn(0, d.gradeA - 1)
        val c1y = ((y + raio - d.minY) / d.celula).toInt().coerceIn(0, d.gradeA - 1)
        val r2 = (raio + d.celula) * (raio + d.celula)
        for (cy in c0y..c1y) for (cx in c0x..c1x) {
            val mx = d.minX + (cx + 0.5) * d.celula; val my = d.minY + (cy + 0.5) * d.celula
            if ((mx - x) * (mx - x) + (my - y) * (my - y) <= r2) s.add(cy * d.gradeL + cx)
        }
        return s
    }

    private fun pedir(c: Int) {
        val ger = geracao
        cargaH.post {
            if (ger != geracao) { pedidas.remove(c); return@post }
            try {
                val p = montarCelula(c)
                if (ger == geracao) prontas.add(p) else pedidas.remove(c)
            } catch (e: Throwable) {
                pedidas.remove(c)
                Registro.anotar("ERRO mapa: célula $c: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun meiaLargura(classe: Int) = when (classe) {
        MapaDados.MOTORWAY, MapaDados.TRUNK -> 8.5f
        MapaDados.PRIMARY -> 7f
        MapaDados.SECONDARY -> 6f
        MapaDados.TERTIARY -> 5.2f
        MapaDados.LIVING -> 3.2f
        MapaDados.UNCLASSIFIED, MapaDados.RESIDENTIAL -> 4.2f
        else -> 4.2f  // ligações (links)
    }

    /** Monta (fora do GL) as ruas e os prédios de uma célula, em metros a partir do canto da célula. */
    private fun montarCelula(c: Int): Pronta {
        val ox = d.celulaOrigemX(c).toDouble(); val oy = d.celulaOrigemY(c).toDouble()
        val maiores = Lista(); val menores = Lista()
        d.viasNaCelula(c) { via ->
            val p0 = d.viaPrimPonto(via); val n = d.viaNPontos(via)
            val cl = d.viaClasse(via)
            val alvo = if (cl <= MapaDados.TERTIARY || MapaDados.ehLink(cl)) maiores else menores
            val w = meiaLargura(cl)
            for (k in 0 until n - 1) {
                val ax = d.pontoX(p0 + k).toDouble(); val ay = d.pontoY(p0 + k).toDouble()
                val bx = d.pontoX(p0 + k + 1).toDouble(); val by = d.pontoY(p0 + k + 1).toDouble()
                // cada trecho é desenhado só na célula do seu meio (sem repetir)
                if (d.celulaDe((ax + bx) / 2, (ay + by) / 2) != c) continue
                quad(alvo, ((ax - ox) / 10).toFloat(), ((ay - oy) / 10).toFloat(), ((bx - ox) / 10).toFloat(), ((by - oy) / 10).toFloat(), w, 0f, 0f)
            }
        }
        val predios = Lista()
        for (i in d.prediosIni(c) until d.prediosFim(c)) {
            val n = d.predioNPontos(i)
            val h = d.predioAltura(i).toFloat()
            // paredes
            for (k in 0 until n) {
                val ax = ((d.predioX(i, k) - ox) / 10).toFloat(); val ay = ((d.predioY(i, k) - oy) / 10).toFloat()
                val k2 = (k + 1) % n
                val bx = ((d.predioX(i, k2) - ox) / 10).toFloat(); val by = ((d.predioY(i, k2) - oy) / 10).toFloat()
                val l = Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()
                if (l < 0.01f) continue
                // normal para fora (polígono anti-horário): (dy, -dx); luz de noroeste
                val nx = (by - ay) / l; val ny = -(bx - ax) / l
                val luz = (-nx * 0.6f + ny * 0.8f)
                val cor = 0.78f + 0.06f * luz   // entre #B9BCB6 e #D4D6D1
                predios.add(ax, ay, 0f, cor); predios.add(bx, by, 0f, cor); predios.add(bx, by, h, cor)
                predios.add(ax, ay, 0f, cor); predios.add(bx, by, h, cor); predios.add(ax, ay, h, cor)
            }
            // telhado (triângulos que vieram prontos no pacote)
            for (t in 0 until d.predioNTri(i) * 3) {
                val v = d.predioTri(i, t)
                predios.add(((d.predioX(i, v) - ox) / 10).toFloat(), ((d.predioY(i, v) - oy) / 10).toFloat(), h, 0.95f)
            }
        }
        return Pronta(c, ox, oy, maiores.buffer(), maiores.n / 6, menores.buffer(), menores.n / 6, predios.buffer(), predios.n / 4)
    }

    /** Vértices de linha: x, y, ex, ey (para onde "engordar"), largura, distância ao longo. 6 por trecho. */
    private fun quad(l: Lista, ax: Float, ay: Float, bx: Float, by: Float, w: Float, aoA: Float, aoB: Float) {
        val dx = bx - ax; val dy = by - ay
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 0.001f) return
        val ux = dx / len; val uy = dy / len
        val nx = -uy; val ny = ux
        // pontas quadradas (esticam meia largura): emendas sem buraco
        l.add(ax, ay, nx - ux, ny - uy, w, aoA); l.add(ax, ay, -nx - ux, -ny - uy, w, aoA); l.add(bx, by, nx + ux, ny + uy, w, aoB)
        l.add(ax, ay, -nx - ux, -ny - uy, w, aoA); l.add(bx, by, -nx + ux, -ny + uy, w, aoB); l.add(bx, by, nx + ux, ny + uy, w, aoB)
    }

    private fun montarRota(r: Rota): FloatBuffer {
        val l = Lista()
        for (i in 0 until r.n - 1) {
            quad(l, ((r.xs[i] - rotaOx) / 10).toFloat(), ((r.ys[i] - rotaOy) / 10).toFloat(),
                ((r.xs[i + 1] - rotaOx) / 10).toFloat(), ((r.ys[i + 1] - rotaOy) / 10).toFloat(), 1f, r.dist[i].toFloat(), r.dist[i + 1].toFloat())
        }
        return l.buffer() ?: buffer(FloatArray(6))
    }

    private fun linhas(vbo: Int, n: Int, mul: Float, minimo: Float, r: Int, g: Int, b: Int) {
        val p = progLinha
        uni(p, "uMul") { GLES20.glUniform1f(it, mul) }
        uni(p, "uMin") { GLES20.glUniform1f(it, minimo) }
        uni(p, "uCor") { GLES20.glUniform4f(it, r / 255f, g / 255f, b / 255f, 1f) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val aPos = GLES20.glGetAttribLocation(p, "aPos"); val aExt = GLES20.glGetAttribLocation(p, "aExt"); val aW = GLES20.glGetAttribLocation(p, "aW")
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(aExt); GLES20.glVertexAttribPointer(aExt, 2, GLES20.GL_FLOAT, false, 24, 8)
        GLES20.glEnableVertexAttribArray(aW); GLES20.glVertexAttribPointer(aW, 2, GLES20.GL_FLOAT, false, 24, 16)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n)
    }

    private fun seta(x: Float, y: Float, rumo: Float, escala: Float, r: Int, g: Int, b: Int) {
        GLES20.glUseProgram(progTela)
        uni(progTela, "uTela") { GLES20.glUniform2f(it, largura.toFloat(), altura.toFloat()) }
        uni(progTela, "uPos") { GLES20.glUniform2f(it, x, y) }
        uni(progTela, "uGiro") { GLES20.glUniform1f(it, Math.toRadians(rumo.toDouble()).toFloat()) }
        uni(progTela, "uEscala") { GLES20.glUniform1f(it, escala) }
        uni(progTela, "uCor") { GLES20.glUniform4f(it, r / 255f, g / 255f, b / 255f, 1f) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, setaVbo)
        val a = GLES20.glGetAttribLocation(progTela, "aPos")
        GLES20.glEnableVertexAttribArray(a); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    private fun subir(p: Pronta): NaPlaca {
        val v = IntArray(3)
        if (p.maiores != null) v[0] = vbo(p.maiores)
        if (p.menores != null) v[1] = vbo(p.menores)
        if (p.predios != null) v[2] = vbo(p.predios)
        return NaPlaca(p.c, p.ox, p.oy, v, if (p.maiores != null) p.nMaiores else 0, if (p.menores != null) p.nMenores else 0,
            if (p.predios != null) p.nPredios else 0)
    }

    private fun apagar(pl: NaPlaca) {
        val v = pl.vbo.filter { it != 0 }.toIntArray()
        if (v.isNotEmpty()) GLES20.glDeleteBuffers(v.size, v, 0)
    }

    private fun vbo(fb: FloatBuffer): Int {
        val id = IntArray(1)
        GLES20.glGenBuffers(1, id, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id[0])
        fb.position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, fb.limit() * 4, fb, GLES20.GL_STATIC_DRAW)
        return id[0]
    }

    private val locais = HashMap<String, Int>()
    private inline fun uni(p: Int, nome: String, f: (Int) -> Unit) {
        val k = "$p/$nome"
        val loc = locais[k] ?: GLES20.glGetUniformLocation(p, nome).also { locais[k] = it }
        if (loc >= 0) f(loc)
    }

    private fun programa(vs: String, fs: String): Int {
        fun sh(tipo: Int, src: String): Int {
            val s = GLES20.glCreateShader(tipo)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        locais.clear()
        return p
    }

    /** Lista de floats que cresce (sem caixinhas de Float). */
    private class Lista {
        var a = FloatArray(1024); var n = 0
        private fun cabe(k: Int) { if (n + k > a.size) a = a.copyOf(maxOf(a.size * 2, n + k)) }
        fun add(x: Float, y: Float, z: Float, w: Float) { cabe(4); a[n++] = x; a[n++] = y; a[n++] = z; a[n++] = w }
        fun add(x: Float, y: Float, ex: Float, ey: Float, w: Float, ao: Float) {
            cabe(6); a[n++] = x; a[n++] = y; a[n++] = ex; a[n++] = ey; a[n++] = w; a[n++] = ao
        }
        fun buffer(): FloatBuffer? = if (n == 0) null else MapaDesenho.buffer(a, n)
    }

    companion object {
        fun buffer(a: FloatArray, n: Int = a.size): FloatBuffer {
            val fb = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(a, 0, n); fb.position(0)
            return fb
        }

        private const val VS_LINHA = """
            uniform mat4 uMvp; uniform vec2 uOrigem; uniform float uMul; uniform float uMin; uniform vec3 uNevoa;
            attribute vec2 aPos; attribute vec2 aExt; attribute vec2 aW;
            varying float vAo; varying float vLonge;
            void main() {
                float w = max(aW.x * uMul, uMin);
                vec2 p = aPos + uOrigem + aExt * w;
                vAo = aW.y;
                vLonge = clamp((length(p - uNevoa.xy) - uNevoa.z * 0.6) / (uNevoa.z * 0.4), 0.0, 1.0);
                gl_Position = uMvp * vec4(p, 0.0, 1.0);
            }"""
        private const val FS_LINHA = """
            precision mediump float;
            uniform vec4 uCor; uniform vec4 uFeito; uniform float uProgresso; uniform vec3 uChao;
            varying float vAo; varying float vLonge;
            void main() {
                vec4 c = uCor;
                if (vAo < uProgresso) { if (uFeito.a < 0.5) discard; c = uFeito; }
                gl_FragColor = vec4(mix(c.rgb, uChao, vLonge), 1.0);
            }"""
        private const val VS_PREDIO = """
            uniform mat4 uMvp; uniform vec2 uOrigem; uniform float uAltura; uniform vec3 uNevoa;
            attribute vec3 aPos; attribute float aCor;
            varying float vCor; varying float vLonge;
            void main() {
                vec2 p = aPos.xy + uOrigem;
                vCor = aCor;
                vLonge = clamp((length(p - uNevoa.xy) - uNevoa.z * 0.6) / (uNevoa.z * 0.4), 0.0, 1.0);
                gl_Position = uMvp * vec4(p, aPos.z * uAltura, 1.0);
            }"""
        private const val FS_PREDIO = """
            precision mediump float;
            uniform vec3 uChao;
            varying float vCor; varying float vLonge;
            void main() {
                vec3 c = vec3(vCor, vCor * 1.005, vCor * 0.985);
                gl_FragColor = vec4(mix(c, uChao, vLonge), 1.0);
            }"""
        private const val VS_TELA = """
            uniform vec2 uTela; uniform vec2 uPos; uniform float uGiro; uniform float uEscala;
            attribute vec2 aPos;
            void main() {
                float c = cos(uGiro); float s = sin(uGiro);
                vec2 p = vec2(aPos.x * c - aPos.y * s, aPos.x * s + aPos.y * c) * uEscala + uPos;
                gl_Position = vec4(p.x / uTela.x * 2.0 - 1.0, 1.0 - p.y / uTela.y * 2.0, 0.0, 1.0);
            }"""
        private const val FS_TELA = """
            precision mediump float;
            uniform vec4 uCor;
            void main() { gl_FragColor = uCor; }"""
    }
}
