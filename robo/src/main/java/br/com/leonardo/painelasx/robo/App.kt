package br.com.leonardo.painelasx.robo

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O processo do Painel Redmi. Sobe quando: o celular liga (Inicio), a Acessibilidade liga (Robo),
 * o vigia de 15 em 15 min roda (Vigia) ou o app é aberto. Em todos os casos garante o laço do conector.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        contexto = this
        Registro.anotar("processo iniciou (versão ${BuildConfig.VERSION_NAME})")
        garantir(this, "app")
    }

    companion object {
        @Volatile
        lateinit var contexto: Context

        /** Liga tudo o que precisa ficar ligado. Pode chamar quantas vezes quiser. */
        fun garantir(ctx: Context, quem: String) {
            Laco.iniciar(ctx.applicationContext)
            Servico.ligar(ctx.applicationContext, quem)
            agendarVigia(ctx.applicationContext)
            Robo.religarSePuder(ctx.applicationContext)
        }

        fun agendarVigia(ctx: Context) {
            try {
                val js = ctx.getSystemService(JobScheduler::class.java)
                if (js.getPendingJob(1) != null) return
                val job = JobInfo.Builder(1, ComponentName(ctx, Vigia::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(job)
            } catch (e: Exception) {
                Registro.anotar("ERRO vigia: ${e.message}")
            }
        }

        fun podeEscreverAjustes(ctx: Context) =
            ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

        fun hora(): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())

        fun abrirTela(ctx: Context, i: Intent) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }

        fun acessibilidadeNaLista(ctx: Context): Boolean {
            val t = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return t.contains(ctx.packageName + "/", ignoreCase = true)
        }
    }
}

/** Registro curto em memória; vai para o servidor no relatório (registro_redmi). */
object Registro {
    private val novas = ArrayList<String>()
    private val ultimas = ArrayDeque<String>()

    @Synchronized
    fun anotar(l: String) {
        val linha = App.hora() + " " + l
        novas.add(linha)
        if (novas.size > 300) novas.removeAt(0)
        ultimas.addLast(linha)
        if (ultimas.size > 200) ultimas.removeFirst()
        android.util.Log.i("PainelRedmi", l)
    }

    @Synchronized
    fun pegarNovas(): List<String> {
        val r = ArrayList(novas)
        novas.clear()
        return r
    }

    @Synchronized
    fun devolver(l: List<String>) {
        novas.addAll(0, l)
        while (novas.size > 300) novas.removeAt(0)
    }

    @Synchronized
    fun ultimas(n: Int): List<String> = ultimas.toList().takeLast(n)
}
