package br.com.leonardo.painelasx

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * A central nem sempre reinicia: muitas vezes ela "dorme" com o carro desligado e o sistema
 * fecha o Painel ASX. Para o app voltar sozinho quando ela acorda, usamos vários caminhos ao mesmo tempo
 * (o registro mostra qual funcionou nesta central):
 *  1) avisos da própria central no manifesto (ACTION_MCU_STATE_CHANGE, ACTION_AFTER_BOOT_COMPLETED);
 *  2) um alarme "sem acordar" marcado na hora de dormir: o Android entrega quando a central acorda;
 *  3) um trabalho agendado que espera a internet (o Wi-Fi volta quando ela acorda).
 */
object Despertador {
    const val ACAO = "br.com.leonardo.painelasx.ACORDAR"
    private const val JOB = 4812

    /** Estado da central ("3" = dormindo). Lê direto (rápido); se o Android não deixar, pergunta ao getprop. */
    fun estadoMcu(): String = Props.ler("sys.nwd.mcu.state")

    /** Chamado quando a central vai dormir. */
    fun marcar(ctx: Context) {
        try { Jarvis.dormiu(ctx) } catch (_: Exception) { }
        try {
            val i = Intent(ctx, Inicio::class.java).setAction(ACAO)
            val pi = PendingIntent.getBroadcast(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 20_000, pi)
        } catch (e: Exception) {
            Registro.anotar("despertador: alarme falhou (${e.message})")
        }
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            js.schedule(JobInfo.Builder(JOB, ComponentName(ctx, AcordaJob::class.java))
                .setMinimumLatency(20_000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(false)
                .build())
        } catch (e: Exception) {
            Registro.anotar("despertador: trabalho falhou (${e.message})")
        }
        Registro.anotar("despertador: marcado para quando a central acordar")
    }

    /** Alguém nos acordou: se a central está ligada (não dormindo), dá as boas-vindas. */
    fun talvezAcordou(ctx: Context, quem: String) {
        val app = ctx.applicationContext
        ServicoFixo.iniciar(app)
        Thread {
            val estado = estadoMcu()
            Registro.anotar("despertador: chamado por $quem (estado da central: ${estado.ifBlank { "?" }})")
            if (estado == "3") {
                marcar(app)   // ainda dormindo: tenta de novo depois
            } else if (!Jarvis.devoSaudar(app)) {
                // O alarme e a "internet voltou" chegam às vezes mais de 1 min depois de a central acordar,
                // quando o anjo da guarda/tela do carro já saudou: sem isto o Jarvis saudava 2 vezes (registro 31/10 09:32 e 09:33).
                Registro.anotar("despertador: o Jarvis já saudou desde que a central acordou")
            } else {
                Rede.naTela {
                    Voz.iniciar(app)
                    Jarvis.partida(app)
                }
            }
        }.start()
    }
}

/** Roda quando a internet volta depois de a central dormir. */
class AcordaJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Registro.iniciar(this)
        Despertador.talvezAcordou(this, "internet voltou")
        try { Ouvido.internetVoltou() } catch (_: Exception) { }
        return false
    }

    override fun onStopJob(params: JobParameters?) = false
}
