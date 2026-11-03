package br.com.leonardo.painelasx

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings

/**
 * v36 — lê TUDO o que a central NWD deixa ler do carro, 100% SÓ LEITURA.
 *
 * NUNCA escreve nada no MCU/carro. Só:
 *  - escuta avisos (broadcast) de dado seguros (tensão, faróis, teclas do volante, portas, radar, mídia, ligação BT,
 *    USB/SD, energia);
 *  - lê ajustes internos (Settings.System) por consulta + observador (volume, fonte de áudio, app na frente, ré...);
 *  - registra os callbacks de LEITURA do CanService (velocidade, RPM, combustível, tensão, marcha, portas, radar, ar).
 *
 * Cada dado vai para o Registro com o prefixo "carro:" (o Claude lê por registro_central filtro "carro:") e o último
 * valor de cada sinal entra no diagnóstico ("--- dados do carro (v36) ---"). Nada aqui envia comando, tecla, nem muda
 * qualquer ajuste. É ligado de dentro do Ouvinte (sobrevive ao force-stop) e também na partida (ServicoFixo).
 *
 * Privacidade: ligação BT -> só o ESTADO (nunca número/contato); teclas do volante -> só o código.
 */
object DadosCarro {

    @Volatile private var iniciado = false
    @Volatile private var appCtx: Context? = null

    // Último valor de cada sinal (para o diagnóstico) e quando foi logado por último (para não encher o registro).
    private val ultimos = LinkedHashMap<String, String>()
    private val logadoEm = HashMap<String, Long>()

    private var ioThread: HandlerThread? = null
    private var io: Handler? = null

    // ------------------------------------------------------------------ início (idempotente, leve, fora da linha principal)

    @Synchronized
    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        val app = ctx.applicationContext
        appCtx = app
        try {
            ioThread = HandlerThread("dados-carro").apply { start() }
            io = Handler(ioThread!!.looper)
        } catch (_: Throwable) {
        }
        val h = io ?: Handler(app.mainLooper)
        h.post {
            try { escutarAvisos(app) } catch (e: Throwable) { Registro.anotar("ERRO carro(avisos): ${e.javaClass.simpleName}: ${e.message}") }
            try { lerSticky(app) } catch (_: Throwable) { }
            try { lerSettings(app) } catch (e: Throwable) { Registro.anotar("ERRO carro(settings): ${e.javaClass.simpleName}: ${e.message}") }
            try { observarSettings(app) } catch (_: Throwable) { }
            try { ligarCan(app) } catch (e: Throwable) { Registro.anotar("ERRO carro(CAN): ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ guardar + logar (com limite para não encher)

    @Synchronized
    private fun registrar(rotulo: String, valor: String, minMs: Long) {
        val antes = ultimos[rotulo]
        ultimos[rotulo] = valor
        val agora = System.currentTimeMillis()
        if (antes != valor || agora - (logadoEm[rotulo] ?: 0L) >= minMs) {
            logadoEm[rotulo] = agora
            Registro.anotar("carro: $rotulo — $valor".trim())
        }
    }

    // ------------------------------------------------------------------ avisos (broadcast) de dado SEGUROS

    private val AVISOS = listOf(
        "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE",
        "com.nwd.ACTION_ILL_STATE_CHANGE",
        "com.nwd.ACTION_BACKLIGHT_SWITCH_CHANGE",
        "com.nwd.action.ACTION_KEY_VALUE",
        "com.nwd.action.ACTION_TEST_KEY",
        "com.nwd.ACTION_CAN_DOOR_STATE_CHANGE",
        "com.nwd.ACTION_CAN_DOOR_WINDOW",
        "com.nwd.ACTION_CAN_RADAR_STATE_CHANGE",
        "com.nwd.can.ACTION_BACK_SHOW_RADAR",
        "com.nwd.android.ACTION_STREAM_MUSIC",
        "com.nwd.MEDIA_LIST_CHANGE",
        "com.bt.ACTION_BT_BEGIN_CALL_ONLINE",
        "com.bt.ACTION_BT_INCOMING_CALL",
        "com.bt.ACTION_BT_END_CALL",
        "com.bt.ACTION_BT_OUTGOING_CALL",
        "com.nwd.ACTION_TIPS_INSERT_USB",
        "com.nwd.ACTION_TIPS_INSERT_SD",
        "com.nwd.ACTION_TIPS_INSERT_DISK",
        "com.nwd.ACTION_TIPS_CONNECT_IPOD",
        "com.nwd.action.ACTION_MCU_STATE_CHANGE"
    )

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { tratarAviso(i) } catch (_: Throwable) { }
        }
    }

    private fun escutarAvisos(ctx: Context) {
        val f = IntentFilter()
        AVISOS.forEach { f.addAction(it) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receptor, f, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receptor, f)
        }
        Registro.anotar("carro: escutando ${AVISOS.size} avisos de dado (v36)")
    }

