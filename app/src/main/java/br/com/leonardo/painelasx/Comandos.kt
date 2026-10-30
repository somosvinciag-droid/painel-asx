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
            val r = Viagens.resumo(dias)
            return "Nos últimos $dias dia(s): ${r.optInt("viagens")} viagens, ${r.optDouble("km_total")} km, ${r.optDouble("horas_dirigindo")} h dirigindo."
        }

        // Rota no GPS ("rota para Pouso Alegre", "rota para Caxambu, Pouso Alegre, Caxambu")
        Rotas.destinos(falado)?.let { return Rotas.navegar(ctx, it) }

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
            // Sem o Controle: abre a tela inicial do jeito normal do Android.
            return try {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Tela inicial."
            } catch (e: Exception) { precisaAcessibilidade() }
        }
        if (t == "voltar" || t == "volta") {
            return if (ctl?.voltar() == true) "Voltei." else precisaAcessibilidade()
        }
        if (t.startsWith("le a tela") || t.startsWith("ler a tela") || t.startsWith("leia a tela")) {
            if (ctl == null) return precisaAcessibilidade()
            Registro.anotar("leitura da tela:\n" + ctl.lerTela())
            return "Anotei os botões desta tela no diagnóstico."
        }
        Regex("^(toca|toque|clica|clique|aperta|aperte) (em |no |na )?(.+)$").find(t)?.let { m ->
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

        return "Não entendi \"$falado\"."
    }

    private fun precisaAcessibilidade() =
        "Para ler a tela ou tocar em botões preciso do Controle (opcional). No Painel ASX, toque em \"Ligar\"."
}
