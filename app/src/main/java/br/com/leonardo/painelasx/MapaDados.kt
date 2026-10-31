package br.com.leonardo.painelasx

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * O pacote do mapa da região (regiao.bin), gerado no GitHub Actions por ferramentas/mapa/gerar_mapa.py.
 * Lido com memória mapeada: o Android carrega do disco só as partes usadas e pode soltá-las quando
 * falta memória (não ocupa o heap do app). Sem nada do Android aqui: dá para testar na JVM.
 *
 * Coordenadas em DECÍMETROS numa projeção plana em volta de (lat0, lon0). Dados © OpenStreetMap (ODbL).
 */
class MapaDados private constructor(private val b: ByteBuffer) {
    companion object {
        const val M = 6371008.8 * Math.PI / 180.0

        // classes de via (iguais às do gerador)
        const val MOTORWAY = 0; const val TRUNK = 1; const val PRIMARY = 2; const val SECONDARY = 3; const val TERTIARY = 4
        const val UNCLASSIFIED = 5; const val RESIDENTIAL = 6; const val LIVING = 7

        const val F_ROTATORIA = 1; const val F_IDA = 2; const val F_VOLTA = 4; const val F_TERRA = 8
        const val INVERTIDA = 0x80000000.toInt()

        fun abrir(f: File): MapaDados {
            RandomAccessFile(f, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return deBuffer(buf)
            }
        }

        fun deBuffer(buf: ByteBuffer): MapaDados {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            for (i in 0 until 8) magic[i] = buf.get(i)
            if (String(magic, Charsets.US_ASCII) != "PASXMAP1") throw IllegalArgumentException("não é um pacote de mapa")
            if (buf.getInt(8) != 1) throw IllegalArgumentException("formato ${buf.getInt(8)} desconhecido")
            val total = buf.getInt(152)
            if (total != buf.capacity()) throw IllegalArgumentException("pacote cortado ($total ≠ ${buf.capacity()})")
            return MapaDados(buf)
        }

        fun ehLink(classe: Int) = classe >= 8
        /** Peso da via para "a rua principal" (menor = mais importante). */
        fun importancia(classe: Int) = when (classe) { 8 -> 0; 9 -> 1; 10 -> 2; 11 -> 3; 12 -> 4; else -> classe }
    }

    val versao = b.getInt(12)
    val lat0 = b.getDouble(16)
    val lon0 = b.getDouble(24)
    private val cos0 = Math.cos(Math.toRadians(lat0))
    val minX = b.getInt(32); val minY = b.getInt(36); val maxX = b.getInt(40); val maxY = b.getInt(44)
    val celula = b.getInt(48); val gradeL = b.getInt(52); val gradeA = b.getInt(56)
    val nNos = b.getInt(60); val nArestas = b.getInt(64); val nVias = b.getInt(68); val nPontos = b.getInt(72)
    val nPredios = b.getInt(76); val nNomes = b.getInt(80); val nLugares = b.getInt(84); val nRuas = b.getInt(88)
    private val oNos = b.getInt(92); private val oPrim = b.getInt(96); private val oArestas = b.getInt(100)
    private val oVias = b.getInt(104); private val oPontos = b.getInt(108); private val oGradeIni = b.getInt(112)
    private val oGradeLista = b.getInt(116); private val oPredCel = b.getInt(120); private val oPredOff = b.getInt(124)
    private val oPredDados = b.getInt(128); private val oNomesOff = b.getInt(132); private val oNomesDados = b.getInt(136)
    private val oLugares = b.getInt(140); private val oRuas = b.getInt(144)

    // ------------------------------------------------ projeção (decímetros)
    fun x(lon: Double) = (lon - lon0) * M * cos0 * 10
    fun y(lat: Double) = (lat - lat0) * M * 10
    fun lon(x: Double) = lon0 + x / (M * cos0 * 10)
    fun lat(y: Double) = lat0 + y / (M * 10)
    fun dentro(x: Double, y: Double) = x >= minX && x <= maxX && y >= minY && y <= maxY

