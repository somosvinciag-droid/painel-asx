package br.com.leonardo.painelasx

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock

/**
 * "O que abriu junto com o Android": quais apps e serviços sobem na partida da central.
 * No Android 10 o app não enxerga os processos dos outros; por isso usa o "acesso ao uso"
 * (UsageStatsManager), que o Leonardo liga uma vez nos ajustes. Só leitura: não fecha nem desativa nada.
 */
object Partida {
    private const val JANELA_MS = 3 * 60_000L

    fun temAcesso(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val modo = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (modo == AppOpsManager.MODE_DEFAULT)
            ctx.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else modo == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun tipo(t: Int): String? = when (t) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "abriu tela"
        UsageEvents.Event.ACTIVITY_PAUSED -> "saiu da tela"
        UsageEvents.Event.ACTIVITY_STOPPED -> "tela parada"
        UsageEvents.Event.FOREGROUND_SERVICE_START -> "serviço em 1º plano"
        UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "serviço em 1º plano parou"
        UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "standby mudou"
        UsageEvents.Event.USER_INTERACTION -> "usado"
        UsageEvents.Event.DEVICE_STARTUP -> "android ligou"
        UsageEvents.Event.DEVICE_SHUTDOWN -> "android desligou"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "tela acesa"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "tela apagada"
        UsageEvents.Event.CONFIGURATION_CHANGE -> null   // não diz nada sobre o app
        else -> "evento $t"
    }

    private class Pacote(val primeira: Long) { val tipos = LinkedHashSet<String>() }

    /** Lista, em ordem de tempo, do que apareceu do boot até 3 min depois. */
    fun texto(ctx: Context): String {
        if (!temAcesso(ctx)) return "sem acesso ao uso (ligar no botão)\n"
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val eventos = usm.queryEvents(boot, boot + JANELA_MS) ?: return "(nenhum evento)\n"
        val porPacote = LinkedHashMap<String, Pacote>()
        val e = UsageEvents.Event()
        while (eventos.hasNextEvent()) {
            eventos.getNextEvent(e)
            val t = tipo(e.eventType) ?: continue
            val p = e.packageName ?: continue
            porPacote.getOrPut(p) { Pacote(e.timeStamp) }.tipos.add(t)
        }
        if (porPacote.isEmpty()) return "(nenhum evento desde que a central ligou)\n"
        val sb = StringBuilder()
        sb.append("desde o boot (").append(java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale("pt", "BR")).format(java.util.Date(boot)))
            .append(") até 3 min depois; segundos depois do boot:\n")
        for ((p, info) in porPacote.entries.sortedBy { it.value.primeira }.take(120)) {
            val nome = try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString()
            } catch (_: Exception) { p }
            sb.append("+").append(String.format(java.util.Locale("pt", "BR"), "%.1f", (info.primeira - boot) / 1000.0)).append(" s  ")
                .append(nome).append(" (").append(p).append("): ").append(info.tipos.joinToString(", ")).append('\n')
        }
        return sb.toString()
    }
}
