package br.com.leonardo.painelasx

/**
 * Rotas NOSSAS, sem internet: A* sobre a rede de ruas do pacote (MapaDados), com mão de direção,
 * velocidade por tipo de via e sem meia-volta no meio da rua. Depois monta as manobras (vire, rotatória...).
 * Sem nada do Android: testado na JVM.
 */
class Manobra(
    val ponto: Int,          // índice do ponto da rota
    val distM: Double,       // a quantos metros do início da rota
    val tipo: Tipo,
    val lado: Int = 0,       // -1 esquerda, 1 direita
    val saida: Int = 0,      // rotatória: número da saída
    val nome: String = "",   // rua para onde vai
    val continua: Boolean = false  // "para continuar na ..." (a rua faz a curva)
) {
    enum class Tipo { SAIA, VIRE, LEVE, ACENTUADA, RETORNO, MANTENHA, SAIDA, SIGA, ROTATORIA, PARADA, CHEGADA }
    override fun toString() = "$tipo${if (lado < 0) "-esq" else if (lado > 0) "-dir" else ""}${if (saida > 0) "#$saida" else ""} '$nome' @${distM.toInt()}m"
}

class Rota(
    val xs: DoubleArray, val ys: DoubleArray,   // pontos (decímetros, plano do pacote)
    val dist: DoubleArray,                      // metros acumulados até cada ponto
    val tempo: DoubleArray,                     // segundos acumulados até cada ponto
    val viaDoTrecho: IntArray,                  // via de cada trecho i -> i+1
    val manobras: List<Manobra>,
    val destino: String,
    val destinos: List<Pair<Double, Double>>    // pontos pedidos (dm) que ainda faltam, o último é o destino
) {
    val n get() = xs.size
    val totalM get() = dist[n - 1]
    val totalS get() = tempo[n - 1]
}

class Roteador(private val d: MapaDados) {
    class SemRota(msg: String) : Exception(msg)

    var ultimasVisitas = 0
        private set

    // ------------------------------------------------ pedaço de via
    /** Pontos da via [via] de [de] até [ate] (posições ao longo em dm do plano, no sentido a->b). Inclui as pontas. */
    private fun pedaco(via: Int, de: Double, ate: Double, sx: ArrayList<Double>, sy: ArrayList<Double>) {
        val p0 = d.viaPrimPonto(via); val n = d.viaNPontos(via)
        val ida = ate >= de
        val lo = minOf(de, ate); val hi = maxOf(de, ate)
        val tx = ArrayList<Double>(); val ty = ArrayList<Double>()
        var acum = 0.0
        for (k in 0 until n - 1) {
            val ax = d.pontoX(p0 + k).toDouble(); val ay = d.pontoY(p0 + k).toDouble()
            val bx = d.pontoX(p0 + k + 1).toDouble(); val by = d.pontoY(p0 + k + 1).toDouble()
            val l = Math.hypot(bx - ax, by - ay)
            val f = acum + l
            if (tx.isEmpty() && lo <= f + 1e-6) {
                val t = if (l <= 0) 0.0 else ((lo - acum) / l).coerceIn(0.0, 1.0)
                tx.add(ax + (bx - ax) * t); ty.add(ay + (by - ay) * t)
            }
            if (tx.isNotEmpty()) {
                if (hi <= f + 1e-6) {
                    val t = if (l <= 0) 1.0 else ((hi - acum) / l).coerceIn(0.0, 1.0)
                    tx.add(ax + (bx - ax) * t); ty.add(ay + (by - ay) * t)
                    break
                }
                tx.add(bx); ty.add(by)
            }
            acum = f
        }
        if (tx.isEmpty()) { // via sem comprimento
            tx.add(d.pontoX(p0).toDouble()); ty.add(d.pontoY(p0).toDouble())
        }
        if (!ida) { tx.reverse(); ty.reverse() }
        sx.addAll(tx); sy.addAll(ty)
    }