    /** Avisos "grudados" (sticky): tenta pegar o último valor que já foi mandado antes de o app ligar. */
    private fun lerSticky(ctx: Context) {
        for (a in listOf(
            "com.nwd.action.ACTION_CAN_BATTERY_VOLTAGE",
            "com.nwd.ACTION_ILL_STATE_CHANGE",
            "com.nwd.action.ACTION_MCU_STATE_CHANGE"
        )) {
            try {
                val i = ctx.registerReceiver(null, IntentFilter(a))
                if (i != null) tratarAviso(i)
            } catch (_: Throwable) {
            }
        }
    }

    private fun tratarAviso(i: Intent) {
        val acao = i.action ?: return
        when {
            acao.contains("ACTION_CAN_BATTERY_VOLTAGE") -> registrar("bateria", bundleStr(i.extras), 30_000)
            // Privacidade: ligação BT -> SÓ o estado, nunca número/contato (não lê os extras).
            acao.startsWith("com.bt.ACTION_BT_") -> registrar("ligação BT", estadoLigacao(acao), 0)
            // Volante: SÓ o código da tecla. NUNCA reenviar (enviar a tecla seria escrita).
            acao.contains("ACTION_KEY_VALUE") || acao.contains("ACTION_TEST_KEY") -> registrar("tecla volante", codigoTecla(i), 0)
            acao.contains("ILL_STATE") -> registrar("faróis/ill", bundleStr(i.extras), 0)
            acao.contains("BACKLIGHT_SWITCH") -> registrar("backlight", bundleStr(i.extras), 0)
            acao.contains("DOOR") -> registrar("portas (aviso)", bundleStr(i.extras), 10_000)
            acao.contains("RADAR") || acao.contains("BACK_SHOW_RADAR") -> registrar("radar (aviso)", bundleStr(i.extras), 5_000)
            acao.contains("STREAM_MUSIC") -> registrar("mídia tocando", bundleStr(i.extras), 15_000)
            acao.contains("MEDIA_LIST_CHANGE") -> registrar("mídia lista", bundleStr(i.extras), 30_000)
            acao.contains("TIPS_INSERT") || acao.contains("CONNECT_IPOD") ->
                registrar("USB/SD", acao.substringAfterLast('_') + " " + bundleStr(i.extras), 0)
            acao.contains("MCU_STATE_CHANGE") -> registrar("energia (mcu)", bundleStr(i.extras), 0)
            else -> registrar("aviso " + acao.substringAfterLast('.'), bundleStr(i.extras), 30_000)
        }
    }

    private fun estadoLigacao(acao: String): String = when {
        acao.contains("BEGIN_CALL_ONLINE") -> "em ligação"
        acao.contains("INCOMING_CALL") -> "recebendo chamada"
        acao.contains("OUTGOING_CALL") -> "ligação saindo"
        acao.contains("END_CALL") -> "ligação encerrada"
        else -> "mudança de ligação"
    }

    /** Só o código numérico da tecla (nunca mais nada). */
    private fun codigoTecla(i: Intent): String {
        val ex = i.extras ?: return "?"
        for (k in ex.keySet()) {
            val v = try { @Suppress("DEPRECATION") ex.get(k) } catch (_: Exception) { null }
            if (v is Int || v is Byte || v is Short || v is Long) return "$k=$v"
        }
        return "?"
    }

    private fun bundleStr(ex: Bundle?): String {
        if (ex == null) return "(sem dados)"
        return try {
            "{" + ex.keySet().joinToString(", ") { k ->
                "$k=" + valor(@Suppress("DEPRECATION") ex.get(k))
            } + "}"
        } catch (_: Exception) {
            "(?)"
        }.take(400)
    }

    private fun valor(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> v.take(32).joinToString(" ") { "%02x".format(it) } + (if (v.size > 32) "…" else "")
        is IntArray -> v.contentToString()
        is Bundle -> "{…}"
        else -> v.toString()
    }.take(200)

    // ------------------------------------------------------------------ ajustes internos (Settings.System) — só leitura

