package br.com.leonardo.painelasx

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Entende o que o Leonardo falou e faz.
 * Tudo aqui funciona sem internet e sem custo.
 * Devolve a frase que o app responde (falada e escrita).
 */
object Comandos {

    val AJUDA = listOf(
        "\"Jarvis, …\" qualquer pergunta (conversa com o Claude, se a chave estiver no servidor)",
        "\"vamos pra casa\", \"rota para a Mineirita\", \"salva aqui como cliente João\", \"favoritos\"",
        "\"toca a playlist Rock\", \"toca Iron Maiden\", \"toca Iron Man do Black Sabbath\", \"continua a música\"",
        "\"toca a rádio Itatiaia\" (rádio pela internet) / \"para o rádio\"",
        "\"limite 100\" (aviso de velocidade), \"como está o tempo\", \"vai chover?\"",
        "\"abasteci 40 litros, 230 reais\", \"odômetro 123456\", \"troquei o óleo\", \"calibrei os pneus\", \"manutenção\"",
        "\"anota que…\", \"pedido do cliente Fulano: …\" (vai para o seu Claude)",
        "\"estacionei por 2 horas\" / \"me avisa em 30 minutos\" (aviso no celular pelo ntfy)",
        "\"resumo do dia\", \"resumo da semana\", \"tela do carro\", \"backup\"",
        "\"abre a câmera\" / \"câmera de ré\"",
        "\"abre o Spotify\", \"abre o Waze\", \"abre o rádio\"… (qualquer app pelo nome)",
        "\"rota para Pouso Alegre\" (Waze) / \"rota para Caxambu, Pouso Alegre, Caxambu\" (Maps com paradas)",
        "\"volume 50\", \"aumenta o volume\", \"abaixa o volume\", \"silêncio\"",
        "\"próxima\", \"anterior\", \"pausa\", \"continua\"",
        "\"tela inicial\", \"voltar\"",
        "\"toca em <texto do botão>\" (aperta o botão que tem esse texto na tela)",
        "\"rotina\" (roda a rotina de quando o carro liga)",
        "\"turbo\" / \"limpar memória\" (fecha o que está parado e libera RAM)",
        "\"quantos km rodei hoje / essa semana / esse mês\", \"onde está o carro\"",
        "\"lê a tela\" (anota os botões da tela atual no diagnóstico)"
    )

    private val PALAVRAS_CAMERA = listOf("camera", "avm", "dvr", "reverse", "rear", "re")

    fun executar(ctx: Context, falado: String): String {
        val t = Apps.normalizar(falado)
        Registro.anotar("comando: \"$falado\"")
        if (t.isEmpty()) return "Não ouvi nada."
        val ctl = Controle.ativo

        // "Jarvis, ..." -> tira o nome e segue; o que não for comando vira conversa.
        var jarvis = false
        var t2 = t
        var original = falado.trim()
        Regex("^(ok |ei |e ai |oi )?(jarvis|jarbas|jarvas)( por favor)?\\b").find(t2)?.let { m ->
            jarvis = true
            t2 = t2.substring(m.range.last + 1).trim()
            original = original.replace(Regex("^\\W*(ok|ei|e aí|e ai|oi)?\\W*(jarvis|jarbas|jarvas)\\W*(por favor)?\\W*", RegexOption.IGNORE_CASE), "").trim()
            if (t2.isEmpty()) return "Às suas ordens, senhor."
        }
        rapido(ctx, t2, original)?.let { return it }
        val r = executarComandos(ctx, t2, original, ctl)
        if (r != null) return r
        if (Config.jarvisConversa || jarvis) {
            Jarvis.conversar(ctx, original)
            return "Um instante."
        }
        return "Desculpe, senhor, não entendi: \"$falado\"."
    }

    private val NUMEROS = mapOf("uma" to 1, "um" to 1, "duas" to 2, "dois" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5,
        "seis" to 6, "dez" to 10, "quinze" to 15, "vinte" to 20, "trinta" to 30, "quarenta" to 40, "meia" to 30)