    // ------------------------------------------------ fila de prioridade simples (com repetição)
    private class Fila {
        var chave = DoubleArray(1024); var no = IntArray(1024); var n = 0
        fun por(k: Double, v: Int) {
            if (n == chave.size) { chave = chave.copyOf(n * 2); no = no.copyOf(n * 2) }
            var i = n++
            while (i > 0) {
                val p = (i - 1) / 2
                if (chave[p] <= k) break
                chave[i] = chave[p]; no[i] = no[p]; i = p
            }
            chave[i] = k; no[i] = v
        }
        fun vazia() = n == 0
        fun topoChave() = chave[0]
        fun tirar(): Int {
            val r = no[0]
            n--
            if (n > 0) {
                val k = chave[n]; val v = no[n]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= n) break
                    if (c + 1 < n && chave[c + 1] < chave[c]) c++
                    if (chave[c] >= k) break
                    chave[i] = chave[c]; no[i] = no[c]; i = c
                }
                chave[i] = k; no[i] = v
            }
            return r
        }
    }

    // ------------------------------------------------ uma perna (origem -> destino)
    private class Inicio(val via: Int, val ida: Boolean, val ao: Double, val no: Int, val custo: Double)
    private class Fim(val via: Int, val ida: Boolean, val ao: Double, val no: Int, val custo: Double)

    private class Perna(val xs: ArrayList<Double>, val ys: ArrayList<Double>, val vias: ArrayList<Int>, val nos: ArrayList<Int>)

    private fun tempoParcial(via: Int, dm: Double, compPlano: Double): Double =
        if (compPlano <= 0) 0.0 else d.viaTempo(via) * (dm / compPlano)

    private fun perna(ox: Double, oy: Double, rumo: Double?, kmh: Double, tx: Double, ty: Double): Perna {
        var perto = d.viasPerto(ox, oy, 600.0)
        if (perto.isEmpty()) perto = d.viasPerto(ox, oy, 3000.0)
        if (perto.isEmpty()) throw SemRota("longe de qualquer rua do mapa")
        var alvo = d.viasPerto(tx, ty, 3000.0)
        if (alvo.isEmpty()) alvo = d.viasPerto(tx, ty, 20000.0)
        if (alvo.isEmpty()) throw SemRota("o destino está longe das ruas do mapa")

        val inicios = ArrayList<Inicio>()
        for (p in perto.take(6)) {
            val fl = d.viaFlags(p.via)
            val longe = p.dist / 10.0 * 0.5  // meio segundo por metro de distância da rua
            for (ida in booleanArrayOf(true, false)) {
                if (ida && fl and MapaDados.F_IDA == 0) continue
                if (!ida && fl and MapaDados.F_VOLTA == 0) continue
                var custo = longe
                if (rumo != null && kmh > 8) {
                    val dir = if (ida) p.rumo else (p.rumo + 180) % 360
                    if (Math.abs(Geo.giro(rumo, dir)) > 100) custo += 90.0
                }
                val resto = if (ida) p.compPlano - p.ao else p.ao
                custo += tempoParcial(p.via, resto, p.compPlano)
                inicios.add(Inicio(p.via, ida, p.ao, if (ida) d.viaB(p.via) else d.viaA(p.via), custo))
            }
        }
        if (inicios.isEmpty()) throw SemRota("nenhuma rua perto permite seguir")
        val fins = ArrayList<Fim>()
        val melhorAlvo = alvo[0].dist
        for (p in alvo.take(4)) {
            if (p.dist > melhorAlvo + 300) continue
            val fl = d.viaFlags(p.via)
            val extra = (p.dist - melhorAlvo) / 10.0 * 0.5
            if (fl and MapaDados.F_IDA != 0) fins.add(Fim(p.via, true, p.ao, d.viaA(p.via), extra + tempoParcial(p.via, p.ao, p.compPlano)))
            if (fl and MapaDados.F_VOLTA != 0) fins.add(Fim(p.via, false, p.ao, d.viaB(p.via), extra + tempoParcial(p.via, p.compPlano - p.ao, p.compPlano)))
        }
        if (fins.isEmpty()) throw SemRota("o destino não tem rua que chegue")

        // Origem e destino na mesma via, no sentido certo: nem precisa de A*.
        var melhor = Double.MAX_VALUE
        var direto: Pair<Inicio, Fim>? = null
        for (i in inicios) for (f in fins) {
            if (i.via == f.via && i.ida == f.ida && ((i.ida && f.ao >= i.ao) || (!i.ida && f.ao <= i.ao))) {
                val pv = perto.first { it.via == i.via }
                val c = tempoParcial(i.via, Math.abs(f.ao - i.ao), pv.compPlano)
                if (c < melhor) { melhor = c; direto = i to f }
            }
        }

        val nN = d.nNos
        val g = DoubleArray(nN) { Double.MAX_VALUE }
        val pai = IntArray(nN) { -1 }        // aresta que chegou (ou -2 - índice do início)
        val viaPai = IntArray(nN) { -1 }
        val fila = Fila()
        // estimativa do que falta (reta a 100 km/h): guia o A* para o lado do destino
        val vMax = 100 / 3.6
        fun h(n: Int) = Math.hypot(d.noX(n) - tx, d.noY(n) - ty) / 10.0 / vMax
        for ((k, i) in inicios.withIndex()) {
            if (i.custo < g[i.no]) {
                g[i.no] = i.custo; pai[i.no] = -2 - k; viaPai[i.no] = i.via
                fila.por(i.custo + h(i.no), i.no)
            }
        }
        val fimDoNo = HashMap<Int, MutableList<Fim>>()
        for (f in fins) fimDoNo.getOrPut(f.no) { ArrayList() }.add(f)
        var melhorFim: Fim? = null
        var visitas = 0
        while (!fila.vazia()) {
            if (fila.topoChave() >= melhor) break
            val k0 = fila.topoChave()
            val u = fila.tirar()
            if (k0 > g[u] + h(u) + 1e-9) continue
            visitas++
            if (visitas > 4_000_000) break
            fimDoNo[u]?.forEach { f ->
                // Chegar no destino pela mesma via em que acabou de vir, voltando: é meia-volta, não vale.
                if (f.via == viaPai[u] && pai[u] >= 0) return@forEach
                val c = g[u] + f.custo
                if (c < melhor) { melhor = c; melhorFim = f; direto = null }
            }
            val ini = d.primeiraAresta(u); val fim = d.fimArestas(u)
            var opcoes = 0
            for (e in ini until fim) if ((d.arestaVia(e) and 0x7fffffff) != viaPai[u]) opcoes++
            for (e in ini until fim) {
                val via = d.arestaVia(e) and 0x7fffffff
                if (via == viaPai[u] && opcoes > 0) continue  // sem meia-volta (só em rua sem saída)
                val v = d.arestaPara(e)
                val c = g[u] + d.viaTempo(via)
                if (c < g[v]) {
                    g[v] = c; pai[v] = e; viaPai[v] = via
                    fila.por(c + h(v), v)
                }
            }
        }
        ultimasVisitas = visitas

        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val vias = ArrayList<Int>(); val nos = ArrayList<Int>()
        fun junta(via: Int, de: Double, ate: Double, noFim: Int) {
            val ax = ArrayList<Double>(); val ay = ArrayList<Double>()
            pedaco(via, de, ate, ax, ay)
            val trechos = ax.size - 1
            if (xs.isEmpty()) { xs.add(ax[0]); ys.add(ay[0]); nos.add(-1) }
            for (k in 1 until ax.size) {
                xs.add(ax[k]); ys.add(ay[k]); vias.add(via)
                nos.add(if (k == ax.size - 1) noFim else -1)
            }
            if (trechos == 0 && noFim >= 0) nos[nos.size - 1] = noFim
        }

        val dir = direto
        if (dir != null) {
            val (i, f) = dir
            junta(i.via, i.ao, f.ao, -1)
        } else {
            val mf = melhorFim ?: throw SemRota("não achei caminho até o destino")
            val arestas = ArrayList<Int>()
            var u = mf.no
            while (pai[u] >= 0) {
                val e = pai[u]
                arestas.add(e)
                val via = d.arestaVia(e) and 0x7fffffff
                u = if (d.arestaVia(e) and MapaDados.INVERTIDA != 0) d.viaB(via) else d.viaA(via)
            }
            val ini = inicios[-2 - pai[u]]
            junta(ini.via, ini.ao, if (ini.ida) d.compPlano(ini.via) else 0.0, ini.no)
            for (e in arestas.asReversed()) {
                val via = d.arestaVia(e) and 0x7fffffff
                val inv = d.arestaVia(e) and MapaDados.INVERTIDA != 0
                val comp = d.compPlano(via)
                junta(via, if (inv) comp else 0.0, if (inv) 0.0 else comp, d.arestaPara(e))
            }
            junta(mf.via, if (mf.ida) 0.0 else d.compPlano(mf.via), mf.ao, -1)
        }
        // o último ponto não é cruzamento
        if (nos.isNotEmpty()) nos[nos.size - 1] = -1
        return Perna(xs, ys, vias, nos)
    }

    /**
     * Rota de (ox, oy) passando pelos [destinos] (dm). [rumo]/[kmh] do GPS ajudam a escolher o lado da rua.
     * [nomes]: nome de cada destino (paradas e chegada).
     */
    fun rota(ox: Double, oy: Double, rumo: Double?, kmh: Double, destinos: List<Pair<Double, Double>>, nomes: List<String>): Rota {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val vias = ArrayList<Int>(); val nos = ArrayList<Int>()
        val paradas = ArrayList<Int>()
        var cx = ox; var cy = oy; var r = rumo; var v = kmh
        for ((k, alvo) in destinos.withIndex()) {
            val p = perna(cx, cy, r, v, alvo.first, alvo.second)
            if (xs.isNotEmpty()) {
                // emenda: o primeiro ponto da perna nova repete o último
                for (i in 1 until p.xs.size) { xs.add(p.xs[i]); ys.add(p.ys[i]) }
                vias.addAll(p.vias)
                nos[nos.size - 1] = -1
                for (i in 1 until p.nos.size) nos.add(p.nos[i])
            } else {
                xs.addAll(p.xs); ys.addAll(p.ys); vias.addAll(p.vias); nos.addAll(p.nos)
            }
            paradas.add(xs.size - 1)
            if (k < destinos.size - 1) {
                val n = xs.size
                if (n >= 2) r = Geo.rumo(xs[n - 1] - xs[n - 2], ys[n - 1] - ys[n - 2])
                cx = xs[n - 1]; cy = ys[n - 1]; v = 30.0
            }
        }
        // pontos repetidos (pernas que se encostam) atrapalham os rumos: tira
        val n = xs.size
        val dist = DoubleArray(n); val tempo = DoubleArray(n)
        val viaT = IntArray(maxOf(0, n - 1)) { if (it < vias.size) vias[it] else vias.lastOrNull() ?: 0 }
        for (i in 1 until n) {
            val via = viaT[i - 1]
            val pl = Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) / 10.0
            val compPl = planoM(via)
            val f = if (compPl > 0) (d.viaComp(via) / 10.0) / compPl else 1.0
            val m = pl * f
            dist[i] = dist[i - 1] + m
            tempo[i] = tempo[i - 1] + m / (d.viaVel(via).coerceAtLeast(5) / 3.6)
        }
        val rotaX = xs.toDoubleArray(); val rotaY = ys.toDoubleArray()
        val manobras = Manobras(d).montar(rotaX, rotaY, dist, viaT, nos.toIntArray(), paradas, nomes)
        return Rota(rotaX, rotaY, dist, tempo, viaT, manobras, nomes.lastOrNull() ?: "destino", destinos)
    }

    private fun planoM(via: Int): Double = d.compPlano(via) / 10.0
}