    private val SET_FILTRO = listOf("nwd", "mcu", "acc", "volt", "batt", "back", "vol", "white", "source", "top")
    private val SET_CONHECIDAS = listOf("mcu_backcar_state", "mcu_system_volume", "key_top_app", "mcu_current_source", "mcu_state")

    private fun lerSettings(ctx: Context) {
        val achados = LinkedHashMap<String, String>()
        for (k in SET_CONHECIDAS) {
            try {
                val v = Settings.System.getString(ctx.contentResolver, k)
                if (v != null) achados[k] = v
            } catch (_: Throwable) {
            }
        }
        try {
            ctx.contentResolver.query(Settings.System.CONTENT_URI, arrayOf("name", "value"), null, null, null)?.use { cur ->
                val iN = cur.getColumnIndex("name")
                val iV = cur.getColumnIndex("value")
                while (cur.moveToNext() && achados.size < 120) {
                    val n = (if (iN >= 0) cur.getString(iN) else null) ?: continue
                    val nl = n.lowercase()
                    if (SET_FILTRO.any { nl.contains(it) }) {
                        achados[n] = (if (iV >= 0) cur.getString(iV) else null) ?: ""
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (achados.isEmpty()) {
            registrar("Settings", "(nada lido)", 60_000)
            return
        }
        for ((k, v) in achados) registrar("Settings $k", v, 60_000)
    }

    private var observador: ContentObserver? = null

    private fun observarSettings(ctx: Context) {
        if (observador != null) return
        val h = io ?: Handler(ctx.mainLooper)
        val obs = object : ContentObserver(h) {
            @Volatile private var ultimo = 0L
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val agora = System.currentTimeMillis()
                if (agora - ultimo < 2000) return
                ultimo = agora
                try { lerSettings(ctx) } catch (_: Throwable) { }
            }
        }
        observador = obs
        ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
    }

    // ------------------------------------------------------------------ CanService (bind SÓ LEITURA, como o plugin nwd_can)

    private const val CAN_PKG = "com.nwd.can.setting"
    private const val CAN_CLS = "com.nwd.can.service.CanService"
    private const val CAN_ACAO = "com.nwd.can.service.impl.carcase.ACTION_CAN_SERVICE"
    private const val FEATURE_DESC = "com.nwd.can.sdk.outer.adil.ICanRemote4OuterFeature"
    private const val CALLBACK_DESC = "com.nwd.can.sdk.outer.adil.ICanRemoteModelCallback"
    private const val SDK_CANAL = "nwdthirdapp"
    private const val SDK_SEGREDO = "d39df3d908cf7136227987e37d5b2c7d"
    private const val SDK_DESC = "painel asx"
    private const val MARCADOR = 110 // 0x6E

    private const val TX_INIT = 2
    private val TX_CALLBACKS = intArrayOf(9, 11, 15, 17, 23, 27) // ar, porta, radar, carInfo, raw, canCarInfo (só LEITURA)

    @Volatile private var canFeature: IBinder? = null
    @Volatile private var canBound = false
    @Volatile private var canRecebidos = 0

    private fun ligarCan(ctx: Context) {
        val intent = Intent(CAN_ACAO).apply {
            setPackage(CAN_PKG)
            component = ComponentName(CAN_PKG, CAN_CLS)
        }
        val ok = try {
            ctx.applicationContext.bindService(intent, canConn, Context.BIND_AUTO_CREATE)
        } catch (e: Throwable) {
            Registro.anotar("carro: CAN não disponível (${e.javaClass.simpleName})")
            registrar("CAN", "indisponível (${e.javaClass.simpleName})", 0)
            return
        }
        canBound = ok
        if (!ok) {
            Registro.anotar("carro: CAN vazio (sem serviço — bind recusado)")
            registrar("CAN", "sem serviço (bind recusado)", 0)
            return
        }
        // Se em 20 s nenhum dado chegou, provavelmente não há caixa de CAN ligada no ASX.
        (io ?: Handler(ctx.mainLooper)).postDelayed({
            if (canRecebidos == 0) {
                Registro.anotar("carro: CAN vazio — nenhum dado em 20 s (provável sem caixa de CAN no ASX)")
                registrar("CAN", "ligado mas sem dados em 20 s (provável sem caixa)", 0)
            }
        }, 20_000)
    }

    private val canConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            canFeature = binder
            val h = io ?: Handler(appCtx?.mainLooper ?: android.os.Looper.getMainLooper())
            h.post {
                try {
                    for (c in TX_CALLBACKS) registrarCallbackCan(c)
                    initSdkCfg()
                    Registro.anotar("carro: CAN ligado (aguardando dados)")
                    registrar("CAN", "ligado (aguardando dados)", 0)
                } catch (e: Throwable) {
                    Registro.anotar("carro: CAN erro ao registrar (${e.javaClass.simpleName}: ${e.message})")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            canFeature = null
            Registro.anotar("carro: CAN desconectou")
        }
    }

    /** Registra um callback de LEITURA (igual ao nwd_can): duas vias; se não acertar, tenta oneway. */
    private fun registrarCallbackCan(code: Int) {
        val f = canFeature ?: return
        var ok = false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FEATURE_DESC)
            data.writeStrongBinder(canCallback)
            ok = f.transact(code, data, reply, 0)
            try { reply.readException() } catch (_: Exception) { }
        } catch (_: Exception) {
        } finally { reply.recycle(); data.recycle() }
        if (!ok) {
            val d2 = Parcel.obtain()
            try {
                d2.writeInterfaceToken(FEATURE_DESC)
                d2.writeStrongBinder(canCallback)
                f.transact(code, d2, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) {
            } finally { d2.recycle() }
        }
    }

    /** Autentica o canal do SDK (handshake de leitura — NÃO é comando de controle ao MCU). */
    private fun initSdkCfg() {
        val f = canFeature ?: return
        var ok = false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(FEATURE_DESC)
            data.writeString(SDK_CANAL)
            data.writeString(SDK_SEGREDO)
            data.writeByte(0)
            data.writeString(SDK_DESC)
            ok = f.transact(TX_INIT, data, reply, 0)
            try { reply.readException() } catch (_: Exception) { }
        } catch (_: Exception) {
        } finally { reply.recycle(); data.recycle() }
        if (!ok) {
            val d2 = Parcel.obtain()
            try {
                d2.writeInterfaceToken(FEATURE_DESC)
                d2.writeString(SDK_CANAL)
                d2.writeString(SDK_SEGREDO)
                d2.writeByte(0)
                d2.writeString(SDK_DESC)
                f.transact(TX_INIT, d2, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) {
            } finally { d2.recycle() }
        }
    }

    /** O binder onde o CanService empurra os dados. Só LÊ e registra; nunca responde com comando. */
    private val canCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            canRecebidos++
            if (code in 1..0x00ffffff) {
                try { data.enforceInterface(CALLBACK_DESC) } catch (_: Exception) { }
            }
            return when (code) {
                1 -> { // onDistributeCanData(byte[]) — quadro "normalizado"
                    val bytes = try { data.createByteArray() } catch (_: Exception) { null }
                    reply?.writeNoException()
                    if (bytes != null) try { tratarQuadroCan(bytes) } catch (_: Throwable) { }
                    true
                }
                2 -> { // onDistributeCarInfo(CarInfo) — objeto tipado
                    try {
                        val presente = data.readInt()
                        if (presente != 0) registrar("CAN carInfo", resumoTyped(data), 15_000)
                    } catch (_: Throwable) { }
                    reply?.writeNoException()
                    true
                }
                3, 4 -> { reply?.writeNoException(); true } // amp / tpms: só confirma, não usa
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    // ---- decodificação portada do nwd_can (RemoteProtocalParse), em resumo ----

    private fun u(b: ByteArray, i: Int): Int { if (i < 0 || i >= b.size) return 0; val v = b[i].toInt(); return if (v < 0) v + 256 else v }

    private fun dec(b: ByteArray, off: Int, len: Int): Int { // big-endian
        if (off >= b.size - 1) return 0
        var r = 0
        for (k in 0 until len) {
            var v = if (off + k < b.size) b[off + k].toInt() else 0
            if (v < 0) v += 256
            r = r or (v shl ((len - k - 1) * 8))
        }
        return r
    }

    private fun tratarQuadroCan(b: ByteArray) {
        if (b.isEmpty()) return
        if (u(b, 0) != MARCADOR) {
            val hex = b.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
            registrar("CAN bruto", "len=${b.size} $hex", 30_000)
            return
        }
        when (u(b, 1)) {
            1 -> registrar("CAN ar", resumoAc(b), 15_000)
            2 -> registrar("CAN carInfo", resumoCarInfo(b), 15_000)
            3 -> registrar("CAN portas", resumoPortas(b), 10_000)
            4 -> registrar("CAN radar", resumoRadar(b), 5_000)
            else -> registrar("CAN sub${u(b, 1)}", b.take(24).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }, 30_000)
        }
    }

    private fun resumoCarInfo(b: ByteArray): String {
        fun i16(o: Int) = dec(b, o, 2).let { if (it == 65535) -1 else it }
        val vel = i16(3); val rpm = i16(5)
        val comb = u(b, 17).let { if (it == 255) -1 else it }
        val tensaoRaw = u(b, 18).let { if (it == 255) -1 else it }
        val tensao = if (tensaoRaw < 0) "?" else "%.1fV(raw=%d)".format(tensaoRaw / 10.0, tensaoRaw)
        val freioMao = u(b, 16); val cinto = u(b, 13)
        val piscaE = u(b, 31); val piscaD = u(b, 30)
        val farolAlto = u(b, 24); val farolBaixo = u(b, 25)
        val portas = "FL${u(b,19)}FR${u(b,20)}RL${u(b,21)}RR${u(b,22)}ML${u(b,23)}"
        val sb = StringBuilder("vel=$vel rpm=$rpm comb=$comb% tensao=$tensao freioMao=$freioMao cinto=$cinto " +
            "piscaE=$piscaE piscaD=$piscaD farolAlto=$farolAlto farolBaixo=$farolBaixo portas=$portas")
        if (b.size > 103) sb.append(" marcha=${u(b,74)} modo=${u(b,80)} coolant=${u(b,93)}/${u(b,100)}")
        return sb.toString()
    }

    private fun resumoAc(b: ByteArray): String {
        val b3 = u(b, 3); val b5 = u(b, 5)
        return "liga=${(b3 shr 7) and 1} recirc=${(b3 shr 4) and 1} auto=${(b3 shr 5) and 1} vent=${b5 and 15} " +
            "tempE=${u(b,10)} tempD=${u(b,11)}"
    }

    private fun resumoPortas(b: ByteArray): String {
        val v = u(b, 3)
        fun on(n: Int) = if (((v shr n) and 1) == 1) "aberta" else "ok"
        return "FL=${on(7)} FR=${on(6)} RL=${on(5)} RR=${on(4)} porta-malas=${on(3)} capô=${on(2)}"
    }

    private fun resumoRadar(b: ByteArray): String =
        "tras=${u(b,3)},${u(b,4)},${u(b,5)},${u(b,6)} frente=${u(b,7)},${u(b,8)},${u(b,9)},${u(b,10)}"

    /** Lê o CarInfo tipado NA ORDEM EXATA do SDK (como o nwd_can) e tira os campos úteis. */
    private fun resumoTyped(p: Parcel): String {
        return try {
            p.readInt(); p.readInt(); p.readInt(); p.readInt()        // mDrivingMile,1,2,3
            p.readInt(); p.readInt(); p.readInt()                     // mCanDriverMileage,2,3
            p.readFloat(); p.readFloat(); p.readFloat()               // mElecPow1,2,3
            p.readInt(); p.readInt(); p.readInt()                     // mElecCanDriver1,2,3
            p.readFloat(); p.readFloat()                              // mTRIPAMile, mTRIPBMile
            val vel = p.readInt()
            p.readInt(); p.readInt(); p.readInt()                     // mEquallySpeed,2,3
            p.readByte()                                              // mSpeedUnit
            p.readInt()                                               // mDriverTime1
            p.readInt(); p.readInt()                                  // mDriverTime2,3
            val rpm = p.readInt()
            val coolant = p.readFloat()
            p.readFloat()                                             // mInstantanOil
            p.readFloat(); p.readFloat(); p.readFloat()               // mAverageOil,2,3
            p.readByte()                                              // mOilConsumptionUnit
            val comb = p.readFloat()
            p.readByte()                                              // mOilLowWarning
            val tensao = p.readFloat()
            "vel=$vel rpm=$rpm comb=%.0f%% tensao=%.1fV coolant=%.0f".format(comb, tensao, coolant)
        } catch (_: Throwable) {
            "(carInfo tipado: não consegui ler)"
        }
    }

    // ------------------------------------------------------------------ diagnóstico

    @Synchronized
    fun diagnostico(): String {
        if (!iniciado) return "(coletor de dados do carro ainda não ligou)\n"
        if (ultimos.isEmpty()) return "ligado; nenhum sinal recebido ainda (carro desligado ou sem caixa de CAN)\n"
        val sb = StringBuilder()
        for ((k, v) in ultimos) sb.append(k).append(": ").append(v).append('\n')
        return sb.toString()
    }
}
