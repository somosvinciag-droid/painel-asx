package br.com.leonardo.painelasx

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Caminho 2 (fase 2): o celular como acessório USB (AOA). A central é o "host". */
class CanalAoa(
    private val con: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val entrada: UsbEndpoint,
    private val saida: UsbEndpoint,
    val nomeAparelho: String
) : Canal {
    override val tipo = "cabo"
    @Volatile private var fechado = false

    // bulkTransfer devolve negativo tanto para "nada chegou" quanto para erro: a queda é vista pelo
    // aviso USB_DEVICE_DETACHED ou pela falta de resposta ao ping.
    override fun ler(buf: ByteArray, n: Int): Int {
        if (fechado) return -1
        val r = con.bulkTransfer(entrada, buf, minOf(n, 16384), 1000)
        return if (r > 0) r else 0
    }

    override fun escrever(b: ByteArray) {
        var pos = 0
        while (pos < b.size) {
            val n = minOf(16384, b.size - pos)
            val parte = if (pos == 0 && n == b.size) b else b.copyOfRange(pos, pos + n)
            if (con.bulkTransfer(saida, parte, n, 1000) < 0) throw IOException("cabo: escrita falhou")
            pos += n
        }
        // Tamanho múltiplo do pacote USB: manda um pacote vazio para o outro lado saber que acabou.
        if (saida.maxPacketSize > 0 && b.size % saida.maxPacketSize == 0) con.bulkTransfer(saida, ByteArray(0), 0, 1000)
    }

    override fun fechar() {
        if (fechado) return
        fechado = true
        try { con.releaseInterface(intf) } catch (_: Exception) { }
        try { con.close() } catch (_: Exception) { }
    }
}

/**
 * Cabo USB do celular.
 *  - Interruptor "Cabo: Painel ASX / Android Auto" (padrão: Android Auto). Com "Android Auto", a central NÃO mexe
 *    no cabo: nenhum filtro USB ligado, nenhum pedido de modo acessório. O ZLink continua como sempre.
 *  - Com "Painel ASX": liga o filtro USB do app (activity-alias), pede a permissão do Android (o Leonardo toca em
 *    Permitir; nunca apertamos sozinhos) e manda o celular para o modo acessório com os textos do Painel.
 *  - Diagnóstico (só leitura) para saber se o ZLink pega o cabo com permissão automática (caso B) ou não (caso A).
 */
object CaboUsb {
    private const val GOOGLE = 0x18D1
    private const val XIAOMI = 0x2717
    private val PIDS_ACESSORIO = setOf(0x2D00, 0x2D01)
    private const val ACAO_PERMISSAO = "br.com.leonardo.painelasx.PERMISSAO_USB"
    private const val ZLINK = "com.zjinnova.zlink"
    const val ALIAS = "br.com.leonardo.painelasx.CaboPeloPainel"

    // Os mesmos textos do accessory_filter.xml do app do celular (fabricante + modelo). Sempre com a versão.
    private val TEXTOS = arrayOf(
        "Leonardo", "PainelASX", "Painel ASX do ASX 2012", "1",
        "https://github.com/somosvinciag-droid/painel-asx/releases/tag/celular", "asx"
    )

    @Volatile private var app: Context? = null
    @Volatile private var preparado = false
    private val vistos = ArrayDeque<String>()
    private var ultimoTratado = ""
    private var ultimoTratadoEm = 0L

    fun usarCabo(ctx: Context) = CaboCelular.prefs(ctx).getBoolean("cabo_painel", false)

