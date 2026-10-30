package br.com.leonardo.painelasx

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizacao automatica.
 * Pergunta ao GitHub qual e a versao mais nova publicada. Se for maior do
 * que a instalada, baixa o APK e instala. Com a Acessibilidade ligada, o
 * proprio app toca em "Instalar" na janela do Android.
 */
object Atualizador {
    const val NOME_APK = "painel-asx.apk"
    private const val JOB_ID = 4811
    @Volatile private var ocupado = false

    private const val PERIODO = 15 * 60 * 1000L // de 15 em 15 minutos (tambem e o "despertador" do app)

    fun agendar(ctx: Context) {
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(JOB_ID)?.intervalMillis == PERIODO) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, AtualizaJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODO)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }

    /** Se a atualizacao acontecer com o app aberto, ele abre de novo sozinho depois. */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("atualizacao", Context.MODE_PRIVATE)

    fun marcarReabrir(ctx: Context, sim: Boolean) =
        prefs(ctx).edit().putBoolean("reabrir", sim).apply()

    fun reabrirSePrecisar(ctx: Context) {
        if (!prefs(ctx).getBoolean("reabrir", false)) return
        marcarReabrir(ctx, false)
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Registro.anotar("atualizacao: reabri o app (v${BuildConfig.VERSION_CODE})")
        } catch (e: Exception) {
            Registro.anotar("atualizacao: nao consegui reabrir (${e.message})")
        }
    }

    /** Se true, instala sem perguntar. Padrao: false (o Leonardo aprova cada atualizacao). */
    fun sozinho(ctx: Context) = prefs(ctx).getBoolean("sozinho", false)
    fun mudarSozinho(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sozinho", v).apply()

    /** Numero da versao nova ja encontrada e esperando aprovacao (0 = nenhuma). */
    fun disponivel(ctx: Context): Int {
        val n = prefs(ctx).getInt("disponivel", 0)
        return if (n > BuildConfig.VERSION_CODE) n else 0
    }

    /** Verificacao automatica (despertador, central acordou, app abriu): so AVISA, a nao ser que "sozinho" esteja ligado. */
    fun verificarEmSegundoPlano(ctx: Context) {
        val app = ctx.applicationContext
        Thread { verificar(app, instalar = sozinho(app)) { } }.start()
    }

    /**
     * Roda fora da tela principal (faz internet). [aviso] recebe frases para mostrar.
     * instalar=false: so descobre e avisa ("Atualização disponível"). instalar=true: baixa e instala
     * (usado quando o Leonardo aprova: toque no aviso, botão no app ou pedido pelo Claude).
     */
    fun verificar(ctx: Context, instalar: Boolean = true, aviso: (String) -> Unit) {
        if (ocupado) { aviso("Já estou verificando."); return }
        ocupado = true
        try {
            val url = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "painel-asx")
            if (c.responseCode != 200) {
                aviso("Não consegui falar com o GitHub (código ${c.responseCode}).")
                return
            }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name")
            val numero = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (numero <= BuildConfig.VERSION_CODE) {
                aviso("Você já está na versão mais nova (v${BuildConfig.VERSION_CODE}).")
                return
            }
            val assets = json.getJSONArray("assets")
            var link: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == NOME_APK) link = a.optString("browser_download_url")
            }
            if (link == null) { aviso("A versão $tag ainda não tem o arquivo do app."); return }

            if (!instalar) {
                if (prefs(ctx).getInt("disponivel", 0) != numero) Registro.anotar("atualizacao: $tag disponivel, esperando aprovacao")
                prefs(ctx).edit().putInt("disponivel", numero).apply()
                Avisos.atualizacaoDisponivel(ctx, tag)
                aviso("Versão $tag disponível. Toque em \"Atualizar\" para instalar.")
                return
            }
            Avisos.limpar(ctx, Avisos.ID_ATUALIZACAO)

            aviso("Baixando a versão $tag…")
            Registro.anotar("atualizacao: baixando $tag")
            val destino = File(ctx.cacheDir, "atualizacao.apk")
            baixar(link, destino)
            aviso("Instalando a versão $tag…")
            instalar(ctx, destino)
        } catch (e: Exception) {
            Registro.anotar("atualizacao: erro ${e.javaClass.simpleName}: ${e.message}")
            aviso("Sem internet ou erro ao atualizar. Tento de novo mais tarde.")
        } finally {
            ocupado = false
        }
    }

    private fun baixar(link: String, destino: File) {
        var endereco = link
        repeat(5) {
            val c = URL(endereco).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "painel-asx")
            val code = c.responseCode
            if (code in 300..399) {
                endereco = c.getHeaderField("Location") ?: throw Exception("redirecionamento sem destino")
                return@repeat
            }
            if (code != 200) throw Exception("download deu $code")
            c.inputStream.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
            return
        }
        throw Exception("redirecionamentos demais")
    }

    private fun instalar(ctx: Context, apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Registro.anotar("atualizacao: falta permitir 'instalar apps desconhecidos'")
            return
        }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app", 0, apk.length()).use { saida ->
                apk.inputStream().use { it.copyTo(saida) }
                s.fsync(saida)
            }
            marcarReabrir(ctx, MainActivity.naTela)
            Controle.confirmarInstalacao = true
            val i = Intent(ctx, ResultadoInstalacao::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val p = PendingIntent.getBroadcast(ctx, 0, i, flags)
            s.commit(p.intentSender)
        }
    }
}

/** Resposta do Android sobre a instalacao. */
class ResultadoInstalacao : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmar != null) {
                    confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirmar)
                    } catch (e: Exception) {
                        Registro.anotar("atualizacao: nao abri a confirmacao (${e.message})")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Controle.confirmarInstalacao = false
                Registro.anotar("atualizacao: instalada")
            }
            else -> {
                Controle.confirmarInstalacao = false
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Registro.anotar("atualizacao: falhou ($status) $msg")
            }
        }
    }
}

/** O Android chama isto de 3 em 3 horas, quando ha internet. */
class AtualizaJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Registro.iniciar(this)
        // Despertador: se o app tinha morrido, volta aqui (o Android 10 desta central permite).
        ServicoFixo.iniciar(applicationContext)
        Thread {
            Atualizador.verificar(applicationContext, instalar = Atualizador.sozinho(applicationContext)) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?) = true
}

/**
 * Central ligou / app foi atualizado: agenda a verificacao.
 * A rotina NAO roda daqui: quem a roda e o Controle, quando a Acessibilidade
 * liga (o Android so deixa abrir apps em segundo plano a partir dele).
 */
class Inicio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Registro.iniciar(ctx)
        Registro.anotar("central: ${intent.action?.substringAfterLast('.')}")
        Atualizador.agendar(ctx)
        // Ao ligar a central e depois de atualizar, o Android deixa iniciar o servico fixo daqui.
        ServicoFixo.iniciar(ctx)
        // Depois de uma atualizacao, quem reabre o app e o Controle, quando o Android o religa
        // (daqui o Android nao deixa abrir telas).
    }
}