    // ------------------------------------------------ nós e arestas
    fun noX(n: Int) = b.getInt(oNos + n * 8)
    fun noY(n: Int) = b.getInt(oNos + n * 8 + 4)
    fun primeiraAresta(n: Int) = b.getInt(oPrim + n * 4)
    fun fimArestas(n: Int) = b.getInt(oPrim + (n + 1) * 4)
    fun arestaPara(e: Int) = b.getInt(oArestas + e * 8)
    /** Via da aresta, com o bit INVERTIDA se anda de b para a. */
    fun arestaVia(e: Int) = b.getInt(oArestas + e * 8 + 4)

    // ------------------------------------------------ vias
    private fun v(i: Int) = oVias + i * 28
    fun viaA(i: Int) = b.getInt(v(i))
    fun viaB(i: Int) = b.getInt(v(i) + 4)
    fun viaPrimPonto(i: Int) = b.getInt(v(i) + 8)
    /** Comprimento em decímetros (medido na esfera, não no plano). */
    fun viaComp(i: Int) = b.getInt(v(i) + 12)
    fun viaNome(i: Int) = b.getInt(v(i) + 16)
    fun viaNPontos(i: Int) = b.getShort(v(i) + 20).toInt() and 0xffff
    fun viaClasse(i: Int) = b.get(v(i) + 22).toInt() and 0xff
    fun viaVel(i: Int) = b.get(v(i) + 23).toInt() and 0xff
    fun viaFlags(i: Int) = b.get(v(i) + 24).toInt() and 0xff
    fun rotatoria(i: Int) = viaFlags(i) and F_ROTATORIA != 0
    /** Comprimento da via no plano (dm), somando os trechos. */
    fun compPlano(i: Int): Double {
        val p0 = viaPrimPonto(i); val n = viaNPontos(i)
        var s = 0.0
        for (k in 0 until n - 1) s += Math.hypot((pontoX(p0 + k + 1) - pontoX(p0 + k)).toDouble(), (pontoY(p0 + k + 1) - pontoY(p0 + k)).toDouble())
        return s
    }
    fun pontoX(p: Int) = b.getInt(oPontos + p * 8)
    fun pontoY(p: Int) = b.getInt(oPontos + p * 8 + 4)

    /** Tempo da via em segundos. */
    fun viaTempo(i: Int): Double = viaComp(i) / 10.0 / (viaVel(i).coerceAtLeast(5) / 3.6)

    // ------------------------------------------------ grade (células de [celula] dm)
    fun celulaDe(x: Double, y: Double): Int {
        val cx = ((x - minX) / celula).toInt().coerceIn(0, gradeL - 1)
        val cy = ((y - minY) / celula).toInt().coerceIn(0, gradeA - 1)
        return cy * gradeL + cx
    }
    fun celulaX(c: Int) = c % gradeL
    fun celulaY(c: Int) = c / gradeL
    fun celulaOrigemX(c: Int) = minX + celulaX(c) * celula
    fun celulaOrigemY(c: Int) = minY + celulaY(c) * celula

    /** Vias que passam pela célula [c]. */
    inline fun viasNaCelula(c: Int, f: (Int) -> Unit) {
        val ini = gradeIni(c); val fim = gradeIni(c + 1)
        for (k in ini until fim) f(gradeLista(k))
    }
    fun gradeIni(c: Int) = b.getInt(oGradeIni + c * 4)
    fun gradeLista(k: Int) = b.getInt(oGradeLista + k * 4)

    // ------------------------------------------------ prédios (por célula do centro)
    fun prediosIni(c: Int) = b.getInt(oPredCel + c * 4)
    fun prediosFim(c: Int) = b.getInt(oPredCel + (c + 1) * 4)
    private fun pd(i: Int) = oPredDados + b.getInt(oPredOff + i * 4)
    fun predioNPontos(i: Int) = b.getShort(pd(i)).toInt() and 0xffff
    fun predioAltura(i: Int) = b.get(pd(i) + 2).toInt() and 0xff
    fun predioNTri(i: Int) = b.getShort(pd(i) + 4).toInt() and 0xffff
    fun predioX(i: Int, k: Int) = b.getInt(pd(i) + 8 + k * 8)
    fun predioY(i: Int, k: Int) = b.getInt(pd(i) + 12 + k * 8)
    fun predioTri(i: Int, k: Int) = b.getShort(pd(i) + 8 + predioNPontos(i) * 8 + k * 2).toInt() and 0xffff

