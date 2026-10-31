package br.com.leonardo.painelasx

import java.util.Locale

/**
 * As frases do mapa em português do Brasil: o que aparece no cartão da curva e o que o Jarvis fala.
 * Sem nada do Android: testado na JVM.
 */
object MapaFrases {
    private val FEMININO = setOf("rua", "avenida", "av", "av.", "travessa", "praça", "praca", "estrada", "rodovia", "alameda",
        "via", "rotatória", "rotatoria", "ladeira", "servidão", "servidao", "vila", "marginal", "ponte", "passarela", "subida", "descida")
    private val MASCULINO = setOf("largo", "beco", "anel", "acesso", "contorno", "viaduto", "caminho", "trevo", "parque",
        "boulevard", "corredor", "túnel", "tunel", "elevado", "retorno")
    private val SIGLA_RODOVIA = Regex("^[A-Z]{2,3}[- ]?\\d{2,3}")

    private val ORDINAIS = listOf("", "primeira", "segunda", "terceira", "quarta", "quinta", "sexta", "sétima", "oitava", "nona", "décima")

    /** "na"/"no"/"em" conforme o tipo da rua. */
    fun preposicao(nome: String): String {
        if (SIGLA_RODOVIA.containsMatchIn(nome.trim())) return "na"
        val p = nome.trim().substringBefore(' ').lowercase(Locale.ROOT)
        return when (p) {
            in FEMININO -> "na"
            in MASCULINO -> "no"
            else -> "em"
        }
    }

    /** "para a"/"para o"/"para" conforme o tipo da rua. */
    private fun para(nome: String) = when (preposicao(nome)) { "na" -> "para a"; "no" -> "para o"; else -> "para" }

    private fun lado(l: Int) = if (l < 0) "à esquerda" else "à direita"

    /** A ação, sem a rua: "Vire à direita". [falado] usa "segunda" em vez de "2ª". */
    fun acao(m: Manobra, falado: Boolean = false): String = when (m.tipo) {
        Manobra.Tipo.SAIA -> "Siga"
        Manobra.Tipo.VIRE -> "Vire ${lado(m.lado)}"
        Manobra.Tipo.LEVE -> "Vire levemente ${lado(m.lado)}"
        Manobra.Tipo.ACENTUADA -> "Vire acentuadamente ${lado(m.lado)}"
        Manobra.Tipo.RETORNO -> "Faça o retorno"
        Manobra.Tipo.MANTENHA -> "Mantenha-se ${lado(m.lado)}"
        Manobra.Tipo.SAIDA -> "Pegue a saída ${lado(m.lado)}"
        Manobra.Tipo.SIGA -> "Siga em frente"
        Manobra.Tipo.ROTATORIA ->
            if (falado) "Na rotatória, pegue a ${ORDINAIS.getOrElse(m.saida) { "${m.saida}ª" }} saída"
            else "Na rotatória, ${m.saida}ª saída"
        Manobra.Tipo.PARADA -> "Parada"
        Manobra.Tipo.CHEGADA -> "Chegada"
    }

    /** Complemento com a rua: "na Rua Major Penha", "para continuar na BR-381". Vazio se a rua não tem nome. */
    fun rua(m: Manobra): String {
        val n = m.nome.trim()
        if (n.isEmpty()) return ""
        return when (m.tipo) {
            Manobra.Tipo.SAIA -> "${if (preposicao(n) == "no") "pelo" else if (preposicao(n) == "na") "pela" else "por"} $n"
            Manobra.Tipo.ROTATORIA, Manobra.Tipo.SAIDA -> "${para(n)} $n"
            Manobra.Tipo.PARADA, Manobra.Tipo.CHEGADA -> n
            else -> if (m.continua) "para continuar ${preposicao(n)} $n" else "${preposicao(n)} $n"
        }
    }

    /** Para a voz: "MG-158" vira "MG 158" (o motor de voz lê melhor). */
    fun paraFalar(t: String) = t.replace(Regex("([A-Z]{2,3})-(\\d)"), "$1 $2")

