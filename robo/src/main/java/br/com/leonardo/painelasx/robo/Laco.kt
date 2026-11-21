package br.com.leonardo.painelasx.robo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Ligação com o servidor (vaga /d/redmi), igual à central do carro:
 *  - registra uma vez com uma chave aleatória (só o sha256 fica no servidor);
 *  - fica esperando comandos (/app/comandos, 20 s por pedido), executa e devolve (/app/resultado);
 *  - manda o estado e o registro (/app/relatorio) de minuto em minuto.
 * Sem internet: tenta de novo sozinho (2 s, 4 s, ... até 30 s).
 */
object Laco {
    @Volatile
    private var linha: Thread? = null
    @Volatile
    var ultimoContato = 0L
    @Volatile
    var ultimoErro = ""
    private val executor = Executors.newSingleThreadExecutor()
    private var ultimoRelatorio = 0L

    fun prefs(ctx: Context) = ctx.getSharedPreferences("conector", Context.MODE_PRIVATE)

    private fun aleatorio(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun chave(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("chave", null) ?: aleatorio(24).also { p.edit().putString("chave", it).apply() }
    }

    fun tokenMcp(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("mcp", null) ?: aleatorio(18).also { p.edit().putString("mcp", it).apply() }
    }

    fun link(ctx: Context) = BuildConfig.SERVIDOR + "/mcp/" + tokenMcp(ctx)

    fun online() = System.currentTimeMillis() - ultimoContato < 75_000

    @Synchronized
    fun iniciar(ctx: Context) {
        if (linha?.isAlive == true) return
        linha = Thread({ rodar(ctx.applicationContext) }, "conector").apply { isDaemon = true; start() }
    }

    private fun rodar(ctx: Context) {
        var espera = 2000L
        while (true) {
            try {
                if (!prefs(ctx).getBoolean("registrado", false)) registrar(ctx)
                if (System.currentTimeMillis() - ultimoRelatorio > 60_000) relatorio(ctx)
                val r = pedir(ctx, "GET", "/app/comandos", null, 35_000)
                if (r.first == 403) {
                    Registro.anotar("ERRO conector: servidor recusou a chave (403)")
                    throw Exception("403")
                }
                if (r.first != 200) throw Exception("comandos deu ${r.first}")
                ultimoContato = System.currentTimeMillis()
                ultimoErro = ""
                espera = 2000L
                val cmds = JSONObject(r.second).optJSONArray("comandos") ?: JSONArray()
                for (i in 0 until cmds.length()) {
                    val c = cmds.getJSONObject(i)
                    executor.execute { atender(ctx, c) }
                }
            } catch (e: Throwable) {
                ultimoErro = (e.message ?: e.javaClass.simpleName).take(200)
                try { Thread.sleep(espera) } catch (_: InterruptedException) { }
                espera = (espera * 2).coerceAtMost(30_000)
            }
        }
    }

    private fun registrar(ctx: Context) {
        val r = pedir(ctx, "POST", "/app/registrar", JSONObject().put("chave", chave(ctx)).put("mcp", tokenMcp(ctx)), 20_000)
        if (r.first == 200) {
            prefs(ctx).edit().putBoolean("registrado", true).apply()
            Registro.anotar("conector: registrado no servidor")
        } else {
            Registro.anotar("ERRO conector: registro deu ${r.first} ${r.second.take(150)}")
            throw Exception("registro ${r.first}")
        }
    }

    fun relatorio(ctx: Context) {
        val linhas = Registro.pegarNovas()
        try {
            val corpo = JSONObject().put("estado", Estado.agora(ctx)).put("linhas", JSONArray(linhas))
            val r = pedir(ctx, "POST", "/app/relatorio", corpo, 20_000)
            if (r.first != 200) throw Exception("relatorio ${r.first}")
            ultimoRelatorio = System.currentTimeMillis()
        } catch (e: Exception) {
            Registro.devolver(linhas)
            throw e
        }
    }

    private fun atender(ctx: Context, c: JSONObject) {
        val id = c.optString("id")
        val criado = c.optLong("criado")
        if (criado > 0 && System.currentTimeMillis() - criado > 120_000) return // velho demais
        val tipo = c.optString("tipo")
        val args = try { JSONObject(c.optString("texto").ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        var ok = true
        val resposta = try {
            Comandos.executar(ctx, tipo, args)
        } catch (e: Throwable) {
            ok = false
            Registro.anotar("ERRO comando $tipo: ${e.message}")
            e.message ?: e.javaClass.simpleName
        }
        if (tipo != "print") Registro.anotar("comando $tipo ${args.toString().take(120)} -> ${if (ok) "ok" else "erro"}: ${resposta.take(120)}")
        for (tentativa in 1..3) {
            try {
                val r = pedir(ctx, "POST", "/app/resultado", JSONObject().put("id", id).put("ok", ok).put("resposta", resposta), 30_000)
                if (r.first == 200) break
            } catch (_: Exception) {
                Thread.sleep(1000)
            }
        }
    }

    fun pedir(ctx: Context, metodo: String, caminho: String, corpo: JSONObject?, leituraMs: Int): Pair<Int, String> {
        val c = URL(BuildConfig.SERVIDOR + caminho).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15_000
            c.readTimeout = leituraMs
            c.setRequestProperty("Authorization", "Bearer " + chave(ctx))
            if (corpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(corpo.toString().toByteArray()) }
            }
            val cod = c.responseCode
            val fluxo = if (cod < 400) c.inputStream else c.errorStream
            val texto = fluxo?.use { s ->
                val b = ByteArrayOutputStream()
                s.copyTo(b)
                b.toString("UTF-8")
            } ?: ""
            return cod to texto
        } finally {
            c.disconnect()
        }
    }
}
