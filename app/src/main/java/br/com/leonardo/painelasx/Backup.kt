package br.com.leonardo.painelasx

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Backup na nuvem (no servidor do conector, na Cloudflare do Leonardo):
 * ajustes, favoritos, diário do carro e viagens. NUNCA a chave do app nem o link do conector.
 * Para recuperar numa instalação nova: colar o link antigo do conector no Painel.
 */
object Backup {
    private val ARQUIVOS = listOf("viagens.json", "onde_parou.json")

    fun montar(ctx: Context): JSONObject {
        val prefs = JSONObject()
        for (nome in Config.ARQUIVOS_PREFS) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(nome, Context.MODE_PRIVATE).all) {
                when (v) {
                    is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
                    is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
                    is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                    is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
                }
            }
            prefs.put(nome, o)
        }
        val arquivos = JSONObject()
        for (a in ARQUIVOS) {
            val f = File(ctx.filesDir, a)
            if (f.exists() && f.length() < 3_000_000) arquivos.put(a, f.readText())
        }
        return JSONObject().put("versao_app", BuildConfig.VERSION_CODE).put("em", System.currentTimeMillis())
            .put("prefs", prefs).put("arquivos", arquivos)
    }

    fun enviar(ctx: Context): String {
        val corpo = montar(ctx)
        val r = Conector.enviar("/app/backup", corpo, 60_000)
        val ok = r.first == 200
        Registro.anotar(if (ok) "backup: enviado (${corpo.toString().length / 1024} KB)" else "backup: falhou (${r.first})")
        return if (ok) "Backup feito na nuvem." else "O backup não foi agora (sem internet?)."
    }

    /** Põe de volta o que veio do servidor. Depois o app reinicia sozinho. */
    fun restaurar(ctx: Context, b: JSONObject): Int {
        var n = 0
        val prefs = b.optJSONObject("prefs") ?: JSONObject()
        for (nome in prefs.keys()) {
            if (nome !in Config.ARQUIVOS_PREFS) continue
            val o = prefs.getJSONObject(nome)
            val e = ctx.getSharedPreferences(nome, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                val x = o.getJSONObject(k)
                when (x.optString("t")) {
                    "b" -> e.putBoolean(k, x.optBoolean("v"))
                    "i" -> e.putInt(k, x.optInt("v"))
                    "l" -> e.putLong(k, x.optLong("v"))
                    "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                    "s" -> e.putString(k, x.optString("v"))
                }
                n++
            }
            e.commit()
        }
        val arquivos = b.optJSONObject("arquivos") ?: JSONObject()
        for (a in arquivos.keys()) if (a in ARQUIVOS) { File(ctx.filesDir, a).writeText(arquivos.getString(a)); n++ }
        Registro.anotar("backup: restaurei $n itens")
        Registro.gravarAgora()
        return n
    }
}