    /** "300 metros", "1 quilômetro", "1,5 quilômetro", "12 quilômetros". */
    fun distanciaFalada(m: Double): String {
        if (m < 1000) {
            val r = if (m < 100) maxOf(10, (Math.round(m / 10) * 10).toInt()) else (Math.round(m / 50) * 50).toInt()
            return if (r >= 1000) "1 quilômetro" else "$r metros"
        }
        val km = if (m < 10_000) Math.round(m / 500) * 0.5 else Math.round(m / 1000).toDouble()
        return when {
            km == 1.0 -> "1 quilômetro"
            km % 1.0 == 0.0 -> "${km.toInt()} quilômetros"
            km < 2 -> "${"%.1f".format(Locale("pt", "BR"), km)} quilômetro"
            else -> "${"%.1f".format(Locale("pt", "BR"), km)} quilômetros"
        }
    }

    /** "300 m", "80 m", "1,2 km", "12 km" (cartão e faixa de baixo). */
    fun distanciaTela(m: Double): String = when {
        m < 100 -> "${maxOf(0, (Math.round(m / 10) * 10).toInt())} m"
        m < 1000 -> "${(Math.round(m / 50) * 50).toInt().let { if (it >= 1000) 950 else it }} m"
        m < 10_000 -> "${"%.1f".format(Locale("pt", "BR"), m / 1000)} km"
        else -> "${Math.round(m / 1000)} km"
    }

    /** "1 h 50", "25 min". */
    fun duracaoTela(s: Double): String {
        val min = Math.round(s / 60).toInt()
        return if (min < 60) "${maxOf(1, min)} min" else "${min / 60} h ${"%02d".format(min % 60)}"
    }

    /** "1 hora e 50 minutos", "25 minutos". */
    fun duracaoFalada(s: Double): String {
        val min = Math.round(s / 60).toInt()
        if (min < 1) return "menos de 1 minuto"
        val h = min / 60; val mm = min % 60
        val ph = if (h == 1) "1 hora" else "$h horas"
        val pm = if (mm == 1) "1 minuto" else "$mm minutos"
        return when {
            h == 0 -> pm
            mm == 0 -> ph
            else -> "$ph e $pm"
        }
    }

    /** A frase completa da manobra. [dist] = null: é agora. [depois]: outra manobra logo em seguida. */
    fun falar(m: Manobra, dist: Double?, depois: Manobra? = null): String {
        val em = if (dist != null) "Em ${distanciaFalada(dist)}, " else ""
        val r = rua(m)
        val base = when (m.tipo) {
            Manobra.Tipo.CHEGADA ->
                if (dist != null) "${em}você chega ao destino${if (r.isNotEmpty()) ": $r" else ""}"
                else "Você chegou${if (r.isNotEmpty()) ": $r" else " ao destino"}"
            Manobra.Tipo.PARADA ->
                if (dist != null) "${em}a parada${if (r.isNotEmpty()) ": $r" else ""}"
                else "Chegamos à parada${if (r.isNotEmpty()) ": $r" else ""}"
            else -> {
                val a = acao(m, falado = true)
                val frase = if (em.isNotEmpty()) em + a.replaceFirstChar { it.lowercase(Locale.ROOT) } else a
                if (r.isNotEmpty()) "$frase $r" else frase
            }
        }
        val e = if (depois != null && depois.tipo != Manobra.Tipo.CHEGADA && depois.tipo != Manobra.Tipo.PARADA)
            ", e depois, ${acao(depois, falado = true).replaceFirstChar { it.lowercase(Locale.ROOT) }}" else ""
        return paraFalar("$base$e.")
    }

    /** Ao começar: "Rota para Mineirita: 112 quilômetros, 1 hora e 50 minutos. Siga pela Rua X." */
    fun inicio(r: Rota): String {
        val primeira = r.manobras.firstOrNull()
        val siga = if (primeira != null && primeira.tipo == Manobra.Tipo.SAIA && primeira.nome.isNotBlank()) " Siga ${rua(primeira)}." else ""
        return paraFalar("Rota para ${r.destino}: ${distanciaFalada(r.totalM)}, ${duracaoFalada(r.totalS)}.$siga")
    }

    /** Depois de uma curva, quando a próxima está longe: "Siga na BR-381 por 25 quilômetros." */
    fun sigaPor(nome: String, dist: Double): String {
        val n = nome.trim()
        return paraFalar(if (n.isEmpty()) "Siga por ${distanciaFalada(dist)}." else "Siga ${preposicao(n)} $n por ${distanciaFalada(dist)}.")
    }
}
