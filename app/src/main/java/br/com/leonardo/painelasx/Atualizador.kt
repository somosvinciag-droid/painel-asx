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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Atualizacao automatica.
 * Pergunta ao GitHub qual e a versao mais nova publicada. Se for maior do
 * que a instalada, baixa o APK e instala. Com a Acessibilidade ligada, o
 * proprio app toca em "Instalar" na janela do Android.
 */
object Atualizador {
    const val NOME_APK = "painel-asx.apk"
    private const val JOB_ID = 4811
    /** Só uma verificação/download por vez (o despertador, o Controle e o botão podem chamar juntos). */
    private val ocupado = AtomicBoolean(false)

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
        if (!ocupado.compareAndSet(false, true)) { aviso("Já estou verificando."); return }
        try {
            val json = ultimaVersao(ctx) ?: run { aviso("Não consegui falar com o GitHub."); return }
            val tag = json.optString("tag_name")
            val numero = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (numero <= BuildConfig.VERSION_CODE) {
                limparBaixados(ctx, null)
                aviso("Você já está na versão mais nova (v${BuildConfig.VERSION_CODE}).")
                return
            }
            val assets = json.getJSONArray("assets")
            var link: String? = null
            var tamanho = -1L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == NOME_APK) { link = a.optString("browser_download_url"); tamanho = a.optLong("size", -1L) }
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
            // Um arquivo por versão: o pedaço já baixado de uma versão nunca se mistura com outra.
            val destino = File(ctx.cacheDir, "atualizacao-$numero.apk")
            limparBaixados(ctx, destino)
            if (destino.exists() && tamanho > 0 && destino.length() != tamanho) destino.delete()
            if (!destino.exists()) baixarComPaciencia(link, destino)
            conferirApk(ctx, destino, tamanho)
            aviso("Instalando a versão $tag…")
            if (!instalar(ctx, destino)) aviso("Falta permitir \"instalar apps desconhecidos\" para o Painel ASX.")
        } catch (e: Exception) {
            Registro.anotar("atualizacao: erro ${e.javaClass.simpleName}: ${e.message}")
            aviso("Sem internet ou erro ao atualizar. Tento de novo mais tarde.")
        } finally {
            ocupado.set(false)
        }
    }

    /**
     * Pergunta ao GitHub a última versão. Tenta 3 vezes: com a internet do celular por Bluetooth a conexão
     * às vezes cai antes da resposta ("unexpected end of stream"), e na segunda tentativa costuma dar certo.
     */
    private fun ultimaVersao(ctx: Context): JSONObject? {
        // Pergunta "mudou desde a última vez?" (ETag): se não mudou, o GitHub responde 304 sem o conteúdo
        // e usamos a resposta guardada. A pergunta de 15 em 15 min fica menor e não gasta o limite do GitHub.
        val cache = ctx.getSharedPreferences("atualizacao_cache", Context.MODE_PRIVATE)
        val etag = cache.getString("etag", null)
        val guardada = cache.getString("json", null)
        var erro: Exception? = null
        for (t in 1..3) {
            val c = URL("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "painel-asx")
                if (etag != null && guardada != null) c.setRequestProperty("If-None-Match", etag)
                val code = c.responseCode
                if (code == 304 && guardada != null) {
                    try { return JSONObject(guardada) } catch (_: Exception) { cache.edit().clear().apply(); return null }
                }
                if (code != 200) { Registro.anotar("atualizacao: GitHub respondeu $code"); return null }
                val texto = c.inputStream.bufferedReader().readText()
                val json = JSONObject(texto)
                val novaEtag = c.getHeaderField("ETag")
                if (novaEtag != null) cache.edit().putString("etag", novaEtag).putString("json", texto).apply()
                else cache.edit().clear().apply()
                return json
            } catch (e: java.io.IOException) {
                erro = e
                if (t < 3) Thread.sleep(3000L * t)
            } finally {
                c.disconnect()
            }
        }
        throw erro ?: Exception("GitHub não respondeu")
    }

    /** Baixa continuando de onde parou; se a conexão cair no meio, tenta mais 3 vezes antes de desistir. */
    private fun baixarComPaciencia(link: String, destino: File) {
        var erro: Exception? = null
        for (t in 1..4) {
            try {
                Arquivo.baixar(link, destino)
                return
            } catch (e: Exception) {
                erro = e
                val parte = File(destino.parentFile, destino.name + ".parte")
                Registro.anotar("atualizacao: download parou (${e.javaClass.simpleName}: ${e.message}); " +
                    "${parte.length() / 1024} KB guardados" + (if (t < 4) ", continuo de onde parou" else ""))
                if (t < 4) Thread.sleep(3000L * t)
            }
        }
        throw erro ?: Exception("download falhou")
    }

    /** Confere o APK antes de instalar: tamanho igual ao do GitHub e o Android consegue ler (é mesmo o Painel ASX, mais novo). */
    private fun conferirApk(ctx: Context, apk: File, tamanho: Long) {
        if (tamanho > 0 && apk.length() != tamanho) {
            apk.delete()
            throw Exception("APK com tamanho errado (${apk.length()} de $tamanho bytes); apaguei para baixar de novo")
        }
        val info = try { ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) } catch (_: Exception) { null }
        @Suppress("DEPRECATION")
        val versao = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: -1L
        if (info == null || info.packageName != ctx.packageName || versao <= BuildConfig.VERSION_CODE) {
            apk.delete()
            throw Exception("APK estragado ou errado (pacote ${info?.packageName}, versão $versao); apaguei para baixar de novo")
        }
    }

    /** Apaga APKs e pedaços de versões antigas (só fica o [manter] e o pedaço dele). */
    private fun limparBaixados(ctx: Context, manter: File?) {
        try {
            ctx.cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("atualizacao") && (manter == null ||
                        (f.name != manter.name && f.name != manter.name + ".parte"))) f.delete()
            }
        } catch (_: Exception) { }
    }

    /** false = falta a permissão de instalar apps desconhecidos. */
    private fun instalar(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Registro.anotar("atualizacao: falta permitir 'instalar apps desconhecidos'")
            return false
        }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            try {
                s.openWrite("app", 0, apk.length()).use { saida ->
                    apk.inputStream().use { it.copyTo(saida) }
                    s.fsync(saida)
                }
            } catch (e: Exception) {
                try { s.abandon() } catch (_: Exception) { }   // não deixa sessão de instalação pendurada
                throw e
            }
            marcarReabrir(ctx, MainActivity.naTela)
            Controle.confirmarInstalacao = true
            val i = Intent(ctx, ResultadoInstalacao::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val p = PendingIntent.getBroadcast(ctx, 0, i, flags)
            s.commit(p.intentSender)
        }
        return true
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
        val acao = intent.action ?: ""
        val estado = intent.extras?.let { e -> @Suppress("DEPRECATION") e.get("extra_mcu_state")?.toString() } ?: ""
        Registro.anotar("central: ${acao.substringAfterLast('.')}" + (if (estado.isNotEmpty()) " (estado $estado)" else ""))
        Atualizador.agendar(ctx)
        when {
            acao == Despertador.ACAO -> { Despertador.talvezAcordou(ctx, "alarme"); return }
            acao == "com.nwd.action.ACTION_MCU_STATE_CHANGE" && estado == "3" -> { Despertador.marcar(ctx.applicationContext); return }
            acao == "com.nwd.action.ACTION_MCU_STATE_CHANGE" || acao == "com.nwd.ACTION_AFTER_BOOT_COMPLETED" ||
                acao.endsWith("QUICKBOOT_POWERON") -> { Despertador.talvezAcordou(ctx, acao.substringAfterLast('.')); return }
        }
        // Ao ligar a central e depois de atualizar, o Android deixa iniciar o servico fixo daqui.
        ServicoFixo.iniciar(ctx)
        // Depois de uma atualizacao, quem reabre o app e o Controle, quando o Android o religa
        // (daqui o Android nao deixa abrir telas).
    }
}
