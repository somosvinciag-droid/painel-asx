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
            medirInicio("conector") { Conector.iniciar(app) }
            medirInicio("atualizador") { Atualizador.agendar(app) }
            medirInicio("lugares") { Lugares.iniciar(app) }
            medirInicio("leitor do carro") { LeitorCarro.iniciar(app) }
            medirInicio("celular") { CaboCelular.iniciar(app) }
        }, "inicio").start()
        try {
            registerReceiver(desligando, android.content.IntentFilter(Intent.ACTION_SHUTDOWN))
        } catch (_: Exception) {
        }
    }

    /** A central vai desligar: fecha a viagem em andamento. */
    private val desligando = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = Viagens.centralDesligando()
    }

    /** A cada 30 s: se o Controle estiver desligado, mostra o aviso "toque para religar". */
    private val vigia = android.os.Handler(android.os.Looper.getMainLooper())
    private var avisou = false
    private val olharControle = object : Runnable {
        override fun run() {
            try {
                if (Controle.ligado(this@ServicoFixo)) {
                    if (avisou) { Avisos.limpar(this@ServicoFixo, Avisos.ID_CONTROLE); avisou = false }
                } else if (!avisou) {
                    Avisos.controleDesligado(this@ServicoFixo)
                    Registro.anotar("aviso: Controle desligado, pedi para religar")
                    avisou = true
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
