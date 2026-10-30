package br.com.leonardo.painelasx

import android.content.Context
import android.content.Intent
import java.text.Normalizer

/** Lista e abre os apps que existem na central. */
object Apps {
    data class App(val nome: String, val pacote: String)

    fun normalizar(t: String): String =
        Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun listar(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val lista = pm.queryIntentActivities(i, 0)
        return lista.map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pacote }
            .sortedBy { normalizar(it.nome) }
    }

    /** Procura o app cujo nome mais se parece com o que foi dito. */
    fun procurar(ctx: Context, dito: String): App? {
        val alvo = normalizar(dito)
        if (alvo.isEmpty()) return null
        val palavras = alvo.split(" ").filter { it.length > 2 }
        var melhor: App? = null
        var nota = 0
        for (a in listar(ctx)) {
            val n = normalizar(a.nome)
            val p = when {
                n == alvo -> 4
                n.startsWith(alvo) || alvo.startsWith(n) -> 3
                n.contains(alvo) || alvo.contains(n) -> 2
                palavras.any { n.split(" ").contains(it) } -> 1
                else -> 0
            }
            if (p > nota) {
                nota = p
                melhor = a
            }
        }
        return melhor
    }

    fun abrir(ctx: Context, pacote: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pacote) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            Registro.anotar("abriu app: $pacote")
            true
        } catch (e: Exception) {
            Registro.anotar("falhou ao abrir $pacote: ${e.message}")
            false
        }
    }

    fun nomeDe(ctx: Context, pacote: String?): String {
        if (pacote.isNullOrEmpty()) return "nenhum"
        return listar(ctx).firstOrNull { it.pacote == pacote }?.nome ?: pacote
    }
}
