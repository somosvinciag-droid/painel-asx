package br.com.leonardo.painelasx.robo

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

object Estado {
    @Volatile
    var ultimoArquivo: String = ""

    fun agora(ctx: Context): JSONObject {
        val o = JSONObject()
        o.put("app", "Painel Redmi " + BuildConfig.VERSION_NAME)
        o.put("modelo", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")")
        o.put("android", Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT)
        try {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val nivel = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val escala = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                o.put("bateria", if (nivel >= 0) nivel * 100 / escala else -1)
                o.put("carregando", b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
        } catch (_: Exception) { }
        val pm = ctx.getSystemService(PowerManager::class.java)
        o.put("tela_ligada", pm.isInteractive)
        o.put("sem_restricao_de_bateria", pm.isIgnoringBatteryOptimizations(ctx.packageName))
        o.put("bloqueado", ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        o.put("bloqueio_com_senha", ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        o.put("acessibilidade", Robo.ativo != null)
        o.put("acessibilidade_na_lista", App.acessibilidadeNaLista(ctx))
        o.put("religa_acessibilidade_sozinho", App.podeEscreverAjustes(ctx))
        o.put("avisos_permitidos", ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        o.put("servico_fixo", Servico.rodando)
        o.put("app_na_frente", Robo.pacoteAtual)
        o.put("ligado_ha_min", SystemClock.elapsedRealtime() / 60000)
        if (Laco.ultimoErro.isNotEmpty()) o.put("ultimo_erro_rede", Laco.ultimoErro)
        if (ultimoArquivo.isNotEmpty()) o.put("ultimo_arquivo", ultimoArquivo)
        return o
    }
}
