package br.com.leonardo.painelasx.celular

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * A "ponte" com o carro: serviço em primeiro plano (aparelho conectado + microfone).
 *  - abre o servidor na ancoragem Bluetooth (fase 1) e aceita o cabo (fase 2);
 *  - confere o pareamento; manda música, capa, bateria, avisos, ligação e sinal;
 *  - recebe controles da música e o pedido de "ouvir".
 * Nada daqui vai para a internet: só para a central do carro, na rede local ou no cabo.
 */
class Ponte : Service() {

    companion object {
        val h = Handler(Looper.getMainLooper())
        private const val ID = 4812
        private const val CANAL = "ponte"
        private const val CANAL_AVISOS = "avisos"

        @Volatile var rodando = false
            private set
        /** O serviço foi ligado com o app na frente: pode usar o microfone. */
        @Volatile var comMicrofone = false
            private set
        /** O carro ligou "Ler mensagens": só então os avisos de mensagens saem do celular. */
        @Volatile var querMensagens = false
            private set
        @Volatile private var ativa: Conexao? = null
        @Volatile private var pendente: Conexao? = null
        @Volatile private var pendenteMensagens = false
        @Volatile private var servidor: ServidorRede? = null
        @Volatile private var ctx: Context? = null
        @Volatile var ultimoMotivo = ""
            private set

        fun iniciar(c: Context, comMicrofone: Boolean, acessorio: UsbAccessory? = null) {
            val i = Intent(c, Ponte::class.java).putExtra("mic", comMicrofone)
            if (acessorio != null) i.putExtra(UsbManager.EXTRA_ACCESSORY, acessorio)
            try {
                if (rodando) c.startService(i)
                else c.startForegroundService(i.putExtra("fg", true))
            } catch (e: Exception) {
                ultimoMotivo = "não liguei (${e.javaClass.simpleName})"
            }
        }

        fun ligadoAoCarro() = ativa?.viva == true
        fun transporte() = ativa?.takeIf { it.viva }?.canal?.tipo ?: ""
        fun esperandoPermissao() = pendente?.viva == true
        fun ancoragemLigada() = servidor?.ancoragemLigada == true
        fun estadoServidor() = servidor?.estado ?: "desligado"

        /** Manda ao carro (só para a central já permitida). */
        fun enviar(o: JSONObject) {
            ativa?.mandar(o)
        }

        fun estadoTexto(): String = when {
            ligadoAoCarro() -> "Ligado ao carro (" + (if (transporte() == "cabo") "cabo USB" else "rede Bluetooth") + ")"
            esperandoPermissao() -> "O carro está esperando você tocar em Permitir"
            !rodando -> "Desligado"
            !ancoragemLigada() -> "Esperando o carro (ancoragem Bluetooth desligada)"
            else -> "Esperando o carro"
        }

        // ------------------------------------------------------------ conexões

        private fun novaConexao(canal: Canal) {
            val c = Conexao(canal, 0, 20_000, { cx, o -> h.post { tratar(cx, o) } }, { cx, m -> h.post { caiu(cx, m) } })
            Thread({ c.rodar("ponte-${canal.tipo}") }, "ponte-${canal.tipo}").start()
            // A central tem de dizer "olá" (com o id dela) em 10 s.
            h.postDelayed({ if (c.viva && c !== ativa && c !== pendente) c.fechar("sem olá") }, 10_000)
        }

        private fun tratar(c: Conexao, o: JSONObject) {
            val app = ctx ?: return
            if (!c.viva) return
            val t = o.optString("t")
            if (t == "ping") { c.mandar(JSONObject().put("t", "pong")); return }
            if (t == "ola") {
                val id = o.optString("id")
                if (!Pareamento.valido(id)) { c.fechar("id inválido"); return }
                c.id = id
                when (Pareamento.situacao(app, id)) {
                    Pareamento.Situacao.PERMITIDA -> aceitar(c, o.optBoolean("mensagens", false))
                    Pareamento.Situacao.RECUSADA -> recusar(c)
                    Pareamento.Situacao.NOVA -> {
                        pendente?.takeIf { it !== c }?.fechar("outro pedido")
                        pendente = c
                        pendenteMensagens = o.optBoolean("mensagens", false)
                        c.mandar(JSONObject().put("t", "pareando").put("codigo", Pareamento.codigo(id)))
                        Pareamento.pedir(app, id)
                        atualizarAviso()
                    }
                }
                return
            }
            if (c !== ativa) return   // nada é atendido antes do Leonardo permitir
            when (t) {
                "ajustes" -> querMensagens = o.optBoolean("mensagens", querMensagens)
                "controle" -> MusicaCel.comando(o.optString("acao"), o.optLong("ms", -1))
                "ouvir" -> Escuta.ouvir(app)
                "parar" -> Escuta.parar()
            }
        }

        private fun recusar(c: Conexao) {
            c.mandar(JSONObject().put("t", "recusado"))
            h.postDelayed({ c.fechar("central recusada") }, 1500)
        }

        private fun aceitar(c: Conexao, mensagens: Boolean) {
            val app = ctx ?: return
            if (ativa !== c) ativa?.fechar("outra conexão")
            ativa = c
            if (pendente === c) pendente = null
            querMensagens = mensagens
            val versao = try { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode } catch (_: Exception) { 0L }
            c.mandar(JSONObject().put("t", "ola").put("proto", 1).put("app", versao)
                .put("modelo", Build.MODEL).put("fabricante", Build.MANUFACTURER).put("android", Build.VERSION.RELEASE)
                .put("mic", comMicrofone).put("notificacoes", Ouvinte.conectado))
            ultimaBateria = ""
            mandarBateria(app)
            MusicaCel.iniciar(app)
            MusicaCel.reenviarTudo()
            ultimoSinal = ""
            mandarSinal(app)
            estadoLigacao = ""
            checarLigacao(app)
            ultimoMotivo = ""
            atualizarAviso()
            h.removeCallbacks(tique)
            h.post(tique)
        }

        private fun caiu(c: Conexao, motivo: String) {
            if (ativa === c) {
                ativa = null
                querMensagens = false
                Escuta.parar()
                ultimoMotivo = motivo
            }
            if (pendente === c) pendente = null
            atualizarAviso()
        }

        /** O Leonardo tocou em Permitir/Recusar (ou "esquecer" na tela). */
        fun pareamentoMudou(c: Context, id: String, permitida: Boolean) {
            h.post {
                val p = pendente
                if (p != null && p.id == id) {
                    if (permitida) aceitar(p, pendenteMensagens) else recusar(p)
                }
                if (!permitida) ativa?.takeIf { id.isEmpty() || it.id == id }?.fechar("permissão retirada")
                atualizarAviso()
            }
        }

        // ------------------------------------------------------------ bateria, sinal, ligação

        @Volatile private var ultimaBateria = ""
        private var bateriaIntent: Intent? = null

        private fun mandarBateria(app: Context) {
            val i = bateriaIntent ?: try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null } ?: return
            val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / escala
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val carregando = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val chave = "$nivel/$carregando"
            if (chave == ultimaBateria) return
            ultimaBateria = chave
            enviar(JSONObject().put("t", "bateria").put("nivel", nivel).put("carregando", carregando))
        }