/** As manobras de uma rota (onde avisar e o quê). */
class Manobras(private val d: MapaDados) {
    /** Nome da via; "BR-460;MG-456" (duas rodovias juntas) vira "BR-460 / MG-456". */
    private fun nomeVia(v: Int) = d.nome(d.viaNome(v)).replace(Regex("\\s*;\\s*"), " / ")

    /** Rumo de saída de [no] pela via [via] (no sentido que sai do nó), olhando ~20 m. */
    private fun rumoSaida(no: Int, via: Int): Double {
        val p0 = d.viaPrimPonto(via); val n = d.viaNPontos(via)
        val doA = d.viaA(via) == no
        val ox = d.noX(no).toDouble(); val oy = d.noY(no).toDouble()
        for (k in 1 until n) {
            val i = if (doA) k else n - 1 - k
            val x = d.pontoX(p0 + i).toDouble(); val y = d.pontoY(p0 + i).toDouble()
            if (Math.hypot(x - ox, y - oy) >= 200 || k == n - 1) return Geo.rumo(x - ox, y - oy)
        }
        return 0.0
    }

    private fun rumoAntes(xs: DoubleArray, ys: DoubleArray, k: Int): Double {
        var j = k - 1
        while (j > 0 && Math.hypot(xs[k] - xs[j], ys[k] - ys[j]) < 200) j--
        return Geo.rumo(xs[k] - xs[j], ys[k] - ys[j])
    }