    private fun numeroDe(t: String): Int? = t.toIntOrNull() ?: NUMEROS[t]

    /** Comandos novos (v14): lugares, música, rádio, diário, avisos, tempo. null = não é nenhum destes. */
    private fun rapido(ctx: Context, t: String, falado: String): String? {
        // Limite de velocidade
        Regex("^(muda o |mudar o |coloca o |poe o )?limite( de velocidade)?( para| pra| em| de)? (\\d{2,3})").find(t)?.let {
            val v = it.groupValues[4].toInt()
            Config.mudarLimite(ctx, v)
            return "Limite de velocidade: ${Config.limite(ctx)} quilômetros por hora."
        }
        if (t == "limite" || t.contains("qual o limite")) return "O limite está em ${Config.limite(ctx)}. Para mudar, fale: limite 100."

        // Lugares favoritos
        Regex("^(salva|salvar|grava|gravar|guarda|guardar) (aqui|este lugar|esse lugar|o lugar)( como| com o nome)? (.+)$").find(t)?.let {
            return Lugares.salvarAqui(ctx, it.groupValues[4])
        }
        Regex("^(o )?endereco (de |da |do )?(.+?) (e|eh) (.+)$").find(t)?.let {
            val end = Regex("(?i)\\s(é|e)\\s").split(falado, 2).getOrNull(1)?.trim() ?: it.groupValues[5]
            return Lugares.salvar(ctx, it.groupValues[3], end)
        }
        if (t == "favoritos" || t == "meus favoritos" || t.startsWith("quais sao os favoritos")) return Lugares.texto(ctx)

        // Música / rádio
        if (t.startsWith("para o radio") || t.startsWith("desliga o radio") || t == "para a radio" || t.startsWith("desliga a radio")) {
            return if (Musica.pararRadio()) "Rádio desligado." else "O rádio da internet não está tocando."
        }
        Regex("^(toca|tocar|coloca|bota|poe|liga|sintoniza)( a| na)? (radio|radio online) (.+)$").find(t)?.let {
            return Musica.radioOnline(ctx, it.groupValues[4])
        }
        if (Regex("^(continua|continuar|volta|voltar)( a musica| o spotify| de onde parou| a tocar)").containsMatchIn(t) || t == "continua de onde parou") {
            return Musica.continuar(ctx)
        }
        val controleDeMusica = Regex("\\b(volume|rota)\\b").containsMatchIn(t) ||
            Regex("^(toca|coloca|bota|poe)( a| o)? (proxima|anterior|musica|play|de novo|denovo)$").matches(t)
        if (!controleDeMusica) Regex("^(toca|tocar|coloca|bota|poe|quero ouvir|escutar)( a| o| um| uma)? (playlist|lista) (.+?)( no spotify)?$").find(t)?.let {
            return Musica.spotify(ctx, it.groupValues[4], "playlist")
        }
        if (!controleDeMusica) Regex("^(toca|tocar|coloca|bota|poe|quero ouvir)( a| o)? (musica|cancao) (.+?)( do| da| de) (.+?)( no spotify)?$").find(t)?.let {
            return Musica.spotify(ctx, it.groupValues[4], "musica", it.groupValues[6])
        }
        if (!controleDeMusica) Regex("^(toca|tocar|coloca|bota|poe|quero ouvir)( a| o)? (.+?)( do| da| de) (.+?)( no spotify)?$").find(t)?.let {
            val g = it.groupValues[3]
            if (!(g.startsWith("musica") || g.startsWith("som") || g == "algo" || g == "alguma coisa" || g.startsWith("em ") || g.startsWith("no ") || g.startsWith("na "))) return Musica.spotify(ctx, it.groupValues[3], "musica", it.groupValues[5])
        }
        if (!controleDeMusica) Regex("^(toca|tocar|coloca|bota|poe|quero ouvir)( o artista| a banda)? (.+?)( no spotify)?$").find(t)?.let {
            val alvo = it.groupValues[3]
            if (!alvo.matches(Regex("(musica|play|a musica|som)")) && !alvo.startsWith("em ") && !alvo.startsWith("no ") && !alvo.startsWith("na ")) {
                return Musica.spotify(ctx, alvo, if (it.groupValues[2].isNotBlank()) "artista" else "")
            }
        }

        // Tempo
        if (t.contains("vai chover") || t.contains("previsao") || t.contains("como esta o tempo") || t.contains("como ta o tempo") ||
            t == "tempo" || t == "clima" || t.contains("temperatura")) {
            Rede.emSegundoPlano("clima") { Clima.atualizar(ctx) }
            return Clima.texto()
        }

        // Abastecimento e manutenção
        if (t.startsWith("abasteci") || t.startsWith("enchi o tanque") || t.startsWith("coloquei gasolina") || t.startsWith("coloquei etanol")) {
            return Diario.abastecer(ctx, falado)
        }
        Regex("^(o )?(odometro|quilometragem|hodometro)( esta| ta| marca| em| e)?( em)? (\\d[\\d ]*)").find(t)?.let {
            val km = it.groupValues[5].replace(" ", "").toIntOrNull() ?: return "Não entendi o número."
            return Diario.mudarOdometro(ctx, km)
        }
        if (Regex("(troquei|trocamos|fiz|fizemos) (o |a )?(troca (de|do) )?oleo").containsMatchIn(t)) return Diario.servicoFeito(ctx, "oleo")
        if (Regex("(troquei|trocamos|fiz) (o |a )?(troca (de|do) )?(fluido|oleo) (do )?(cambio|cvt)").containsMatchIn(t)) return Diario.servicoFeito(ctx, "cvt")
        if (Regex("(fiz|fizemos) (o |a )?(alinhamento|balanceamento|rodizio)|troquei (os )?pneus").containsMatchIn(t)) return Diario.servicoFeito(ctx, "pneus")
        if (Regex("^(calibrei|calibramos|enchi)( os)? pneus").containsMatchIn(t)) return Diario.calibrou(ctx)
        if (t == "consumo" || t.contains("quanto esta o consumo") || t.contains("qual o consumo") || t.contains("km por litro") || t.contains("quilometros por litro") || t.contains("quanto estou gastando")) {
            val kml = Diario.consumoMedio(ctx) ?: return "Ainda não sei o consumo, senhor. Fale quando abastecer, por exemplo: abasteci 40 litros, 230 reais."
            val ck = Diario.custoPorKm(ctx)
            val br = java.util.Locale("pt", "BR")
            return "O consumo médio do último tanque foi de ${"%.1f".format(br, kml)} quilômetros por litro." +
                (ck?.let { " Cada quilômetro está custando ${"%.2f".format(br, it.first)} reais." } ?: "")
        }
        if (t == "manutencao" || t.contains("proxima revisao") || t.contains("como esta a manutencao") || t.contains("quando troco o oleo")) {
            return Diario.manutencaoTexto(ctx)
        }

        // Anotações e pedidos (vão para o Claude)
        Regex("^pedido( do| da| de)?( cliente)? (.+)$").find(t)?.let {
            val txt = falado.replace(Regex("(?i)^\\s*pedido\\s*"), "").trim()
            return Diario.anotar(ctx, "pedido", txt.ifBlank { it.groupValues[3] })
        }
        Regex("^(anota|anote|anotar|nota|lembra|lembrar|registra)( ai| isso| que| de| pra mim)* (.+)$").find(t)?.let {
            val txt = falado.replace(Regex("(?i)^\\s*(anota|anote|anotar|nota|lembra|lembrar|registra)(\\s+(aí|ai|isso|que|de|pra mim))*\\s*"), "").trim()
            return Diario.anotar(ctx, "nota", txt.ifBlank { it.groupValues[3] })
        }

        // Avisos no celular (estacionamento, lembretes)
        Regex("(estacionei|parei)( aqui)? (por|ate|durante) (\\w+) (minuto|minutos|hora|horas)").find(t)?.let {
            val n = numeroDe(it.groupValues[4]) ?: return "Não entendi o tempo."
            val min = if (it.groupValues[5].startsWith("hora")) n * 60 else n
            return lembrete(ctx, (min - 10).coerceAtLeast(1), "Faltam 10 minutos para acabar o estacionamento ($n ${it.groupValues[5]}).", "Combinado: aviso no celular 10 minutos antes de acabar.")
        }
        Regex("^me (avisa|lembra) (daqui a |em |daqui )(\\w+)( e meia)? (minuto|minutos|hora|horas)( para| pra| que| de)? ?(.*)$").find(t)?.let {
            val n = numeroDe(it.groupValues[3]) ?: return "Não entendi o tempo."
            var min = if (it.groupValues[5].startsWith("hora")) n * 60 else n
            if (it.groupValues[4].isNotBlank()) min += 30
            val oque = it.groupValues[7].ifBlank { "lembrete do carro" }
            return lembrete(ctx, min, oque, "Combinado: aviso no celular em $min minutos.")
        }

        // Resumos
        if (t.startsWith("resumo do dia") || t == "resumo de hoje" || t == "resumo") return resumo(1)
        if (t.startsWith("resumo da semana")) return resumo(7)
        if (t.startsWith("resumo do mes")) return resumo(30)

        // Telas e outros
        if (t == "tela do carro" || t.startsWith("abre a tela do carro") || t == "painel do carro" || t == "tela vermelha") {
            Painel.abrir(ctx); return "Tela do carro."
        }
        if (t == "backup" || t.startsWith("faz o backup") || t.startsWith("fazer backup")) {
            Rede.emSegundoPlano("backup") { val r = Backup.enviar(ctx); Rede.naTela { Voz.falar(r) } }
            return "Fazendo o backup."
        }
        if (t.contains("musica de entrada") || t.contains("musica de boas vindas")) {
            val liga = !(t.contains("desliga") || t.contains("tira") || t.contains("sem"))
            Jarvis.mudarMusicaDeEntrada(ctx, liga)
            return if (liga) "Música de entrada ligada." else "Música de entrada desligada."
        }
        if (Regex("voz (mais )?(grave|grossa)").containsMatchIn(t) && !t.contains("menos")) return VozPropria.ajustar(ctx, -0.04f, 0f)
        if (Regex("voz (mais )?(fina|aguda)|voz menos grave").containsMatchIn(t)) return VozPropria.ajustar(ctx, 0.04f, 0f)
        if (Regex("(fala|falar|fale|voz) (mais )?(rapido|depressa|ligeiro)").containsMatchIn(t)) return VozPropria.ajustar(ctx, 0f, 0.07f)
        if (Regex("(fala|falar|fale|voz) (mais )?(devagar|lento|calmo)").containsMatchIn(t)) return VozPropria.ajustar(ctx, 0f, -0.07f)
        if (t.contains("voz normal") || t.contains("voz padrao")) return VozPropria.ajustar(ctx, 0f, 0f, padrao = true)
        if (t.contains("tela de fabrica") || t.contains("sistema de fabrica")) {
            if (t.startsWith("volta") || t.contains("usar") || t.contains("quero")) {
                Nwd.pedirProp(ctx, Nwd.PROP_LAUNCHER, Nwd.LAUNCHER_FABRICA)
                Apps.abrir(ctx, Nwd.LAUNCHER_FABRICA)
                return "Voltei para a tela de fábrica, senhor. Para me chamar de volta: abra o Painel ASX e toque em Fazer do Painel ASX o sistema do carro."
            }
            Apps.abrir(ctx, Nwd.LAUNCHER_FABRICA); return "Abrindo a tela de fábrica."
        }
        if (t == "apps" || t == "todos os apps" || t.startsWith("mostra os apps")) {
            ctx.startActivity(Intent(ctx, TodosApps::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "Seus apps, senhor."
        }
        if (t.contains("voz do google") || t.contains("voz do android")) {
            VozPropria.mudarLigada(ctx, false); Voz.reiniciar(ctx); return "Usando a voz do Android."
        }
        if (t.contains("voz propria") || t.contains("voz do jarvis")) {
            VozPropria.mudarLigada(ctx, true); return "Voz própria: ${VozPropria.estado}."
        }
        if (t.startsWith("testa a voz") || t.startsWith("teste de voz") || t == "qual voz") return "Estou usando a ${Voz.qualVoz()}."
        if (t == "obrigado" || t == "valeu" || t == "obrigado jarvis") return "Sempre às ordens, senhor."
        if (t == "bom dia" || t == "boa tarde" || t == "boa noite") return "${t.replaceFirstChar { it.uppercase() }}, Leonardo. ${Clima.texto()}"
        return null
    }

    private fun lembrete(ctx: Context, minutos: Int, texto: String, ok: String): String {
        val app = ctx.applicationContext
        Rede.emSegundoPlano("lembrete") {
            val r = try {
                Conector.enviar("/app/lembrete", org.json.JSONObject().put("minutos", minutos).put("texto", texto)).first
            } catch (_: Exception) { 0 }
            if (r != 200) Rede.naTela { Voz.falar("Não consegui marcar o aviso: sem internet agora.") }
        }
        val p = app.getSharedPreferences("config", Context.MODE_PRIVATE)
        return if (p.getBoolean("ntfy_ok", false)) ok
        else "$ok Para o aviso chegar no celular, instale o app ntfy e assine o canal que está no Painel ASX."
    }

    private fun resumo(dias: Int): String {
        val r = Viagens.resumo(dias, comLista = false)
        val periodo = when (dias) { 1 -> "Hoje"; 7 -> "Nos últimos 7 dias"; else -> "Nos últimos $dias dias" }
        return "$periodo: ${r.optInt("viagens")} viagens, ${r.optDouble("km_total")} quilômetros, ${r.optDouble("horas_dirigindo")} horas dirigindo" +
            (if (!r.isNull("nota_media")) ", nota média ${r.optInt("nota_media")}." else ".")
    }

    /** Os comandos de antes (v13). null = não entendeu. */
    private fun executarComandos(ctx: Context, t: String, falado: String, ctl: Controle?): String? {
        // Viagens e onde o carro está
        if (t.contains("onde") && (t.contains("carro") || t.contains("estacionei") || t.contains("parei"))) {
            val o = Viagens.ondeParou()
            return if (o.has("lat")) "O carro está (ou ficou) aqui: ${o.optString("mapa")}" else o.optString("aviso")
        }
        if (t.contains("quantos km") || t.contains("quilometros") || t.contains("viagens")) {
            val dias = when {
                t.contains("hoje") -> 1
                t.contains("mes") -> 30
                else -> 7
            }
            val r = Viagens.resumo(dias, comLista = false)
            return "Nos últimos $dias dia(s): ${r.optInt("viagens")} viagens, ${r.optDouble("km_total")} km, ${r.optDouble("horas_dirigindo")} h dirigindo."
        }

        // Rota no GPS ("rota para Pouso Alegre", "rota para Caxambu, Pouso Alegre, Caxambu")
        val pedidos = Rotas.destinos(falado)
            ?: Regex("^(vamos|bora|me leva|leva|vai|ir|vamo)( embora)? (pra|para|pro|ao|a|na|no) (.+)$").find(t)?.groupValues?.get(4)
                ?.let { n -> if (Lugares.achar(ctx, n) != null) listOf(n) else null }
        if (pedidos != null) {
            val enderecos = pedidos.map { Lugares.achar(ctx, it)?.second?.optString("endereco")?.ifBlank { null } ?: it }
            val ultimo = pedidos.last()
            Carro.definirDestino(Lugares.achar(ctx, ultimo)?.first ?: ultimo, enderecos.last())
            return Rotas.navegar(ctx, enderecos)
        }

        // Camera de re
        if (t.contains("camera") || t.endsWith(" re") || t == "re") {
            val app = Apps.listar(ctx).firstOrNull { a ->
                val n = Apps.normalizar(a.nome).split(" ")
                PALAVRAS_CAMERA.any { n.contains(it) } || Apps.normalizar(a.nome).contains("camera")
            }
            return if (app != null && Apps.abrir(ctx, app.pacote)) "Abrindo ${app.nome}."
            else "Não achei um app de câmera na central. Me manda o diagnóstico."
        }

        // Volume
        if (t.contains("volume") || t == "silencio" || t.contains("mudo")) {
            val numero = Regex("\\d+").find(t)?.value?.toIntOrNull()
            return when {
                numero != null -> { Rotina.ajustarVolume(ctx, numero); "Volume em $numero por cento." }
                t.contains("aument") || t.contains("sobe") || t.contains("mais") -> { Rotina.mudarVolume(ctx, 10); "Aumentei." }
                t.contains("abaix") || t.contains("diminu") || t.contains("menos") -> { Rotina.mudarVolume(ctx, -10); "Abaixei." }
                t.contains("silencio") || t.contains("mudo") || t.contains("zera") -> { Rotina.ajustarVolume(ctx, 0); "Silêncio." }
                else -> "Fala o volume, por exemplo: volume 50."
            }
        }

        // Musica
        when {
            t.startsWith("proxima") || t.startsWith("pula") || t.contains("proxima musica") ->
                { Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_NEXT); return "Próxima." }
            t.startsWith("anterior") || t.contains("musica anterior") || t.contains("volta a musica") ->
                { Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS); return "Anterior." }
            t.startsWith("pausa") || t.startsWith("para a musica") || t.startsWith("pare") ->
                { Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE); return "Pausei." }
            t == "continua" || t == "toca" || t == "play" || t.startsWith("continua a musica") || t == "toca musica" ->
                { Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PLAY); return "Tocando." }
        }

        // Navegar na central (precisa da Acessibilidade)
        if (t.contains("tela inicial") || t == "inicio" || t == "casa" || t == "home") {
            if (ctl?.inicio() == true) return "Tela inicial."
            Nwd.tecla(ctx, Nwd.INICIO)
            return "Tela inicial."
            // Sem o Controle: abre a tela inicial do jeito normal do Android.
            return try {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Tela inicial."
            } catch (e: Exception) { precisaAcessibilidade() }
        }
        if (t == "voltar" || t == "volta") {
            if (ctl?.voltar() != true) Nwd.tecla(ctx, Nwd.VOLTAR)
            return "Voltei."
        }
        if (t.startsWith("le a tela") || t.startsWith("ler a tela") || t.startsWith("leia a tela")) {
            if (ctl == null) return precisaAcessibilidade()
            Registro.anotar("leitura da tela:\n" + ctl.lerTela())
            return "Anotei os botões desta tela no diagnóstico."
        }
        (Regex("^(toca|toque) (em |no |na )(.+)$").find(t) ?: Regex("^(clica|clique|aperta|aperte) (em |no |na )?(.+)$").find(t))?.let { m ->
            if (ctl == null) return precisaAcessibilidade()
            val alvo = m.groupValues[3]
            val original = falado.trim().split(Regex("\\s+")).takeLast(alvo.split(" ").size).joinToString(" ")
            return if (ctl.clicarTexto(listOf(original, alvo))) "Toquei em $original."
            else "Não achei \"$original\" na tela."
        }

        // Turbo (liberar memoria)
        if (t.contains("turbo") || t.contains("limpa a memoria") || t.contains("limpar memoria") ||
            t.contains("limpa memoria") || t.contains("libera memoria") || t.contains("liberar memoria")) {
            return Turbo.limpar(ctx)
        }

        // Rotina
        if (t == "rotina" || t.startsWith("roda a rotina") || t == "bora" || t == "partiu") {
            Rotina.rodar(ctx)
            return "Rodando a rotina."
        }

        // Abrir app pelo nome
        val nomeApp = t.replace(Regex("^(abre|abrir|abra|liga|ligar|inicia|iniciar|vai pro|vai para o|vai para a)( o| a| os| as)? "), "")
        val app = Apps.procurar(ctx, nomeApp)
        if (app != null && Apps.abrir(ctx, app.pacote)) return "Abrindo ${app.nome}."

        return null
    }

    private fun precisaAcessibilidade() =
        "Para ler a tela ou tocar em botões preciso do Controle (opcional). No Painel ASX, toque em \"Ligar\"."
}
