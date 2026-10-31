package br.com.leonardo.painelasx

import java.text.Normalizer

/**
 * Busca de endereço SEM internet no pacote do mapa: cidades, bairros e ruas por cidade.
 * "Rua Major Penha, 359, Centro, Caxambu, MG", "Major Pena em Caxambu", "Pouso Alegre", "Centro".
 * O número da casa é guardado mas a rota vai para a rua (o OSM do Brasil quase não tem números).
 * Sem nada do Android: testado na JVM.
 */
class MapaBusca(private val d: MapaDados) {
    class Achado(val x: Double, val y: Double, val nome: String, val tipo: String, val numero: String?, val nota: Int) {
        override fun toString() = "$tipo '$nome' (${x.toInt()},${y.toInt()}) nota $nota${if (numero != null) " nº $numero" else ""}"
    }

    companion object {
        private val ABREV = mapOf(
            "r" to "rua", "av" to "avenida", "avn" to "avenida", "pca" to "praca", "pc" to "praca", "tv" to "travessa",
            "trav" to "travessa", "rod" to "rodovia", "estr" to "estrada", "est" to "estrada", "al" to "alameda",
            "dr" to "doutor", "dra" to "doutora", "cel" to "coronel", "maj" to "major", "cap" to "capitao", "ten" to "tenente",
            "sgt" to "sargento", "gen" to "general", "gal" to "general", "pe" to "padre", "pres" to "presidente",
            "sen" to "senador", "dep" to "deputado", "ver" to "vereador", "prof" to "professor", "profa" to "professora",
            "eng" to "engenheiro", "sta" to "santa", "sto" to "santo", "n" to "nossa", "sra" to "senhora", "mal" to "marechal",
            "brig" to "brigadeiro", "com" to "comendador", "cons" to "conselheiro", "des" to "desembargador"
        )
        val TIPOS = setOf("rua", "avenida", "travessa", "praca", "rodovia", "estrada", "alameda", "largo", "beco", "via",
            "viela", "ladeira", "rotatoria", "acesso", "servidao", "vila", "contorno", "anel")
        private val LIGA = setOf("de", "da", "do", "das", "dos", "e")
        private val ESTADOS = setOf("mg", "sp", "rj", "es", "minas gerais", "brasil", "br", "minas")

        fun normalizar(s: String): String {
            val semAcento = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val t = semAcento.replace(Regex("[^a-z0-9]+"), " ").trim()
            if (t.isEmpty()) return t
            return t.split(' ').joinToString(" ") { ABREV[it] ?: it }
        }

        /** Sem o tipo da rua ("rua", "avenida"...) e sem "de/da/do". */
        fun nucleo(n: String): List<String> {
            val p = n.split(' ').filter { it.isNotEmpty() }
            val sem = if (p.size > 1 && p[0] in TIPOS) p.drop(1) else p
            return sem.filter { it !in LIGA }
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val c = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + c)
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            return prev[b.length]
        }

        private fun palavraIgual(a: String, b: String): Boolean {
            if (a == b) return true
            val mx = maxOf(a.length, b.length)
            if (mx < 4) return false
            if (Math.abs(a.length - b.length) > 1) return false
            return levenshtein(a, b) <= (if (mx >= 8) 2 else 1)
        }

