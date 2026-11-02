package br.com.leonardo.painelasx

import java.text.Normalizer

/**
 * Corrige os erros mais comuns do ouvido próprio (Whisper) ANTES de mandar a frase para os Comandos.
 * Só mexe no que tem certeza; o resto da frase fica igual.
 *  - "Oto/Pota/Quota/Outra para …" -> "rota para …" (o Whisper quase nunca ouve o "r" do começo);
 *  - nomes de lugares parecidos -> o nome certo ("cachambu" -> Caxambu, "pôs alegre" -> Pouso Alegre, "minerida" -> Mineirita);
 *  - comandos de uma palavra parecidos ("próximo" -> próxima, "pauso" -> pausa…);
 *  - números falados por extenso nos comandos que precisam de número ("limite cem" -> "limite 100").
 * Sem Android aqui dentro (dá para testar no computador).
 */
object Corretor {

    /** Lugares conhecidos (além dos favoritos do Leonardo, que o Ouvido passa). */
    val LUGARES = listOf(
        "casa", "Mineirita", "família", "Caxambu", "Pouso Alegre", "Poços de Caldas", "Baependi", "São Lourenço",
        "Cruzília", "Varginha", "Três Corações", "Lambari", "Itajubá", "Conceição do Rio Verde", "Soledade de Minas",
        "Aiuruoca", "Carmo de Minas", "Cambuquira", "Itanhandu", "Passa Quatro", "Pouso Alto", "São Pedro",
        "São Paulo", "Belo Horizonte", "Campinas", "Santa Rita do Sapucaí", "Itamonte", "Alagoa", "Andrelândia"
    )

    /** Como o Whisper escreve "rota" no começo da frase (ele quase nunca ouve o "r"). */
    private val ROTA_FORTES = setOf(
        "rota", "rotas", "oto", "ota", "hota", "hoto", "quota", "cota", "pota", "poto", "vota", "voto",
        "frota", "fota", "rotta", "ruta", "otra", "gota", "rolta", "rote", "hote", "rode"
    )
    /** Parecidas, mas que também são palavras de outros comandos: só viram "rota" se depois vier um lugar conhecido. */
    private val ROTA_DUVIDOSAS = setOf("outra", "outro", "volta", "roda", "rodo", "hora", "ora", "porta", "bota", "nota", "rato", "foto")
    private val PREPOSICOES = setOf("para", "pra", "pro", "ate", "ao", "p")

    /** Comandos de uma palavra só e como o Whisper às vezes escreve. */
    private val UMA_PALAVRA = mapOf(
        "proxima" to "próxima", "proximo" to "próxima", "proximas" to "próxima", "proxim" to "próxima",
        "anterior" to "anterior", "anteriores" to "anterior",
        "pausa" to "pausa", "pauso" to "pausa", "pausar" to "pausa", "pause" to "pausa", "posa" to "pausa", "pousa" to "pausa", "pauza" to "pausa",
        "continua" to "continua", "continue" to "continua", "continuo" to "continua", "continuar" to "continua",
        "voltar" to "voltar", "volta" to "voltar", "volte" to "voltar",
        "rotina" to "rotina", "rotinas" to "rotina",
        "turbo" to "turbo", "turbos" to "turbo", "turba" to "turbo",
        "backup" to "backup", "bekap" to "backup", "becape" to "backup", "back up" to "backup", "bec up" to "backup",
        "favoritos" to "favoritos", "favorito" to "favoritos",
        "manutencao" to "manutenção", "manutencoes" to "manutenção",
        "silencio" to "silêncio",
        "consumo" to "consumo", "consumos" to "consumo",
        "limite" to "limite", "resumo" to "resumo", "clima" to "clima", "tempo" to "tempo", "apps" to "apps", "aplicativos" to "apps"
    )

    fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun corrigir(bruto: String, favoritos: Collection<String> = emptyList()): String {
        var texto = bruto.trim()
        if (texto.isEmpty()) return texto
        // "Jarvis, …": corrige só o que vem depois do nome.
        var prefixo = ""
        Regex("^\\W*((ok|ei|e aí|e ai|oi)\\W+)?(jarvis|jarbas|jarvas|jervis|jerves|jarves|javis|jarvi|jarviz|jarbes|gervis|jarvez)\\b\\W*(por favor\\W*)?", RegexOption.IGNORE_CASE)
            .find(texto)?.let { m ->
                prefixo = "Jarvis, "
                texto = texto.substring(m.range.last + 1).trim()
            }
        if (texto.isEmpty()) return bruto.trim()
        texto = texto.trimEnd('.', '!', '?', '…', ' ')
        var n = normalizar(texto)

        // 1) comando de uma palavra
        UMA_PALAVRA[n]?.let { return prefixo + it }

        // 1b) erros de junção comuns: "A nota que…" -> "anota que…"; "Né avise em…" -> "me avisa em…"
        texto = Regex("^a nota(?= (que|a[ií]|isso|de|pra mim)\\b)", RegexOption.IGNORE_CASE).replace(texto, "anota")
        Regex("^(n[eé]|me|mi|nem|e)\\s+(a\\s?vise|avisa|avise)\\b", RegexOption.IGNORE_CASE).find(texto)?.let { m ->
            texto = "me avisa" + texto.substring(m.range.last + 1)
        }

        // 1c) frase curta parecida com um comando fixo ("Ele inicial" -> "tela inicial", "Posso mais grave" -> "voz mais grave")
        fraseParecida(texto)?.let { return prefixo + it }

        // 2) "Oto para …" -> "rota para …"; "e leva para …" -> "me leva para …"
        val palavras = texto.split(Regex("\\s+")).toMutableList()
        val np = palavras.map { normalizar(it) }
        if (np.size >= 3 && np[1] in PREPOSICOES && np[0] != "rota" &&
            (np[0] in ROTA_FORTES || (np[0] in ROTA_DUVIDOSAS && ehLugar(palavras.drop(2).joinToString(" "), favoritos)))) {
            palavras[0] = "rota"
            if (np[1] == "p") palavras[1] = "para"
            texto = palavras.joinToString(" ")
        } else if (np.size >= 3 && np[0] in setOf("e", "a", "eu", "mi", "nem", "ne", "i", "de", "ni") && np[1] in setOf("leva", "levar", "leve")) {
            palavras[0] = "me"
            texto = palavras.joinToString(" ")
        } else if (np.size >= 2 && np[0] in setOf("nileva", "mileva", "meleva", "eleva", "deleva") && np[1] in PREPOSICOES) {
            palavras[0] = "me leva"
            texto = palavras.joinToString(" ")
        }
        n = normalizar(texto)

        // 3) lugares depois de "rota para", "vamos pra", "me leva para"
        Regex("^(?:(?:me )?(?:faz|faca|traca|coloca|abre|monta) )?(?:uma |a )?(?:rota|navegar|navega|navegacao|me leva|leva|vamos|bora|vamo) (?:embora )?(?:pra|para|ate|ao|a|em|pro|na|no) ")
            .find(n)?.let { m ->
                // acha o mesmo ponto no texto original (conta as palavras)
                val qtd = m.value.trim().split(" ").size
                val orig = texto.split(Regex("\\s+"))
                if (orig.size > qtd) {
                    val inicio = orig.take(qtd).joinToString(" ")
                    val resto = orig.drop(qtd).joinToString(" ")
                    texto = inicio + " " + corrigirLugares(resto, favoritos)
                }
            }
        n = normalizar(texto)

        // 4) números por extenso nos comandos que precisam de número
        if (Regex("^(muda o |mudar o |coloca o |poe o )?(limite|volume|odometro|quilometragem|hodometro|abasteci|enchi o tanque|coloquei|estacionei|parei|me avisa|me lembra)\\b").containsMatchIn(n)) {
            texto = numerosParaDigitos(texto)
        }
        return prefixo + texto
    }

    // ------------------------------------------------------------------ frases fixas

    /** Comandos sem nada variável (a frase inteira é o comando). Do AJUDA dos Comandos. */
    val FIXAS = listOf(
        "tela inicial", "tela do carro", "lê a tela", "limpar memória", "turbo", "rotina", "backup", "favoritos", "manutenção",
        "voz mais grave", "voz mais fina", "fala mais devagar", "fala mais rápido", "voz normal",
        "troquei o óleo", "calibrei os pneus", "qual o consumo", "quanto está o consumo",
        "aumenta o volume", "abaixa o volume", "silêncio", "próxima", "anterior", "pausa", "continua", "voltar",
        "para o rádio", "continua a música", "resumo do dia", "resumo da semana", "resumo do mês",
        "abre a câmera", "câmera de ré", "onde está o carro", "como está o tempo", "vai chover",
        "quantos km rodei hoje", "quantos km rodei essa semana", "quantos km rodei esse mês", "todos os apps",
        "abre o spotify", "abre o waze", "abre o maps", "abre o whatsapp", "abre o youtube", "abre o rádio", "abre o telefone"
    )
    /** Começos de comandos com parte variável (nome de música, lugar, número…): nestes a frase inteira não é trocada. */
    private val COMECOS_LIVRES = Regex("^(toca|tocar|toque|coloca|bota|poe|quero ouvir|rota|navega|me leva|vamos|anota|anotar|nota|pedido|" +
        "abasteci|enchi|coloquei|salva|salvar|guarda|me avisa|me lembra|estacionei|parei|limite|volume|odometro|quilometragem|" +
        "endereco|lembra|registra|clica|aperta|abre|abrir|abra)\\b")

