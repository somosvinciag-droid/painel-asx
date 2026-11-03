package br.com.leonardo.painelasx

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Botao Turbo: fecha o que esta parado em segundo plano e libera memoria.
 * Nao mexe no que esta aberto na tela, no Android Auto/ZLink, no teclado,
 * no sistema, no Bluetooth nem no proprio Painel ASX.
 */
object Turbo {
    private val NUNCA = listOf(
        "zlink", "carbit", "autokit", "tlink", "projection.gearhead", "carplay",
        "launcher", "inputmethod", "keyboard", "systemui", "bluetooth", "phone", "telecom",
        "com.google.android.gms", "com.android.vending", "com.android.providers", "radio", "canbus", "mcu", "bt.music", "com.bt.", "com.nwd.bt"
    )

    private fun livreMb(am: ActivityManager): Long {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    private val rodando = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Devolve a frase do resultado ("Liberei 320 MB..."). Pode demorar 1-2 s: chame fora da tela principal se der.
     * Uma limpeza por vez (02/11 21:28: 6 seguidas em 8 s, "liberei 0 mb", cada uma passando por todos os apps).
     */
    fun limpar(ctx: Context): String {
        if (!rodando.compareAndSet(false, true)) return "Turbo: já estou liberando a memória."
        try { return limparAgora(ctx) } finally { rodando.set(false) }
    }

    private fun limparAgora(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val antes = livreMb(am)
        val teclado = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') ?: ""
        val inicio = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName ?: ""
        val frente = Controle.ativo?.telaAtual?.substringBefore(" /")?.trim() ?: ""

        @Suppress("DEPRECATION")
        val pacotes = ctx.packageManager.getInstalledPackages(0).map { it.packageName }
        var fechados = 0
        for (p in pacotes) {
            if (p == ctx.packageName || p == teclado || p == inicio || p == frente) continue
            val l = p.lowercase()
            if (NUNCA.any { l.contains(it) }) continue
            try {
                am.killBackgroundProcesses(p)
                fechados++
            } catch (_: Exception) {
            }
        }
        System.gc()
        Thread.sleep(800)
        val depois = livreMb(am)
        val ganho = (depois - antes).coerceAtLeast(0)
        val total = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        val porcento = if (total > 0) depois * 100 / total else 0
        val frase = "Turbo: liberei $ganho MB. Agora tem $depois MB livres ($porcento% da memória)."
        Registro.anotar(frase.lowercase())
        return frase
    }
}