        /** Nota de 0 a 100 para o nome [nome] (normalizado) contra o pedido [q] (normalizado). */
        fun nota(q: String, nome: String): Int {
            if (q == nome) return 100
            val nq = nucleo(q); val nn = nucleo(nome)
            if (nq.isEmpty() || nn.isEmpty()) return 0
            if (nq == nn) {
                // mesmo nome com outro tipo ("Rua X" x "Avenida X") vale menos que o tipo certo
                val tq = q.substringBefore(' '); val tn = nome.substringBefore(' ')
                return if (tq in TIPOS && tn in TIPOS && tq != tn) 75 else 92
            }
            // palavra igual vale 1; parecida ("pena" ~ "penha") vale 0,8
            var achou = 0.0; var palavras = 0
            for (w in nq) {
                if (nn.contains(w)) { achou += 1.0; palavras++ }
                else if (nn.any { palavraIgual(w, it) }) { achou += 0.8; palavras++ }
            }
            if (palavras == 0) return 0
            val cobreQ = achou / nq.size
            val cobreN = achou / nn.size
            if (palavras < nq.size && palavras < 2) return 0
            return (cobreQ * 55 + cobreN * 30).toInt()
        }
    }

    private val nomesLugares: Array<String> by lazy { Array(d.nLugares) { normalizar(d.nome(d.lugarNome(it))) } }
    private val nomesRuas: Array<String> by lazy { Array(d.nRuas) { normalizar(d.nome(d.ruaNome(it))) } }

    /** Prepara os nomes (a 1ª busca fica rápida). */
    fun aquecer() { nomesLugares.size; nomesRuas.size }

    private fun ehCidade(i: Int) = d.lugarTipo(i) <= 2

    /** A cidade com esse nome (a maior, se houver várias; senão a mais perto de [px],[py]). */
    private fun cidade(q: String, px: Double?, py: Double?): Int {
        var melhor = -1; var mt = Int.MAX_VALUE; var md = Double.MAX_VALUE
        for (i in 0 until d.nLugares) {
            if (!ehCidade(i) || nomesLugares[i] != q) continue
            val t = d.lugarTipo(i)
            val dd = if (px != null && py != null) Math.hypot(d.lugarX(i) - px, d.lugarY(i) - py) else 0.0
            if (t < mt || (t == mt && dd < md)) { melhor = i; mt = t; md = dd }
        }
        return melhor
    }

    fun buscar(texto: String, pertoX: Double? = null, pertoY: Double? = null): Achado? {
        var partes = texto.split(',', ';').map { normalizar(it) }.filter { it.isNotEmpty() }.toMutableList()
        if (partes.isEmpty()) return null
        // "MG", "Brasil" no fim
        while (partes.size > 1 && partes.last() in ESTADOS) partes.removeAt(partes.size - 1)
        // número da casa
        var numero: String? = null
        partes = partes.mapNotNull { p ->
            if (p.matches(Regex("\\d+[a-z]?"))) { numero = p; null }
            else {
                val m = Regex("^(.*\\D)\\s(\\d{1,5})$").find(p)
                if (m != null && m.groupValues[1].split(' ').firstOrNull() in TIPOS) { numero = m.groupValues[2]; m.groupValues[1].trim() } else p
            }
        }.toMutableList()
        if (partes.isEmpty()) return null

        // cidade: uma parte inteira, de trás para frente
        var cid = -1
        for (k in partes.indices.reversed()) {
            val c = cidade(partes[k], pertoX, pertoY)
            if (c >= 0 && (partes.size > 1 || nucleo(partes[k]).isNotEmpty())) {
                // "Rua Caxambu" não é a cidade: só se não parecer rua
                if (partes[k].substringBefore(' ') in TIPOS) continue
                cid = c; partes.removeAt(k); break
            }
        }
        // sem vírgula: "rua major penha em caxambu" / "rua major penha caxambu"
        if (cid < 0 && partes.size == 1) {
            val p = partes[0]
            val pal = p.split(' ')
            for (corte in 1 until pal.size) {
                val fimTxt = pal.drop(corte).joinToString(" ")
                val c = cidade(fimTxt, pertoX, pertoY)
                if (c >= 0) {
                    var ini = pal.take(corte)
                    if (ini.lastOrNull() in setOf("em", "de", "no", "na")) ini = ini.dropLast(1)
                    if (ini.isNotEmpty()) { cid = c; partes[0] = ini.joinToString(" ") }
                    break
                }
            }
        }
        val cx = if (cid >= 0) d.lugarX(cid).toDouble() else pertoX
        val cy = if (cid >= 0) d.lugarY(cid).toDouble() else pertoY

        if (partes.isEmpty()) {
            if (cid < 0) return null
            return Achado(d.lugarX(cid).toDouble(), d.lugarY(cid).toDouble(), d.nome(d.lugarNome(cid)), "cidade", numero, 100)
        }

        // rua: a primeira parte que parece rua; senão a primeira
        val rua = partes.firstOrNull { it.substringBefore(' ') in TIPOS } ?: partes[0]
        var melhor: Achado? = null
        var melhorChave = Double.NEGATIVE_INFINITY
        for (i in 0 until d.nRuas) {
            val nt = nota(rua, nomesRuas[i])
            if (nt < 60) continue
            val naCidade = cid >= 0 && d.ruaLugar(i) == cid
            val dist = if (cx != null && cy != null) Math.hypot(d.ruaX(i) - cx, d.ruaY(i) - cy) / 10.0 else 0.0
            if (cid >= 0 && !naCidade && dist > 15_000) continue
            if (cid < 0 && dist > 30_000) continue   // sem cidade: rua até 30 km do carro
            // nota manda; depois "na cidade pedida"; depois a mais perto
            val chave = nt * 1000.0 + (if (naCidade) 500 else 0) - minOf(dist / 100.0, 499.0)
            if (chave > melhorChave) {
                melhorChave = chave
                melhor = Achado(d.ruaX(i).toDouble(), d.ruaY(i).toDouble(), d.nome(d.ruaNome(i)), "rua", numero, nt)
            }
        }
        // bairro / povoado com esse nome (perto da cidade ou do carro)
        var bairro: Achado? = null
        var bd = Double.MAX_VALUE
        for (i in 0 until d.nLugares) {
            if (nomesLugares[i] != rua) continue
            val dist = if (cx != null && cy != null) Math.hypot(d.lugarX(i) - cx, d.lugarY(i) - cy) else 0.0
            if (!ehCidade(i) && dist > 150_000) continue   // bairro com esse nome só até 15 km
            if (dist < bd) { bd = dist; bairro = Achado(d.lugarX(i).toDouble(), d.lugarY(i).toDouble(), d.nome(d.lugarNome(i)), if (ehCidade(i)) "cidade" else "bairro", numero, 100) }
        }
        val m = melhor
        if (bairro != null && (m == null || m.nota < 92 || rua.substringBefore(' ') !in TIPOS)) return bairro
        if (m != null) return m
        // não achou a rua, mas achou a cidade: vai para a cidade
        if (cid >= 0) return Achado(d.lugarX(cid).toDouble(), d.lugarY(cid).toDouble(), d.nome(d.lugarNome(cid)), "cidade", numero, 50)
        return null
    }
}