    /** A frase fixa mais parecida, se for bem parecida e sem empate. null = deixa como está. */
    fun fraseParecida(texto: String): String? {
        val n = normalizar(texto)
        val palavras = n.split(" ").filter { it.isNotEmpty() }
        if (palavras.isEmpty() || palavras.size > 5 || n.any { it.isDigit() }) return null
        if (FIXAS.any { normalizar(it) == n }) return null          // já está certo
        val k = som(n)
        if (k.length < 4) return null
        var melhor: String? = null; var r1 = 0.0; var r2 = 0.0
        for (f in FIXAS) {
            val kf = som(normalizar(f))
            val r = 1.0 - distancia(k, kf).toDouble() / maxOf(k.length, kf.length)
            if (r > r1) { r2 = r1; r1 = r; melhor = f } else if (r > r2) r2 = r
        }
        val f = melhor ?: return null
        // As que começam com palavra de comando livre ("toca…", "abre…") precisam ser quase iguais.
        val minimo = if (COMECOS_LIVRES.containsMatchIn(n)) 0.86 else 0.75
        if (r1 < minimo || r1 - r2 < 0.05) return null
        return if (naoApagaPalavraProtegida(palavras, normalizar(f).split(" "))) f else null
    }

    /**
     * Palavras de verdade que lembram as dos comandos fixos, mas querem dizer outra coisa:
     * "troquei o pneu" não é "troquei o óleo"; "fala mais baixo" não é "fala mais rápido".
     */
    private val PROTEGIDAS = setOf(
        "pneu", "pneus", "estepe", "freio", "freios", "filtro", "bateria", "correia", "pastilha", "pastilhas", "vela", "velas",
        "baixo", "baixa", "alto", "alta"
    )

    /** A frase só é trocada se nenhuma das [PROTEGIDAS] for sumir dela. */
    private fun naoApagaPalavraProtegida(ditas: List<String>, fixa: List<String>): Boolean =
        ditas.none { it in PROTEGIDAS && it !in fixa }

    // ------------------------------------------------------------------ lugares

    private fun corrigirLugares(resto: String, favoritos: Collection<String>): String {
        val conhecidos = (favoritos + LUGARES).distinctBy { normalizar(it) }
        // separa as paradas como o Rotas faz (vírgula, " e ", " depois ")
        val partes = resto.split(Regex("\\s*(,|;|\\s+e depois\\s+|\\s+depois\\s+|\\s+e\\s+|\\s+passando por\\s+)\\s*", RegexOption.IGNORE_CASE))
            .filter { it.isNotBlank() }
        if (partes.isEmpty()) return resto
        return partes.joinToString(", ") { p ->
            lugarParecido(p, conhecidos)
                ?: separarLugares(p, conhecidos)?.joinToString(", ")   // "pôs alegre cachambu" (o Whisper comeu a vírgula)
                ?: p
        }
    }

    /** Várias paradas sem vírgula: só aceita se TODAS as palavras formarem lugares conhecidos. */
    private fun separarLugares(parte: String, conhecidos: Collection<String>): List<String>? {
        val w = parte.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (w.size < 2 || w.size > 10) return null
        // melhor[i] = menor lista de lugares que cobre as palavras 0 até i
        val melhor = arrayOfNulls<List<String>>(w.size + 1)
        melhor[0] = emptyList()
        for (i in 1..w.size) {
            for (j in maxOf(0, i - 4) until i) {
                val antes = melhor[j] ?: continue
                val trecho = w.subList(j, i).joinToString(" ")
                val n = normalizar(trecho).replace(Regex("^(a|o|as|os|na|no|da|do|de) "), "")
                val lugar = conhecidos.firstOrNull { normalizar(it) == n } ?: lugarParecido(trecho, conhecidos) ?: continue
                val nova = antes + lugar
                if (melhor[i] == null || nova.size < melhor[i]!!.size) melhor[i] = nova
            }
        }
        return melhor[w.size]?.takeIf { it.size >= 2 }
    }

    /** [dito] é um lugar conhecido (ou parecido com um)? Vale para a primeira parada. */
    private fun ehLugar(dito: String, favoritos: Collection<String>): Boolean {
        val primeiro = dito.split(Regex("\\s*(,|;|\\s+e\\s+|\\s+depois\\s+)\\s*")).firstOrNull()?.trim() ?: return false
        val conhecidos = favoritos + LUGARES
        val n = normalizar(primeiro).replace(Regex("^(a|o|as|os|na|no|da|do|de) "), "")
        return conhecidos.any { normalizar(it) == n } || lugarParecido(primeiro, conhecidos) != null
    }

