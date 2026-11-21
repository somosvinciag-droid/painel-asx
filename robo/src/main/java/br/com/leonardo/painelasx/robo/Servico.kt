package br.com.leonardo.painelasx.robo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Serviço fixo (primeiro plano, aviso discreto): segura o processo vivo no HyperOS e confere de minuto em
 * minuto se o laço do conector e a Acessibilidade continuam ligados.
 */
class Servico : Service() {

    companion object {
        @Volatile
        var rodando = false
        private const val ID = 7

        fun ligar(ctx: Context, quem: String) {
            if (rodando) return
            try {
                ctx.startForegroundService(Intent(ctx, Servico::class.java).putExtra("quem", quem))
            } catch (e: Exception) {
                Registro.anotar("ERRO serviço fixo não ligou ($quem): ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private val vigia = object : Runnable {
        override fun run() {
            Laco.iniciar(applicationContext)
            Robo.religarSePuder(applicationContext)
            h.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fixo", "Painel Redmi ligado", NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "fixo")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Painel Redmi ligado")
            .setContentText("O Claude pode usar este celular pelo conector.")
            .setOngoing(true)
            .setContentIntent(abrir)
            .build()
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            if (!rodando) Registro.anotar("serviço fixo: ligado (${intent?.getStringExtra("quem") ?: "sistema"})")
            rodando = true
        } catch (e: Exception) {
            Registro.anotar("ERRO serviço fixo: ${e.message}")
        }
        h.removeCallbacks(vigia)
        h.post(vigia)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Arrastado dos recentes: continua (START_STICKY) e o vigia religa se precisar.
        Registro.anotar("serviço fixo: app tirado dos recentes")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        rodando = false
        h.removeCallbacks(vigia)
        Registro.anotar("serviço fixo: parado pelo sistema")
        super.onDestroy()
    }
}

/** De 15 em 15 minutos (mesmo com o app morto) o Android acorda o vigia, que religa tudo. */
class Vigia : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        App.garantir(applicationContext, "vigia")
        return false
    }

    override fun onStopJob(params: JobParameters?) = true
}

/** Celular ligou / app atualizou: liga o serviço fixo e o laço. */
class Inicio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Registro.anotar("início: ${intent.action?.substringAfterLast('.')}")
        App.garantir(ctx.applicationContext, "boot")
    }
}

/**
 * Só para o adb (o receptor exige DUMP, que só o shell/sistema têm):
 *  ...robo.LINK    -> devolve o link do conector (para o Claude do PC guardar)
 *  ...robo.ESTADO  -> devolve o estado
 *  ...robo.LIGAR   -> liga o serviço fixo e o laço
 */
class Pedido : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: ""
        App.garantir(ctx.applicationContext, "adb")
        resultData = when {
            a.endsWith(".LINK") -> "LINK=" + Laco.link(ctx)
            a.endsWith(".ESTADO") -> Estado.agora(ctx).put("online", Laco.online()).toString()
            else -> "ok"
        }
        resultCode = 1
    }
}