    fun montar(xs: DoubleArray, ys: DoubleArray, dist: DoubleArray, vias: IntArray, nos: IntArray,
               paradas: List<Int>, nomes: List<String>): List<Manobra> {
        val r = ArrayList<Manobra>()
        if (xs.size < 2) {
            r.add(Manobra(0, 0.0, Manobra.Tipo.CHEGADA, nome = nomes.lastOrNull() ?: ""))
            return r
        }
        r.add(Manobra(0, 0.0, Manobra.Tipo.SAIA, nome = nomeVia(vias[0])))
        val ultimo = xs.size - 1
        for (k in 1 until ultimo) {
            val pi = paradas.indexOf(k)
            if (pi >= 0 && pi < paradas.size - 1) {
                r.add(Manobra(k, dist[k], Manobra.Tipo.PARADA, nome = nomes.getOrElse(pi) { "" }))
                continue
            }
            val no = nos.getOrElse(k) { -1 }
            if (no < 0) continue
            val vIn = vias[k - 1]; val vOut = vias[k]
            if (vIn == vOut) continue
            val rotIn = d.rotatoria(vIn); val rotOut = d.rotatoria(vOut)
            if (rotIn) continue  // dentro da rotatória: já foi avisado na entrada
            val rIn = rumoAntes(xs, ys, k)
            val rOut = rumoSaida(no, vOut)
            // opções de saída neste cruzamento (sem a meia-volta)
            val opcoes = ArrayList<Pair<Int, Double>>()
            for (e in d.primeiraAresta(no) until d.fimArestas(no)) {
                val via = d.arestaVia(e) and 0x7fffffff
                if (via == vIn) continue
                opcoes.add(via to Geo.giro(rIn, rumoSaida(no, via)))
            }
            if (rotOut) {
                var saida = 0
                var j = k + 1
                var nomeSaida = ""
                while (j < ultimo) {
                    val nj = nos.getOrElse(j) { -1 }
                    if (nj >= 0) {
                        val sai = !d.rotatoria(vias[j])
                        var temSaida = false
                        for (e in d.primeiraAresta(nj) until d.fimArestas(nj)) {
                            val via = d.arestaVia(e) and 0x7fffffff
                            if (!d.rotatoria(via)) { temSaida = true; break }
                        }
                        if (temSaida || sai) saida++
                        if (sai) { nomeSaida = nomeVia(vias[j]); break }
                    }
                    j++
                }
                if (saida == 0) saida = 1
                r.add(Manobra(k, dist[k], Manobra.Tipo.ROTATORIA, saida = saida, nome = nomeSaida))
                continue
            }
            val a = Geo.giro(rIn, rOut)
            val nIn = nomeVia(vIn); val nOut = nomeVia(vOut)
            val lado = if (a >= 0) 1 else -1
            val abs = Math.abs(a)
            val linkOut = MapaDados.ehLink(d.viaClasse(vOut)) && !MapaDados.ehLink(d.viaClasse(vIn))
            val continua = nOut.isNotBlank() && nOut == nIn
            if (opcoes.size <= 1) {
                // sem escolha: só a rua que muda de nome ou faz uma curva forte não precisa de aviso
                continue
            }
            // outra saída parecida com a nossa (bifurcação): "mantenha-se"
            val outrasPerto = opcoes.filter { it.first != vOut && Math.abs(it.second - a) < 45 }
            val ladoGarfo = if (outrasPerto.any { it.second > a }) -1 else 1
            val tipo: Manobra.Tipo? = when {
                abs >= 160 -> Manobra.Tipo.RETORNO
                abs >= 125 -> Manobra.Tipo.ACENTUADA
                abs >= 55 -> Manobra.Tipo.VIRE
                // na mesma rua, quase reto: a rua só segue (não fala "mantenha-se" a cada saída lateral)
                outrasPerto.isNotEmpty() && !(continua && abs < 20) -> {
                    r.add(Manobra(k, dist[k], if (linkOut) Manobra.Tipo.SAIDA else Manobra.Tipo.MANTENHA, lado = ladoGarfo, nome = nOut))
                    null
                }
                abs >= 30 && !continua && outrasPerto.isEmpty() -> if (linkOut) Manobra.Tipo.SAIDA else Manobra.Tipo.LEVE
                // "siga em frente" só ao entrar numa rodovia (nas ruas da cidade é falação)
                abs < 30 && nOut != nIn && MapaFrases.ehRodovia(nOut) && !MapaFrases.ehRodovia(nIn) -> Manobra.Tipo.SIGA
                else -> null
            }
            if (tipo != null) {
                r.add(Manobra(k, dist[k], tipo, lado = if (tipo == Manobra.Tipo.SIGA || tipo == Manobra.Tipo.RETORNO) 0 else lado,
                    nome = nOut, continua = continua && tipo != Manobra.Tipo.SIGA))
            }
        }
        r.add(Manobra(ultimo, dist[ultimo], Manobra.Tipo.CHEGADA, nome = nomes.lastOrNull() ?: ""))
        return juntarPerto(r)
    }

    /** Duas manobras no mesmo lugar (< 25 m): fica a mais forte ("vire" ganha de "mantenha-se"/"siga"). */
    private fun juntarPerto(l: List<Manobra>): List<Manobra> {
        val fracas = setOf(Manobra.Tipo.MANTENHA, Manobra.Tipo.SIGA, Manobra.Tipo.LEVE, Manobra.Tipo.SAIDA)
        val r = ArrayList<Manobra>()
        for (m in l) {
            val ant = r.lastOrNull()
            if (ant != null && ant.tipo != Manobra.Tipo.SAIA && m.tipo != Manobra.Tipo.CHEGADA && m.tipo != Manobra.Tipo.PARADA &&
                ant.tipo != Manobra.Tipo.PARADA && m.distM - ant.distM < 25) {
                if (ant.tipo in fracas && m.tipo !in fracas) { r[r.size - 1] = m; continue }
                if (m.tipo in fracas) continue
            }
            r.add(m)
        }
        return r
    }
}