    /** O Leonardo mudou o interruptor. Depois de mudar, é preciso tirar e pôr o cabo. */
    fun mudarCabo(ctx: Context, painel: Boolean) {
        CaboCelular.prefs(ctx).edit().putBoolean("cabo_painel", painel).apply()
        try {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, ALIAS),
                if (painel) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Registro.anotar("cabo USB: não mudei o filtro (${e.javaClass.simpleName})")
        }
        Registro.anotar("cabo USB: agora o cabo é do " + if (painel) "Painel ASX" else "Android Auto")
        if (painel) procurarJaPlugado(ctx)
    }

    fun preparar(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        if (preparado) return
        preparado = true
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACAO_PERMISSAO)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> plugou(a, d)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { anotar("soltou", d); CaboCelular.soltouCabo(d.deviceName) }
                    ACAO_PERMISSAO -> {
                        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Registro.anotar("cabo USB: permissão " + (if (ok) "dada" else "negada") + " para ${hex(d)}")
                        if (ok) tratar(a, d, forcar = true)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else a.registerReceiver(r, f)
        if (usarCabo(a)) procurarJaPlugado(a)
    }

    /** Aparelho plugado (pelo aviso do Android ou pela CaboActivity). */
    fun plugou(ctx: Context, d: UsbDevice) {
        anotar("plugou", d)
        if (usarCabo(ctx)) tratar(ctx, d)
    }

    private fun procurarJaPlugado(ctx: Context) {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.values.forEach { tratar(ctx, it) }
    }

    private fun ehAcessorio(d: UsbDevice) = d.vendorId == GOOGLE && d.productId in PIDS_ACESSORIO

    private fun pareceCelular(d: UsbDevice): Boolean {
        if (d.vendorId == XIAOMI) return true
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return true            // MTP/PTP
            if (f.interfaceClass == 0xFF && f.interfaceSubclass == 0x42) return true          // ADB
        }
        return false
    }

    @Synchronized
    private fun tratar(ctx: Context, d: UsbDevice, forcar: Boolean = false) {
        if (!usarCabo(ctx)) return
        if (!ehAcessorio(d) && !pareceCelular(d)) return
        val agora = SystemClock.elapsedRealtime()
        if (!forcar && d.deviceName == ultimoTratado && agora - ultimoTratadoEm < 3000) return
        ultimoTratado = d.deviceName; ultimoTratadoEm = agora
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!um.hasPermission(d)) {
            // Mostra o "Permitir" do Android; quem toca é o Leonardo.
            // MUTABLE: o Android preenche a resposta (aparelho + "permitido") neste aviso. Explícito (só para nós).
            val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ACAO_PERMISSAO).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try { um.requestPermission(d, pi) } catch (e: Exception) { Registro.anotar("cabo USB: pedido de permissão falhou (${e.javaClass.simpleName})") }
            return
        }
        Thread({
            try {
                if (ehAcessorio(d)) {
                    val canal = abrir(um, d)
                    if (canal != null) {
                        Registro.anotar("cabo USB: canal aberto com o celular (${d.productName ?: hex(d)})")
                        CaboCelular.entregarCabo(canal)
                    } else Registro.anotar("cabo USB: não abri o acessório (outro app, como o ZLink, pegou antes?)")
                } else if (trocarParaAcessorio(um, d)) {
                    Registro.anotar("cabo USB: pedi o modo acessório ao ${hex(d)}; ele vai voltar como 18d1:2d00")
                }
            } catch (e: Exception) {
                Registro.anotar("ERRO cabo USB: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "cabo-usb").start()
    }

    /** Celular normal -> modo acessório (AOA 51/52/53). */
    private fun trocarParaAcessorio(um: UsbManager, d: UsbDevice): Boolean {
        val c = um.openDevice(d) ?: return false
        try {
            val b = ByteArray(2)
            val n = c.controlTransfer(0xC0, 51, 0, 0, b, 2, 1000)
            val versao = if (n == 2) (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) else 0
            if (versao < 1) { Registro.anotar("cabo USB: ${hex(d)} não aceita o modo acessório (n=$n)"); return false }
            for (i in TEXTOS.indices) {
                val dados = (TEXTOS[i] + "\u0000").toByteArray(Charsets.UTF_8)
                if (c.controlTransfer(0x40, 52, 0, i, dados, dados.size, 1000) < 0) return false
            }
            return c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000) >= 0
        } finally {
            c.close()
        }
    }

    /** 18d1:2d00/2d01 -> os dois pontos "bulk" da interface 0 (a do ADB nunca é usada). */
    private fun abrir(um: UsbManager, d: UsbDevice): CanalAoa? {
        val intf = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceSubclass != 0x42 && it.endpointCount >= 2 } ?: return null
        var ent: UsbEndpoint? = null
        var sai: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val e = intf.getEndpoint(i)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN && ent == null) ent = e
            if (e.direction == UsbConstants.USB_DIR_OUT && sai == null) sai = e
        }
        if (ent == null || sai == null) return null
        val c = um.openDevice(d) ?: return null
        if (!c.claimInterface(intf, true)) { c.close(); return null }
        return CanalAoa(c, intf, ent, sai, d.deviceName)
    }

    // ---------------------------------------------------------------- diagnóstico (só leitura)

    private fun hex(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR"))

    private fun descrever(d: UsbDevice): String {
        val sb = StringBuilder(hex(d))
        val fab = try { d.manufacturerName } catch (_: Exception) { null }
        val prod = try { d.productName } catch (_: Exception) { null }
        if (fab != null || prod != null) sb.append(" \"").append(fab ?: "?").append(" / ").append(prod ?: "?").append('"')
        sb.append(" ").append(d.deviceName).append(" interfaces:")
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            sb.append(" [%02x/%02x/%02x %dep]".format(f.interfaceClass, f.interfaceSubclass, f.interfaceProtocol, f.endpointCount))
        }
        return sb.toString()
    }

    /** Anota todo aparelho plugado/solto (sem o limite de 1 por minuto do LeitorCarro). */
    private fun anotar(acao: String, d: UsbDevice) {
        val linha = hora.format(Date()) + " " + acao + " " + descrever(d)
        synchronized(vistos) {
            vistos.addLast(linha)
            while (vistos.size > 12) vistos.removeFirst()
        }
        if (acao == "plugou") Registro.anotar("cabo USB: plugou " + descrever(d))
    }

    private fun metaDataUsb(pm: PackageManager, ai: android.content.pm.ComponentInfo, acao: String): String {
        val sb = StringBuilder()
        try {
            val xml = ai.loadXmlMetaData(pm, acao) ?: return "(sem filtro)"
            var ev = xml.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && xml.name != "resources") {
                    sb.append('<').append(xml.name)
                    for (i in 0 until xml.attributeCount) sb.append(' ').append(xml.getAttributeName(i)).append('=').append(xml.getAttributeValue(i))
                    sb.append("> ")
                }
                ev = xml.next()
                if (sb.length > 600) { sb.append("…"); break }
            }
            xml.close()
        } catch (e: Exception) {
            sb.append("(não li: ${e.javaClass.simpleName})")
        }
        return sb.toString().ifBlank { "(filtro vazio = qualquer aparelho)" }
    }

    fun diagnostico(ctx: Context): String {
        val sb = StringBuilder()
        val pm = ctx.packageManager
        sb.append("interruptor: cabo do ").append(if (usarCabo(ctx)) "Painel ASX (fase 2)" else "Android Auto (padrão)").append('\n')
        sb.append("filtro USB do Painel (alias): ").append(
            when (pm.getComponentEnabledSetting(ComponentName(ctx.packageName, ALIAS))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ligado"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "desligado"
                else -> "padrão do manifesto (desligado)"
            }).append('\n')

        // 1. A NWD desligou os diálogos de permissão USB? (true = caso B para todos os apps)
        val idCfg = Resources.getSystem().getIdentifier("config_disableUsbPermissionDialogs", "bool", "android")
        sb.append("config_disableUsbPermissionDialogs: ")
            .append(if (idCfg == 0) "não existe" else try { Resources.getSystem().getBoolean(idCfg).toString() } catch (e: Exception) { "? (${e.javaClass.simpleName})" })
            .append('\n')

        // 2. O ZLink é app de sistema / da plataforma?
        try {
            val ai = pm.getApplicationInfo(ZLINK, 0)
            @Suppress("DEPRECATION")
            val pi = pm.getPackageInfo(ZLINK, 0)
            sb.append("zlink: uid=").append(ai.uid)
                .append(if (ai.uid == 1000) " (SISTEMA)" else "")
                .append(" sistema=").append(ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                .append(" atualizado=").append(ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                .append(" pasta=").append(ai.sourceDir)
                .append(" sharedUserId=").append(pi.sharedUserId ?: "-")
                .append(" assinado_como_android=").append(try { pm.checkSignatures("android", ZLINK) == PackageManager.SIGNATURE_MATCH } catch (_: Exception) { "?" })
                .append(" versão=").append(pi.versionName).append('\n')
        } catch (_: PackageManager.NameNotFoundException) {
            sb.append("zlink: não instalado\n")
        } catch (e: Exception) {
            sb.append("zlink: não li (${e.javaClass.simpleName})\n")
        }

        // 3. Quem tem filtro USB (telas e avisos), a prioridade e o filtro.
        for (acao in listOf(UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
            val curto = acao.substringAfterLast('.')
            val flags = PackageManager.GET_META_DATA or PackageManager.GET_RESOLVED_FILTER
            @Suppress("DEPRECATION")
            val telas = try { pm.queryIntentActivities(Intent(acao), flags) } catch (_: Exception) { emptyList() }
            @Suppress("DEPRECATION")
            val avisos = try { pm.queryBroadcastReceivers(Intent(acao), flags) } catch (_: Exception) { emptyList() }
            sb.append("com filtro $curto: ").append(telas.size).append(" telas, ").append(avisos.size).append(" receptores\n")
            for ((tipo, lista) in listOf("tela" to telas, "receptor" to avisos)) {
                for (ri in lista) {
                    val ci: android.content.pm.ComponentInfo = ri.activityInfo ?: continue
                    val sistema = ci.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    sb.append("  ").append(tipo).append(' ').append(ci.packageName).append('/').append(ci.name.removePrefix(ci.packageName))
                        .append(" prioridade=").append(ri.priority).append('/').append(ri.filter?.priority ?: 0)
                        .append(if (sistema) " sistema" else "").append(" uid=").append(ci.applicationInfo.uid)
                        .append("\n    ").append(metaDataUsb(pm, ci, acao)).append('\n')
                }
            }
        }

        // 4. O que está plugado agora e o que foi plugado (com as interfaces: tira a dúvida do 0xff80).
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val agora = um.deviceList.values
        sb.append("plugados agora: ").append(if (agora.isEmpty()) "nenhum" else "").append('\n')
        agora.forEach { sb.append("  ").append(descrever(it)).append(" permissão=").append(um.hasPermission(it)).append('\n') }
        sb.append("últimos plugados/soltos (desde que o app abriu):\n")
        synchronized(vistos) { if (vistos.isEmpty()) sb.append("  nenhum\n") else vistos.forEach { sb.append("  ").append(it).append('\n') } }
        return sb.toString()
    }
}

/**
 * Tela invisível (fase 2): o Android a abre quando o celular é plugado e o filtro USB do Painel está ligado
 * (interruptor "Cabo: Painel ASX"). Entrega o aparelho e fecha na hora.
 */
class CaboActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        val d = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (d != null) {
            CaboCelular.iniciar(applicationContext)
            CaboUsb.plugou(applicationContext, d)
        }
        finish()
    }
}