        @Volatile private var ultimoSinal = ""

        private fun mandarSinal(app: Context) {
            try {
                val tm = app.getSystemService(TelephonyManager::class.java) ?: return
                val operadora = tm.networkOperatorName ?: ""
                val ss = try { tm.signalStrength } catch (_: SecurityException) { null }
                val nivel = ss?.level ?: -1
                val tipos = ss?.cellSignalStrengths.orEmpty()
                val rede = when {
                    tipos.any { it is CellSignalStrengthNr } -> "5G"
                    tipos.any { it is CellSignalStrengthLte } -> "4G"
                    tipos.any { it is CellSignalStrengthWcdma || it is CellSignalStrengthTdscdma } -> "3G"
                    tipos.any { it is CellSignalStrengthGsm || it is CellSignalStrengthCdma } -> "2G"
                    else -> ""
                }
                val chave = "$operadora/$nivel/$rede"
                if (chave == ultimoSinal) return
                ultimoSinal = chave
                enviar(JSONObject().put("t", "sinal").put("operadora", operadora).put("nivel", nivel).put("rede", rede))
            } catch (_: Exception) {
            }
        }

        private var quemLiga = ""
        private var quemEm = 0L
        private var estadoLigacao = ""

        /** Aviso de chamada no celular (nome de quem liga). */
        fun chamada(quem: String) {
            if (quem.isNotBlank()) { quemLiga = quem; quemEm = SystemClock.elapsedRealtime() }
            ctx?.let { checarLigacao(it) }
        }

        fun chamadaSumiu() { ctx?.let { checarLigacao(it) } }

        /** Estado da ligação pelo modo do áudio (não precisa de permissão de telefone). */
        private fun checarLigacao(app: Context) {
            val am = app.getSystemService(AudioManager::class.java) ?: return
            val e = when (am.mode) {
                AudioManager.MODE_RINGTONE -> "tocando"
                AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING -> "atendida"
                else -> "fim"
            }
            if (e == estadoLigacao) return
            estadoLigacao = e
            val quem = if (e != "fim" && SystemClock.elapsedRealtime() - quemEm < 5 * 60_000) quemLiga else ""
            enviar(JSONObject().put("t", "ligacao").put("estado", e).put("quem", quem))
        }

        private var tiques = 0
        private val tique = object : Runnable {
            override fun run() {
                val app = ctx ?: return
                if (!rodando || !ligadoAoCarro()) return   // volta a rodar na próxima conexão
                tiques++
                try {
                    checarLigacao(app)
                    if (tiques % 5 == 0) MusicaCel.tique()
                    if (tiques % 30 == 0) mandarSinal(app)
                } catch (_: Exception) { }
                h.postDelayed(this, 1000)
            }
        }

        // ------------------------------------------------------------ avisos na barra

        private var ultimoTextoAviso = ""

