package br.com.leonardo.painelasx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Servico "fixo" (com um aviso pequeno na barra de cima).
 * Com ele, a central nao fecha o Painel ASX quando falta memoria, e o conector
 * do Claude continua ligado mesmo com o app fora da tela.
 */
class ServicoFixo : Service() {

    companion object {
        private const val CANAL = "painel_fixo"
        private const val ID = 4811

        fun iniciar(ctx: Context) {
            try {
                val i = Intent(ctx, ServicoFixo::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                Registro.anotar("servico fixo: nao iniciou (${e.javaClass.simpleName}: ${e.message})")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Registro.iniciar(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Painel ASX ligado", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Mantém o Painel ASX e o conector do Claude ligados."
                    setShowBadge(false)
                }
            )
        }
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Painel ASX ligado")
            .setContentText("Conectado ao seu Claude")
            .setOngoing(true)
            .setContentIntent(abrir)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        Registro.anotar("servico fixo: ligado (${android.os.SystemClock.elapsedRealtime() - App.inicio} ms depois de o app começar)")
        // A central reinicia quando o carro dá a partida: se ligou há pouco, é o carro ligando.
        // (Normalmente o App já saudou; aqui é reserva. O Jarvis não sauda 2 vezes na mesma partida.)
        if (android.os.SystemClock.elapsedRealtime() < 4 * 60_000L) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { Jarvis.partida(this) }
        }
        // Na linha principal só o que precisa dela (voz do Android, barra, relógio); o resto em segundo plano,
        // para a tela e a saudação não ficarem esperando (na partida de 31/10 a linha principal ficou ~15 s ocupada).
        medirInicio("voz") { Voz.iniciar(this) }
        medirInicio("barra") { Barra.mostrar(this) }
        medirInicio("relogio") { Relogio.iniciar(this) }
        val app = applicationContext
        Thread({
            // Celular primeiro (é leve): com o cabo já plugado quando a central acorda, ele volta em segundos.
            medirInicio("celular") { CaboCelular.iniciar(app) }
            medirInicio("conector") { Conector.iniciar(app) }
            medirInicio("atualizador") { Atualizador.agendar(app) }
            medirInicio("lugares") { Lugares.iniciar(app) }
            medirInicio("leitor do carro") { LeitorCarro.iniciar(app) }
            medirInicio("dados do carro") { DadosCarro.iniciar(app) }
        }, "inicio").start()
        // Vigia do Controle (v36): só começa uma vez; o aviso "religar" só aparece depois de uma ATUALIZAÇÃO.
        if (!vigiando) { vigiando = true; vigia.postDelayed(olharControle, 30_000) }
        try {
            registerReceiver(desligando, android.content.IntentFilter(Intent.ACTION_SHUTDOWN))
        } catch (_: Exception) {
        }
    }

    /** A central vai desligar: fecha a viagem em andamento. */
    private val desligando = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = Viagens.centralDesligando()
    }

    /**
     * A cada 30 s olha o Controle (Acessibilidade).
     * REGRA (v36): o Controle cair em partidas/sonecas é NORMAL e "sem alerta" (CLAUDE.md). O aviso "religar" só
     * aparece depois de uma ATUALIZAÇÃO do app — que é quando a central desliga a Acessibilidade e o modelo de
     * atualização precisa que o Leonardo religue. Como sabemos que atualizou: guardamos em "config/controle_versao"
     * o VERSION_CODE da última vez em que o Controle estava LIGADO. Se agora está desligado e aquela versão é menor
     * que a de agora (ou nunca foi guardada), houve atualização desde a última vez ligada -> avisa. Depois que o
     * Leonardo religa, guardamos a versão de agora, então as sonecas seguintes (mesma versão) ficam caladas.
     */
    private val vigia = android.os.Handler(android.os.Looper.getMainLooper())
    private var avisou = false
    @Volatile private var vigiando = false
    private val olharControle = object : Runnable {
        override fun run() {
            try {
                val p = getSharedPreferences("config", Context.MODE_PRIVATE)
                if (Controle.ligado(this@ServicoFixo)) {
                    if (avisou) { Avisos.limpar(this@ServicoFixo, Avisos.ID_CONTROLE); avisou = false }
                    if (p.getInt("controle_versao", 0) != BuildConfig.VERSION_CODE)
                        p.edit().putInt("controle_versao", BuildConfig.VERSION_CODE).apply()
                } else {
                    val versaoLigado = p.getInt("controle_versao", 0)
                    val atualizou = versaoLigado < BuildConfig.VERSION_CODE
                    if (atualizou && !avisou) {
                        Avisos.controleDesligado(this@ServicoFixo)
                        Registro.anotar("aviso: Controle desligado DEPOIS de atualizar (era v$versaoLigado, agora v${BuildConfig.VERSION_CODE}), pedi para religar")
                        avisou = true
                    } else if (!atualizou && avisou) {
                        Avisos.limpar(this@ServicoFixo, Avisos.ID_CONTROLE); avisou = false
                    }
                }
            } catch (_: Exception) {
            }
            vigia.postDelayed(this, 30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Conector.iniciar(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        vigia.removeCallbacks(olharControle)
        try { unregisterReceiver(desligando) } catch (_: Exception) { }
        Registro.anotar("servico fixo: desligado pelo Android")
        super.onDestroy()
    }
}
