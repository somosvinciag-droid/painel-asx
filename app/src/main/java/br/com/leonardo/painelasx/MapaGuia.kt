package br.com.leonardo.painelasx

/**
 * Acompanha o carro numa rota: onde ele está na rota, quanto falta, quando avisar cada curva
 * (longe / perto / agora) e quando ele saiu do caminho. Sem nada do Android: testado na JVM.
 * [falar] recebe a frase pronta (a Navegacao manda para a voz do Jarvis).
 */
class MapaGuia(val rota: Rota, private val falar: (String) -> Unit) {
    enum class Evento { NORMAL, FORA_DA_ROTA, CHEGOU }

    /** Trecho atual (i -> i+1) e quantos metros já andou na rota. */
    var trecho = 0
        private set
    var feitoM = 0.0
        private set
    /** Ponto do carro "colado" na rota (dm), para desenhar a seta em cima da linha. */
    var coladoX = rota.xs[0]
        private set
    var coladoY = rota.ys[0]
        private set
    var distDaRotaM = 0.0
        private set
    var chegou = false
        private set

    private var foraSeguidas = 0
    private var contraMao = 0
    private val estagio = IntArray(rota.manobras.size)   // 0 nada, 1 longe, 2 perto, 3 agora
    private var proxima = 1
    private var avisouSigaPor = -1

    val restanteM get() = (rota.totalM - feitoM).coerceAtLeast(0.0)
    /** Segundos que faltam (pela velocidade de cada via). */
    val restanteS: Double get() {
        val i = trecho.coerceIn(0, rota.n - 2)
        val l = rota.dist[i + 1] - rota.dist[i]
        val f = if (l > 0) ((feitoM - rota.dist[i]) / l).coerceIn(0.0, 1.0) else 0.0
        val agora = rota.tempo[i] + (rota.tempo[i + 1] - rota.tempo[i]) * f
        return (rota.totalS - agora).coerceAtLeast(0.0)
    }

    /** A próxima manobra a avisar (nunca a de saída), ou null. */
    fun proximaManobra(): Manobra? = rota.manobras.getOrNull(proxima)
    fun distAteProxima(): Double = proximaManobra()?.let { it.distM - feitoM } ?: 0.0

    private fun limiar(precisaoM: Double) = maxOf(35.0, minOf(80.0, precisaoM * 1.5))

    /**
     * Nova posição do GPS. x/y em decímetros (plano do pacote). [rumo]: direção do GPS (graus) ou null.
     */
    fun posicao(x: Double, y: Double, rumo: Double?, kmh: Double, precisaoM: Double = 10.0): Evento {
        if (chegou) return Evento.CHEGOU
        val n = rota.n
        // procura o trecho mais perto: um pouco para trás e até ~400 m para frente
        val ini = maxOf(0, trecho - 3)
        var melhor = Double.MAX_VALUE; var mi = trecho; var mt = 0.0
        var i = ini
        while (i < n - 1) {
            if (rota.dist[i] > feitoM + 400 && i > trecho + 3) break
            val ax = rota.xs[i]; val ay = rota.ys[i]; val bx = rota.xs[i + 1]; val by = rota.ys[i + 1]
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 <= 0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            var d = Math.hypot(x - (ax + dx * t), y - (ay + dy * t)) / 10.0
            // em cruzamentos e ruas paralelas, prefere o trecho que vai no mesmo sentido do carro
            if (rumo != null && kmh > 10 && l2 > 0 && Math.abs(Geo.giro(rumo, Geo.rumo(dx, dy))) > 100) d += 25
            if (d < melhor) { melhor = d; mi = i; mt = t }
            i++
        }
        val dRota = melhor
        distDaRotaM = dRota
        val trechoNovo = mi
        val ax = rota.xs[mi]; val ay = rota.ys[mi]
        val px = ax + (rota.xs[mi + 1] - ax) * mt; val py = ay + (rota.ys[mi + 1] - ay) * mt
        val feitoNovo = rota.dist[mi] + (rota.dist[mi + 1] - rota.dist[mi]) * mt

        // contra-mão (andando ao contrário da rota)
        val rumoTrecho = Geo.rumo(rota.xs[mi + 1] - ax, rota.ys[mi + 1] - ay)
        contraMao = if (rumo != null && kmh > 15 && Math.abs(Geo.giro(rumo, rumoTrecho)) > 135) contraMao + 1 else 0

        if (dRota > limiar(precisaoM) || contraMao >= 4) {
            foraSeguidas++
            if (foraSeguidas >= 3 && kmh > 3) {
                foraSeguidas = 0; contraMao = 0
                return Evento.FORA_DA_ROTA
            }
            return Evento.NORMAL
        }
        foraSeguidas = 0
        // não volta mais de 30 m (o GPS treme parado)
        if (feitoNovo >= feitoM - 30) {
            trecho = trechoNovo; feitoM = maxOf(feitoM, feitoNovo)
            coladoX = px; coladoY = py
        }
        return avisar(kmh)
    }

    private fun avisar(kmh: Double): Evento {
        val ms = rota.manobras
        // passa as manobras que já ficaram para trás
        while (proxima < ms.size - 1 && ms[proxima].distM < feitoM - 5) proxima++
        val m = ms.getOrNull(proxima) ?: return Evento.NORMAL
        val dist = m.distM - feitoM
        val v = maxOf(kmh / 3.6, 5.0)
        val agora = (v * 5).coerceIn(25.0, 150.0)
        val perto = (v * 14).coerceIn(120.0, 600.0)
        val longe = when { v > 19 -> 2000.0; v > 12 -> 1000.0; else -> 0.0 }
        val anterior = ms.getOrNull(proxima - 1)?.distM ?: 0.0

        if (m.tipo == Manobra.Tipo.CHEGADA) {
            if (dist <= maxOf(30.0, v * 3) || restanteM < 25) {
                if (estagio[proxima] < 3) { estagio[proxima] = 3; falar(MapaFrases.falar(m, null)) }
                chegou = true
                return Evento.CHEGOU
            }
            if (dist <= perto && estagio[proxima] < 2 && dist > 60) {
                estagio[proxima] = 2
                falar(MapaFrases.falar(m, dist))
            }
            sigaPor(ms, dist)
            return Evento.NORMAL
        }
        sigaPor(ms, dist)
        val depois = ms.getOrNull(proxima + 1)?.takeIf { it.distM - m.distM < 80 }
        when {
            dist <= agora -> if (estagio[proxima] < 3) {
                estagio[proxima] = 3
                falar(MapaFrases.falar(m, null, depois))
            }
            dist <= perto -> if (estagio[proxima] < 2 && dist > agora + v * 3) {
                estagio[proxima] = 2
                falar(MapaFrases.falar(m, dist, depois))
            }
            longe > 0 && dist <= longe -> if (estagio[proxima] < 1 && dist > perto + v * 10 && m.distM - anterior > longe * 0.8) {
                estagio[proxima] = 1
                falar(MapaFrases.falar(m, dist))
            }
        }
        return Evento.NORMAL
    }

    /** Logo depois de uma curva, se a próxima está longe: "Siga na BR-381 por 25 quilômetros". */
    private fun sigaPor(ms: List<Manobra>, dist: Double) {
        val passou = proxima - 1
        if (passou >= 1 && avisouSigaPor != passou && estagio[passou] == 3 && dist > 3000 && feitoM - ms[passou].distM > 30) {
            avisouSigaPor = passou
            falar(MapaFrases.sigaPor(ms[passou].nome.ifBlank { ms[proxima].nome }, dist))
        }
    }
}