        private fun atualizarAviso() {
            val app = ctx ?: return
            if (!rodando) return
            val t = estadoTexto()
            if (t == ultimoTextoAviso) return
            ultimoTextoAviso = t
            try { app.getSystemService(NotificationManager::class.java).notify(ID, aviso(app, t)) } catch (_: Exception) { }
        }

        private fun aviso(app: Context, texto: String): Notification {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL, "Ligação com o carro", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mostra se o celular está ligado ao Painel ASX do carro."
                    setShowBadge(false)
                })
            }
            // Tocar abre o app: com ele na frente, a ponte ganha o microfone.
            val abrir = PendingIntent.getActivity(app, 40, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(app, CANAL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Painel ASX")
                .setContentText(texto + if (!comMicrofone) " · toque para liberar o microfone" else "")
                .setContentIntent(abrir)
                .setOngoing(true)
                .build()
        }

        private var avisouMicEm = 0L

        /** O carro pediu para ouvir, mas a ponte está sem microfone: um toque aqui resolve. */
        fun avisarMicrofone(app: Context) {
            val agora = SystemClock.elapsedRealtime()
            if (avisouMicEm != 0L && agora - avisouMicEm < 10 * 60_000) return
            avisouMicEm = agora
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL_AVISOS) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, "Avisos do Painel ASX", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val abrir = PendingIntent.getActivity(app, 41, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(app, CANAL_AVISOS)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Microfone para o carro")
                .setContentText("Toque aqui uma vez para o carro poder usar o microfone do celular.")
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
            try { nm.notify(22, n) } catch (_: Exception) { }
        }
    }

    // ---------------------------------------------------------------- o serviço

    private var emPrimeiroPlano = false

    private val bateria = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            bateriaIntent = i
            if (ligadoAoCarro()) mandarBateria(c.applicationContext)
        }
    }

    private val caboSaiu = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            ativa?.takeIf { it.canal.tipo == "cabo" }?.fechar("cabo solto")
            pendente?.takeIf { it.canal.tipo == "cabo" }?.fechar("cabo solto")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
        rodando = true
        val s = ServidorRede { canal, _ -> novaConexao(canal) }
        servidor = s
        s.iniciar()
        registrar(bateria, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registrar(caboSaiu, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED))
        MusicaCel.contexto(this)
        MusicaCel.iniciar(this)
    }

    private fun registrar(r: BroadcastReceiver, f: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val querMic = intent?.getBooleanExtra("mic", false) == true
        if (intent == null || intent.getBooleanExtra("fg", false) || !emPrimeiroPlano ||
            (querMic && !comMicrofone && temPermissaoMic())) {
            if (!primeiroPlano(querMic)) return START_NOT_STICKY
        }
        val acc: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        if (acc != null) abrirAcessorio(acc)
        return START_STICKY
    }

    private fun temPermissaoMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Liga o primeiro plano. Com o app na frente, inclui o microfone (Android 14+ exige isso). */
    private fun primeiroPlano(querMic: Boolean): Boolean {
        val mic = (querMic || comMicrofone) && temPermissaoMic()
        var tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mic) tipos = tipos or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ultimoTextoAviso = estadoTexto()
        return try {
            comMicrofone = mic
            startForeground(ID, aviso(this, ultimoTextoAviso), tipos)
            emPrimeiroPlano = true
            true
        } catch (e: Exception) {
            // Microfone recusado (app fora da tela): fica só com a ligação ao carro.
            comMicrofone = false
            try {
                startForeground(ID, aviso(this, ultimoTextoAviso), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                emPrimeiroPlano = true
                true
            } catch (e2: Exception) {
                ultimoMotivo = "o Android não deixou ligar (${e2.javaClass.simpleName})"
                stopSelf()
                false
            }
        }
    }

    /** Fase 2: a central pediu o modo acessório pelo cabo e o Android abriu a AcessorioActivity. */
    private fun abrirAcessorio(acc: UsbAccessory) {
        Thread({
            try {
                val um = getSystemService(UsbManager::class.java)
                if (!um.hasPermission(acc)) { ultimoMotivo = "cabo: sem permissão para o acessório"; return@Thread }
                val pfd = um.openAccessory(acc) ?: run { ultimoMotivo = "cabo: não abriu"; return@Thread }
                novaConexao(CanalAcessorio(pfd))
            } catch (e: Exception) {
                ultimoMotivo = "cabo: ${e.javaClass.simpleName}"
            }
        }, "ponte-cabo-abrir").start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        rodando = false
        emPrimeiroPlano = false
        comMicrofone = false
        servidor?.encerrar()
        servidor = null
        ativa?.fechar("serviço desligado")
        pendente?.fechar("serviço desligado")
        try { unregisterReceiver(bateria) } catch (_: Exception) { }
        try { unregisterReceiver(caboSaiu) } catch (_: Exception) { }
        super.onDestroy()
    }
}

/** Celular ligou ou o app foi atualizado: liga a ponte (sem microfone; ele vem quando o app é aberto). */
class Inicio : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Ponte.iniciar(c.applicationContext, comMicrofone = false)
    }
}
