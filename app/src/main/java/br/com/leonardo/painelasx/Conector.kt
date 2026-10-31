package br.com.leonardo.painelasx

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Liga a central ao Claude do Leonardo (servidor "painel-asx" na Cloudflare dele).
 *  - manda o estado, os erros e o diagnostico;
 *  - fica esperando comandos do Claude e devolve o resultado.
 * Roda dentro do Controle (que fica sempre ligado), numa linha de trabalho propria.
 */
object Conector {
    private val rodando = AtomicBoolean(false)
    @Volatile var online = false
        private set
    @Volatile var ultimoErro = ""
        private set

    fun configurado() = BuildConfig.SERVIDOR.startsWith("https://")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("conector", Context.MODE_PRIVATE)

    private fun aleatorio(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun chaveApp(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("chave", null) ?: aleatorio(32).also { p.edit().putString("chave", it).apply() }
    }

    private fun tokenMcp(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("mcp", null) ?: aleatorio(24).also { p.edit().putString("mcp", it).apply() }
    }

    /** O link que o Leonardo cola no Claude (Configurações > Conectores > Adicionar). */
    fun linkDoClaude(ctx: Context) = BuildConfig.SERVIDOR + "/mcp/" + tokenMcp(ctx)

    /** Troca o link (o antigo deixa de funcionar). */
    fun gerarLinkNovo(ctx: Context) {
        prefs(ctx).edit().putString("mcp", aleatorio(24)).putBoolean("registrado", false).apply()
    }

    fun iniciar(ctx: Context) {
        if (!configurado()) return
        if (!rodando.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        preparar(app)
        Thread({ laco(app) }, "conector").start()
    }

    private fun laco(ctx: Context) {
        try {
            lacoSemFim(ctx)
        } catch (t: Throwable) {
            Registro.anotar("ERRO conector parou: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            rodando.set(false)   // deixa o próximo Conector.iniciar (serviço fixo, Controle) ligar de novo
        }
    }

    private fun lacoSemFim(ctx: Context) {
        Registro.anotar("conector: ligando")
        var espera = 3000L
        var ultimoRelatorio = 0L
        var mandouDiagnostico = false
        var ultimoEstado = ""
        while (true) {
            try {
                if (!prefs(ctx).getBoolean("registrado", false)) {
                    val r = pedir("POST", "/app/registrar", JSONObject()
                        .put("chave", chaveApp(ctx)).put("mcp", tokenMcp(ctx)), semChave = true)
                    if (r.first == 403) {
                        ultimoErro = "esta central foi registrada por outro aparelho"
                        Registro.anotar("conector: RECUSADO — " + ultimoErro)
                        Thread.sleep(10 * 60 * 1000L)
                        continue
                    }
                    if (r.first != 200) throw Exception("registro deu ${r.first}")
                    prefs(ctx).edit().putBoolean("registrado", true).apply()
                    Registro.anotar("conector: registrado")
                }

                // Relatorio: estado + linhas novas do registro (+ diagnostico 1 vez por ligacao).
                // Estado igual ao último (fora a hora) e nada novo no registro: só manda de 5 em 5 min
                // (antes mandava todo minuto o mesmo estado; o "online" do servidor vem do pedido de comandos).
                val agora = System.currentTimeMillis()
                val linhas = Registro.pegarParaEnviar()
                val est = estado(ctx)
                est.remove("hora_central")
                val assinatura = est.toString()
                est.put("hora_central", agora)
                val passou = agora - ultimoRelatorio
                if (linhas.isNotEmpty() || !mandouDiagnostico || passou > 5 * 60_000 ||
                    (passou > 60_000 && assinatura != ultimoEstado)) {
                    val corpo = JSONObject().put("estado", est).put("linhas", JSONArray(linhas))
                    if (!mandouDiagnostico) corpo.put("diagnostico", Diagnostico.texto(ctx))
                    // Sem internet no meio do envio: devolve as linhas (antes os erros dessa hora se perdiam).
                    val r = try { pedir("POST", "/app/relatorio", corpo) } catch (e: Exception) { Registro.devolverParaEnviar(linhas); throw e }
                    if (r.first == 403) {
                        prefs(ctx).edit().putBoolean("registrado", false).apply()
                        Registro.devolverParaEnviar(linhas)
                        continue
                    }
                    if (r.first != 200) { Registro.devolverParaEnviar(linhas); throw Exception("relatorio deu ${r.first}") }
                    try {
                        val o = JSONObject(r.second)
                        Config.jarvisConversa = o.optBoolean("jarvis", false)
                        Config.jarvisAtlas = o.optBoolean("atlas", false)
                        mudarGzip(ctx, o.optBoolean("gzip", false))
                    } catch (_: Exception) { }
                    ultimoRelatorio = agora
                    ultimoEstado = assinatura
                    mandouDiagnostico = true
                }

                // Espera comando do Claude (ate ~20 s)
                val r = pedir("GET", "/app/comandos", null, leituraMs = 35_000)
                if (r.first != 200) throw Exception("comandos deu ${r.first}")
                if (!online) try { Ouvido.internetVoltou() } catch (_: Exception) { }
                online = true
                ultimoErro = ""
                espera = 3000L
                val cmds = JSONObject(r.second).optJSONArray("comandos") ?: JSONArray()
                for (i in 0 until cmds.length()) executar(ctx, cmds.getJSONObject(i))
            } catch (e: Exception) {
                // (Throwable fora daqui, como falta de memória, sai do laço e o conector religa no próximo iniciar.)
                if (online) Registro.anotar("conector: sem conexão (${e.javaClass.simpleName})")
                online = false
                ultimoErro = e.message ?: e.javaClass.simpleName
                Thread.sleep(espera)
                espera = (espera * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun estado(ctx: Context): JSONObject = JSONObject()
        .put("versao_app", BuildConfig.VERSION_CODE)
        .put("controle_ligado", Controle.ativo != null)
        .put("abre_apps_sozinho", android.provider.Settings.canDrawOverlays(ctx))
        .put("tela_atual", Controle.ativo?.telaAtual ?: "")
        .put("app_aberto_na_frente", MainActivity.naTela)
        .put("rotina_ligada", Rotina.ligada(ctx))
        .put("atualizacao_disponivel", Atualizador.disponivel(ctx).let { if (it > 0) "v$it" else "" })
        .put("instala_sozinho", Atualizador.sozinho(ctx))
        .put("modelo", Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("hora_central", System.currentTimeMillis())
        .put("velocidade_kmh", Carro.velocidadeKmh.toInt())
        .put("limite_kmh", Config.limite(ctx))
        .put("tocando", Musica.tocandoAgora(ctx) ?: "")
        .put("clima", Clima.curto())
        .put("odometro_estimado", Diario.odometro(ctx) ?: JSONObject.NULL)
        .put("ntfy", Config.topicoNtfy(ctx))
        .put("voz", Voz.qualVoz())
        .put("voz_propria", VozPropria.estado)
        .put("ouvido", Ouvido.estado)
        .put("microfone_liberado", Ouvido.temPermissao(ctx))

    private fun executar(ctx: Context, c: JSONObject) {
        val id = c.optString("id")
        val tipo = c.optString("tipo")
        val texto = c.optString("texto")
        val idade = System.currentTimeMillis() - c.optLong("criado", 0)
        if (idade > 90_000) {
            Registro.anotar("conector: ignorei comando atrasado ($tipo)")
            return
        }
        Registro.anotar("claude pediu: $tipo ${texto.take(60)}".trim())
        var ok = true
        val resposta: String = try {
            when (tipo) {
                "comando" -> naTelaPrincipal { Comandos.executar(ctx, texto) }
                "tela" -> naTelaPrincipal {
                    Controle.ativo?.lerTela() ?: "O controle (Acessibilidade) está desligado na central."
                }
                "tocar" -> naTelaPrincipal {
                    val ctl = Controle.ativo
                    when {
                        ctl == null -> "O controle (Acessibilidade) está desligado na central."
                        ctl.clicarTexto(listOf(texto)) -> "Toquei em \"$texto\"."
                        else -> { ok = false; "Não achei \"$texto\" na tela." }
                    }
                }
                "diagnostico" -> Diagnostico.texto(ctx)
                "dados" -> LeitorCarro.dados(ctx).toString(2)
                "viagens" -> Viagens.resumo(texto.toIntOrNull()?.coerceIn(1, 365) ?: 7).toString(2)
                "onde" -> Viagens.ondeParou().toString(2)
                "mapa" -> LeitorCarro.mapa(ctx)
                "viagens_mapa" -> Viagens.resumo(texto.toIntOrNull()?.coerceIn(1, 365) ?: 7, comTrajeto = true).toString()
                "manutencao" -> JSONObject()
                    .put("texto", Diario.manutencaoTexto(ctx))
                    .put("odometro_estimado", Diario.odometro(ctx) ?: JSONObject.NULL)
                    .put("km_somados_gps", Math.round(Diario.kmGps(ctx)))
                    .put("abastecimentos", Diario.abastecimentos(ctx)).toString(2)
                "lugares" -> Lugares.lista(ctx).toString(2)
                "backup" -> Backup.enviar(ctx)
                "foto" -> FotoCamera.tirar(ctx, texto.ifBlank { null }).toString()
                "reiniciar_voz" -> naTelaPrincipal { Voz.reiniciar(ctx); "Voz reiniciada." }
                "falar" -> naTelaPrincipal { Voz.falar(texto, Voz.Tipo.IMPORTANTE); "Falei na central: \"$texto\"" }
                "atualizar" -> {
                    val msgs = mutableListOf<String>()
                    Atualizador.verificar(ctx) { msgs.add(it) }
                    msgs.joinToString(" ")
                }
                else -> { ok = false; "Tipo de comando desconhecido: $tipo" }
            }
        } catch (e: Exception) {
            ok = false
            "Erro na central: ${e.javaClass.simpleName}: ${e.message}"
        }
        try {
            pedir("POST", "/app/resultado", JSONObject().put("id", id).put("ok", ok).put("resposta", resposta))
        } catch (e: Exception) {
            Registro.anotar("conector: não consegui devolver o resultado (${e.message})")
        }
    }

    /** Roda na linha principal do Android (abrir apps e tocar na tela precisam disso) e espera. */
    private fun naTelaPrincipal(bloco: () -> String): String {
        var r = "sem resposta"
        val pronto = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            r = try { bloco() } catch (e: Exception) { "Erro: ${e.message}" }
            pronto.countDown()
        }
        pronto.await(15, TimeUnit.SECONDS)
        Thread.sleep(800) // deixa a tela mudar antes de responder
        return r
    }

    private fun pedir(
        metodo: String, caminho: String, corpo: JSONObject?,
        semChave: Boolean = false, leituraMs: Int = 20_000
    ): Pair<Int, String> {
        val c = URL(BuildConfig.SERVIDOR + caminho).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 15_000
        c.readTimeout = leituraMs
        c.setRequestProperty("User-Agent", "painel-asx/" + BuildConfig.VERSION_CODE)
        if (!semChave) c.setRequestProperty("Authorization", "Bearer " + chaveAppCache)
        var comprimido = false
        if (corpo != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            var bytes = corpo.toString().toByteArray(Charsets.UTF_8)
            // Corpo grande (diagnóstico, backup, respostas ao Claude) vai comprimido, se o servidor aceitar:
            // texto e JSON ficam 2,5 a 4,5 vezes menores (menos internet do celular).
            if (servidorGzip && bytes.size > 1024) {
                val b = java.io.ByteArrayOutputStream(bytes.size / 3)
                java.util.zip.GZIPOutputStream(b).use { it.write(bytes) }
                bytes = b.toByteArray()
                comprimido = true
                c.setRequestProperty("Content-Encoding", "gzip")
            }
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        // Servidor antigo (sem gzip) recusa o corpo comprimido: volta a mandar sem comprimir (o envio é repetido
        // depois pelo próprio laço/fila, como em qualquer falha).
        if (comprimido && code in listOf(400, 415, 500)) {
            servidorGzip = false
            Registro.anotar("conector: o servidor não aceitou o envio comprimido ($code); volto a mandar sem comprimir")
        }
        val txt = try {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) { "" }
        c.disconnect()
        return code to txt
    }

    @Volatile private var chaveAppCache = ""
    /** O servidor aceita corpo comprimido (gzip)? Ele diz isso na resposta do relatório. */
    @Volatile private var servidorGzip = false

    private fun mudarGzip(ctx: Context, v: Boolean) {
        if (v == servidorGzip && prefs(ctx).getBoolean("gzip", false) == v) return
        servidorGzip = v
        prefs(ctx).edit().putBoolean("gzip", v).apply()
    }

    /** Pedido ao servidor do conector (fora da linha principal). */
    fun enviar(caminho: String, corpo: JSONObject, leituraMs: Int = 20_000): Pair<Int, String> {
        if (!configurado() || chaveAppCache.isEmpty()) return 0 to ""
        return pedir("POST", caminho, corpo, leituraMs = leituraMs)
    }

    /**
     * Instalação nova (ou central trocada): usa o link antigo do conector para provar que é o dono,
     * fica com o MESMO link (o Claude não precisa de nada novo) e traz o backup de volta.
     */
    fun recuperar(ctx: Context, linkAntigo: String): String {
        val token = linkAntigo.trim().substringAfter("/mcp/", "").trim('/', ' ')
        if (token.length < 24) return "Isso não parece o link do conector (precisa ter /mcp/ no meio)."
        preparar(ctx)
        val r = pedir("POST", "/app/recuperar", JSONObject().put("mcp_antigo", token).put("chave", chaveApp(ctx)), semChave = true, leituraMs = 60_000)
        if (r.first == 403 || r.first == 404) return "O servidor não aceitou esse link (não é o link atual do conector)."
        if (r.first != 200) return "Sem resposta do servidor agora (${r.first}). Tente com internet."
        prefs(ctx).edit().putString("mcp", token).putBoolean("registrado", true).apply()
        val o = JSONObject(r.second)
        val b = o.optJSONObject("backup")
        val n = if (b != null) Backup.restaurar(ctx, b) else 0
        Registro.anotar("conector: recuperado com o link antigo ($n itens do backup)")
        return if (b != null) "Pronto! Recuperei $n ajustes e o histórico. O app vai reiniciar." else "Pronto! A central voltou a ser sua (não havia backup guardado)."
    }

    /** Chamado antes de iniciar, para a chave estar pronta. */
    fun preparar(ctx: Context) {
        chaveAppCache = chaveApp(ctx)
        servidorGzip = prefs(ctx).getBoolean("gzip", false)
        tokenMcp(ctx)
    }
}