    /** Como o Whisper escreve alguns lugares que o "som" sozinho não acerta. */
    private val APELIDOS = mapOf(
        "minha lida" to "Mineirita", "minha rita" to "Mineirita", "mineira" to "Mineirita", "minerita" to "Mineirita",
        "minha leita" to "Mineirita", "mina rita" to "Mineirita"
    )

    /** O lugar conhecido mais parecido com [dito], ou null se nenhum for parecido o bastante. */
    fun lugarParecido(dito: String, conhecidos: Collection<String>): String? {
        val semArtigo = normalizar(dito).replace(Regex("^(a|o|as|os|na|no|da|do|de) "), "")
        APELIDOS[semArtigo]?.let { return it }
        if (semArtigo.isEmpty() || semArtigo.any { it.isDigit() }) return null
        val k = som(semArtigo)
        if (k.length < 3) return null
        var melhor: String? = null
        var nota = 0.0
        for (c in conhecidos) {
            val kc = som(normalizar(c))
            if (kc.isEmpty()) continue
            val d = distancia(k, kc)
            val r = 1.0 - d.toDouble() / maxOf(k.length, kc.length)
            if (r > nota) { nota = r; melhor = c }
        }
        if (melhor == null) return null
        if (normalizar(melhor) == semArtigo) return null   // já está certo
        // nomes curtos precisam ser quase iguais
        val minimo = if (som(normalizar(melhor)).length <= 5) 0.8 else 0.7
        return if (nota >= minimo) melhor else null
    }

    /** "Chave de som" em português: junta grafias que soam igual (ch/x, ss/ç/s/z, qu/k/c, lh/li…). */
    fun som(t: String): String {
        var s = normalizar(t).replace(" ", "")
        s = s.replace("ch", "x").replace("sh", "x").replace("ph", "f").replace("lh", "li").replace("nh", "ni")
            .replace(Regex("c(?=[ei])"), "s").replace("qu", "k").replace("gu", "g").replace("c", "k")
            .replace("z", "s").replace("w", "v").replace("y", "i").replace("h", "")
            .replace(Regex("o(?=$)"), "u").replace(Regex("e(?=$)"), "i")
            .replace(Regex("(.)\\1+"), "$1")
        return s
    }

    fun distancia(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var ant = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, ant + if (a[i - 1] == b[j - 1]) 0 else 1)
                ant = t
            }
        }
        return d[b.length]
    }

    // ------------------------------------------------------------------ números

    private val UNIDADES = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6,
        "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "quatorze" to 14,
        "catorze" to 14, "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19,
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60, "setenta" to 70,
        "oitenta" to 80, "noventa" to 90, "cem" to 100, "cento" to 100, "duzentos" to 200, "duzentas" to 200,
        "trezentos" to 300, "trezentas" to 300, "quatrocentos" to 400, "quinhentos" to 500, "seiscentos" to 600,
        "setecentos" to 700, "oitocentos" to 800, "novecentos" to 900
    )

    /** "limite cento e vinte" -> "limite 120"; "abasteci quarenta litros, duzentos e trinta reais" -> "abasteci 40 litros, 230 reais". */
    fun numerosParaDigitos(texto: String): String {
        val ps = texto.split(Regex("\\s+"))
        val saida = ArrayList<String>()
        var i = 0
        while (i < ps.size) {
            val w = normalizar(ps[i])
            if (w in UNIDADES || (w == "mil" && i + 1 < ps.size)) {
                // junta "cento e vinte e três mil quatrocentos e cinquenta e seis"
                var total = 0L; var grupo = 0L; var j = i; var usou = 0; var ultimoNumero = i
                var fimPont = ""
                while (j < ps.size) {
                    val bruto = ps[j]
                    val x = normalizar(bruto)
                    if (x in UNIDADES) { grupo += UNIDADES.getValue(x); usou++; ultimoNumero = j }
                    else if (x == "mil") { total += (if (grupo == 0L) 1 else grupo) * 1000; grupo = 0; usou++; ultimoNumero = j }
                    else if (x == "e" && usou > 0 && j + 1 < ps.size && (normalizar(ps[j + 1]) in UNIDADES)) { j++; continue }
                    else break
                    // vírgula/ponto no fim da palavra encerra o número
                    if (bruto.isNotEmpty() && bruto.last() in ",.;!?" && !(x == "mil" && j + 1 < ps.size && normalizar(ps[j + 1]) in UNIDADES)) {
                        fimPont = bruto.last().toString(); break
                    }
                    j++
                }
                // "um"/"uma" sozinho é artigo, não número
                if (usou == 1 && (w == "um" || w == "uma")) { saida.add(ps[i]); i++; continue }
                saida.add((total + grupo).toString() + fimPont)
                i = ultimoNumero + 1
                continue
            }
            saida.add(ps[i]); i++
        }
        return saida.joinToString(" ")
    }
}