    // ------------------------------------------------ nomes, lugares e ruas
    private val cacheNomes = HashMap<Int, String>()
    fun nome(i: Int): String {
        if (i < 0 || i >= nNomes) return ""
        synchronized(cacheNomes) { cacheNomes[i]?.let { return it } }
        val ini = b.getInt(oNomesOff + i * 4)
        val fim = b.getInt(oNomesOff + (i + 1) * 4)
        val arr = ByteArray(fim - ini)
        for (k in arr.indices) arr[k] = b.get(oNomesDados + ini + k)
        val s = String(arr, Charsets.UTF_8)
        synchronized(cacheNomes) { if (cacheNomes.size > 4000) cacheNomes.clear(); cacheNomes[i] = s }
        return s
    }
    fun lugarX(i: Int) = b.getInt(oLugares + i * 16)
    fun lugarY(i: Int) = b.getInt(oLugares + i * 16 + 4)
    fun lugarNome(i: Int) = b.getInt(oLugares + i * 16 + 8)
    /** 0 cidade, 1 cidade pequena, 2 vila, 3 bairro, 4 vizinhança, 5 povoado. */
    fun lugarTipo(i: Int) = b.get(oLugares + i * 16 + 12).toInt() and 0xff
    fun ruaNome(i: Int) = b.getInt(oRuas + i * 16)
    fun ruaLugar(i: Int) = b.getInt(oRuas + i * 16 + 4)
    fun ruaX(i: Int) = b.getInt(oRuas + i * 16 + 8)
    fun ruaY(i: Int) = b.getInt(oRuas + i * 16 + 12)

    // ------------------------------------------------ ajuda geométrica

    /** Ponto mais perto numa via: (distância em dm, posição ao longo em dm no plano, x, y). */
    class NaVia(val via: Int, val dist: Double, val ao: Double, val x: Double, val y: Double, val rumo: Double, val compPlano: Double)

    fun projetarNaVia(via: Int, px: Double, py: Double): NaVia {
        val p0 = viaPrimPonto(via); val n = viaNPontos(via)
        var melhor = Double.MAX_VALUE; var ao = 0.0; var bx = 0.0; var by = 0.0; var rumo = 0.0
        var acum = 0.0
        for (k in 0 until n - 1) {
            val ax = pontoX(p0 + k).toDouble(); val ay = pontoY(p0 + k).toDouble()
            val cx = pontoX(p0 + k + 1).toDouble(); val cy = pontoY(p0 + k + 1).toDouble()
            val dx = cx - ax; val dy = cy - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 <= 0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val qx = ax + dx * t; val qy = ay + dy * t
            val d = Math.hypot(px - qx, py - qy)
            val l = Math.sqrt(l2)
            if (d < melhor) { melhor = d; ao = acum + l * t; bx = qx; by = qy; rumo = Geo.rumo(dx, dy) }
            acum += l
        }
        return NaVia(via, melhor, ao, bx, by, rumo, acum)
    }

    /** Vias até [raio] dm do ponto. */
    fun viasPerto(px: Double, py: Double, raio: Double): List<NaVia> {
        val c0x = ((px - raio - minX) / celula).toInt().coerceIn(0, gradeL - 1)
        val c1x = ((px + raio - minX) / celula).toInt().coerceIn(0, gradeL - 1)
        val c0y = ((py - raio - minY) / celula).toInt().coerceIn(0, gradeA - 1)
        val c1y = ((py + raio - minY) / celula).toInt().coerceIn(0, gradeA - 1)
        val vistas = HashSet<Int>()
        val r = ArrayList<NaVia>()
        for (cy in c0y..c1y) for (cx in c0x..c1x) {
            viasNaCelula(cy * gradeL + cx) { via ->
                if (vistas.add(via)) {
                    val p = projetarNaVia(via, px, py)
                    if (p.dist <= raio) r.add(p)
                }
            }
        }
        r.sortBy { it.dist }
        return r
    }
}

/** Contas no plano (x leste, y norte). */
object Geo {
    /** Rumo em graus (0 = norte, 90 = leste), como a bússola e o GPS. */
    fun rumo(dx: Double, dy: Double): Double {
        val r = Math.toDegrees(Math.atan2(dx, dy))
        return if (r < 0) r + 360 else r
    }
    /** Diferença de rumo em (-180, 180]: positivo = para a direita. */
    fun giro(de: Double, para: Double): Double {
        var d = (para - de) % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }
}
