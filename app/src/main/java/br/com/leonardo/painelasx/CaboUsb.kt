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

    /**
     * Escreve tudo. Logo depois de o celular entrar no modo acessório, o app dele ainda está abrindo o cabo e
     * não lê nada: o USB recusa (cada tentativa espera 1 s). Antes, a 1ª recusa derrubava a conversa e o cabo
     * não era tentado de novo. Agora insiste por até 10 s (só mensagens pequenas vão da central ao celular).
     */
    override fun escrever(b: ByteArray) {
        var pos = 0
        val limite = SystemClock.elapsedRealtime() + 10_000
        while (pos < b.size) {
            if (fechado) throw IOException("cabo: fechado")
            val n = minOf(16384, b.size - pos)
            val r = con.bulkTransfer(saida, b, pos, n, 1000)
            if (r > 0) { pos += r; continue }
            if (SystemClock.elapsedRealtime() > limite) throw IOException("cabo: o celular não leu em 10 s")
        }
        // Tamanho múltiplo do pacote USB: manda um pacote vazio para o outro lado saber que acabou.
        if (saida.maxPacketSize > 0 && b.size % saida.maxPacketSize == 0) con.bulkTransfer(saida, ByteArray(0), 0, 1000)
    }

    /** Android Auto: lê o que chegar (até [n] bytes) esperando no máximo [esperaMs]. -1 = fechado; 0 = nada. */
    fun lerBruto(buf: ByteArray, n: Int, esperaMs: Int): Int {
        if (fechado) return -1
        val t0 = SystemClock.elapsedRealtime()
        val r = try { con.bulkTransfer(entrada, buf, minOf(n, 16384), esperaMs.coerceAtLeast(1)) } catch (_: Exception) { -1 }
        if (fechado) return -1
        if (r > 0) return r
        // -1 na hora (sem esperar o tempo) = erro do USB, não "nada chegou": um respiro para não rodar em falso
        // até o aviso de "cabo solto" chegar.
        if (r < 0 && SystemClock.elapsedRealtime() - t0 < esperaMs / 2) try { Thread.sleep(50) } catch (_: InterruptedException) { }
        return 0
    }

    /**
     * Android Auto: escreve o quadro inteiro, SEM o pacote vazio do fim (o Headunit Revived também não manda; o
     * Android Auto do celular não espera por ele). Insiste por até 3 s se o celular demorar a ler.
     */
    fun escreverBruto(b: ByteArray): Boolean {
        var pos = 0
        val limite = SystemClock.elapsedRealtime() + 3_000
        while (pos < b.size) {
            if (fechado) return false
            val r = try { con.bulkTransfer(saida, b, pos, minOf(16384, b.size - pos), 1000) } catch (_: Exception) { -1 }
            if (r > 0) { pos += r; continue }
            if (SystemClock.elapsedRealtime() > limite) return false
        }
        return true
    }

    override fun fechar() {
        if (fechado) return
        fechado = true
        try { con.releaseInterface(intf) } catch (_: Exception) { }
        try { con.close() } catch (_: Exception) { }
    }
}

/**
 * Cabo USB do celular. Três modos (escolhidos na tela de ajustes, "Cabo USB do celular"):
 *  - "Android Auto pelo Painel" (PADRÃO desde o receptor): a central manda o celular para o modo acessório com a
 *    identidade do Android Auto e o próprio Painel é o receptor ([AndroidAuto]). Nada de app nosso no celular.
 *  - "app Painel ASX Celular" (secundário): os textos do Painel; conversa em JSON com o app do celular ([CaboCelular]).
 *  - "ZLink": a central NÃO mexe no cabo (nenhum filtro USB ligado, nenhum pedido de modo acessório).
 *  - Nos dois primeiros, o filtro USB do app (activity-alias) faz o Android abrir a CaboActivity ao plugar. Na 1ª vez o
 *    Leonardo escolhe "Painel ASX" e marca "usar por padrão"/"Sempre" (nunca apertamos sozinhos); depois o Android dá a
 *    permissão sozinho a cada plugada.
 *  - Nunca pergunta a permissão sem parar: no máximo [MAX_PEDIDOS] vezes seguidas sem o "usar por padrão" funcionar.
 *  - O celular no modo acessório que não responde (outro app, como o ZLink, pegou o cabo) é largado até o cabo ser solto.
 *  - Diagnóstico (só leitura) para saber se o ZLink pega o cabo com permissão automática (caso B) ou não (caso A).
 */
object CaboUsb {
    private const val GOOGLE = 0x18D1
    private const val XIAOMI = 0x2717
    private val PIDS_ACESSORIO = setOf(0x2D00, 0x2D01)
    private const val ACAO_PERMISSAO = "br.com.leonardo.painelasx.PERMISSAO_USB"
    private const val ZLINK = "com.zjinnova.zlink"
    const val ALIAS = "br.com.leonardo.painelasx.CaboPeloPainel"
    private const val MAX_PEDIDOS = 4

    // De onde veio o aparelho (muda o que fazemos sem permissão).
    private const val AVISO = 0        // aviso USB_DEVICE_ATTACHED do Android
    private const val SISTEMA = 1      // o Android abriu a CaboActivity (somos o "padrão": a permissão já vem junto)
    private const val PROCURA = 2      // o app abriu/voltou e o aparelho já estava plugado
    private const val REVER = 3        // aviso sem permissão, conferido de novo 1,5 s depois
    private const val DE_NOVO = 4      // o cabo caiu com o aparelho ainda plugado: abrir de novo
    private const val PERMITIU = 5     // o Leonardo tocou em Permitir
    private const val REVER_TARDE = 6  // ainda sem permissão 15 s depois (ninguém respondeu ao "Abrir com" do Android)

    /** Quedas rápidas seguidas (com resposta) antes de largar o cabo até ele ser solto. */
    private const val MAX_QUEDAS = 8

    // Modo "app do celular": os mesmos textos do accessory_filter.xml do app do celular (fabricante + modelo).
    private val TEXTOS = arrayOf(
        "Leonardo", "PainelASX", "Painel ASX do ASX 2012", "1",
        "https://github.com/somosvinciag-droid/painel-asx/releases/tag/celular", "asx"
    )
    // Modo "Android Auto pelo Painel": a identidade do Android Auto (as mesmas do Headunit Revived). Com ela o
    // Android Auto do celular abre sozinho, sem app nosso no celular.
    private val TEXTOS_AA = arrayOf(
        "Android", "Android Auto", "Android Auto", "2.0.1",
        "https://developer.android.com/auto/index.html", "HU-AAAAAA001"
    )

    /** Modos do cabo. */
    const val MODO_AA = "aa"         // padrão: o Painel é o receptor de Android Auto (o "ZLink nosso")
    const val MODO_APP = "app"       // opção secundária: o app "Painel ASX Celular" pelo cabo (protocolo JSON)
    const val MODO_ZLINK = "zlink"   // o Painel não mexe no cabo (o ZLink de fábrica fica com ele)

    @Volatile private var app: Context? = null
    @Volatile private var preparado = false
    private val vistos = ArrayDeque<String>()
    private var ultimoTratado = ""
    private var ultimoTratadoEm = 0L
    /** Quando ESTE processo mandou um celular para o modo acessório (1ª vez: o Leonardo pode demorar no celular). */
    @Volatile private var trocouEm = 0L
    /** Aparelhos no modo acessório que não responderam: largados até o cabo ser solto. */
    private val alheios = HashSet<String>()
    /** Tentativas seguidas de reabrir o cabo de cada aparelho (zera quando o celular responde). */
    private val tentativas = HashMap<String, Int>()
    @Volatile var aviso = ""
        private set

    /**
     * Modo do cabo. Padrão: "Android Auto pelo Painel". Quem tinha desligado o interruptor antigo ("cabo_painel"
     * = false, o cabo era do Android Auto/ZLink) continua com o ZLink.
     */
    fun modo(ctx: Context): String {
        val p = CaboCelular.prefs(ctx)
        p.getString("cabo_modo", null)?.let { if (it == MODO_AA || it == MODO_APP || it == MODO_ZLINK) return it }
        return if (p.contains("cabo_painel") && !p.getBoolean("cabo_painel", true)) MODO_ZLINK else MODO_AA
    }

    /** O Painel mexe no cabo (Android Auto pelo Painel ou app do celular). */
    fun usarCabo(ctx: Context) = modo(ctx) != MODO_ZLINK

    fun nomeModo(m: String) = when (m) {
        MODO_AA -> "Android Auto pelo Painel"
        MODO_APP -> "app Painel ASX Celular"
        else -> "ZLink (o Painel não mexe no cabo)"
    }

    /** O Leonardo escolheu o modo do cabo. Depois de mudar, é preciso tirar e pôr o cabo. */
    fun mudarModo(ctx: Context, m: String) {
        val antes = modo(ctx)
        CaboCelular.prefs(ctx).edit().putString("cabo_modo", m).putInt("cabo_pedidos", 0).apply()
        if (antes == MODO_AA && m != MODO_AA) AndroidAuto.parar("modo do cabo mudou")
        val painel = m != MODO_ZLINK
        aviso = ""
        synchronized(this) { alheios.clear(); tentativas.clear() }
        try {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx.packageName, ALIAS),
                if (painel) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Registro.anotar("cabo USB: não mudei o filtro (${e.javaClass.simpleName})")
        }
        Registro.anotar("cabo USB: modo agora é " + nomeModo(m))
        if (painel) procurarJaPlugado(ctx)
    }

    @Synchronized
    fun preparar(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        if (preparado) return
        preparado = true
        // Primeira vez com esta versão: grava o modo (padrão = Android Auto pelo Painel) e liga o filtro do cabo, uma vez só.
        if (!CaboCelular.prefs(a).contains("cabo_modo")) {
            val m = modo(a)
            CaboCelular.prefs(a).edit().putString("cabo_modo", m).apply()
            try {
                a.packageManager.setComponentEnabledSetting(ComponentName(a.packageName, ALIAS),
                    if (m == MODO_ZLINK) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
                Registro.anotar("cabo USB: modo padrão gravado: ${nomeModo(m)}")
            } catch (e: Exception) { Registro.anotar("cabo USB: não liguei o filtro (${e.javaClass.simpleName})") }
        }
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
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> plugou(a, d, doSistema = false)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        anotar("soltou", d)
                        synchronized(this@CaboUsb) { alheios.remove(d.deviceName); tentativas.remove(d.deviceName) }
                        CaboCelular.soltouCabo(d.deviceName)
                        AndroidAuto.soltouCabo(d.deviceName)
                    }
                    ACAO_PERMISSAO -> {
                        val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Registro.anotar("cabo USB: permissão " + (if (ok) "dada" else "negada") + " para ${hex(d)}")
                        if (ok) tratar(a, d, PERMITIU)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else a.registerReceiver(r, f)
        if (usarCabo(a)) procurarJaPlugado(a)
    }

    /** Aparelho plugado: pelo aviso do Android ([doSistema] = false) ou pela CaboActivity (true). */
    fun plugou(ctx: Context, d: UsbDevice, doSistema: Boolean) {
        anotar("plugou", d)
        if (usarCabo(ctx)) tratar(ctx, d, if (doSistema) SISTEMA else AVISO)
    }

    private fun procurarJaPlugado(ctx: Context) {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.values.forEach { tratar(ctx, it, PROCURA) }
    }

    /**
     * A conversa pelo cabo acabou. Se o aparelho continua plugado, abre de novo (o celular também reabre do lado dele).
     * Se ele nunca respondeu, é outro app com o cabo (ou, na 1ª vez, o Leonardo ainda não tocou em OK no celular).
     */
    fun caboTerminou(ctx: Context, nome: String, respondeu: Boolean, durouMs: Long) {
        if (!usarCabo(ctx)) return
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (um.deviceList.values.none { it.deviceName == nome }) return   // soltou: espera a próxima plugada
        val espera: Long = synchronized(this) {
            val curta = durouMs < 20_000
            val n = (if (respondeu && !curta) 0 else tentativas[nome] ?: 0) + 1
            tentativas[nome] = n
            val primeiraVez = SystemClock.elapsedRealtime() - trocouEm < 90_000
            if (!respondeu && (n > 4 || (!primeiraVez && n > 1))) {
                alheios.add(nome)
                aviso = if (modo(ctx) == MODO_AA) "o Android Auto do celular não respondeu pelo cabo (o ZLink pegou o cabo?)"
                    else "o celular não respondeu pelo cabo (o ZLink/Android Auto pegou o cabo?)"
                Registro.anotar("cabo USB: $aviso Sigo pela rede Bluetooth até o cabo ser solto.")
                return
            }
            // Responde e cai logo, várias vezes seguidas (ex.: o celular recusa o receptor): para de tentar até o cabo
            // ser solto, em vez de reabrir para sempre. Nada de diálogo: só o aviso e o registro.
            if (respondeu && curta && n > MAX_QUEDAS) {
                alheios.add(nome)
                aviso = "o celular respondeu pelo cabo mas a conexão cai logo ($n vezes); tire e ponha o cabo"
                Registro.anotar("cabo USB: $aviso")
                return
            }
            when {
                respondeu && !curta -> 1_000L                    // funcionava e caiu: volta já
                respondeu -> minOf(30_000L, 2_000L * n)          // cai logo de novo: espera cada vez mais
                else -> 3_000L * n                               // 1ª vez: o Leonardo ainda tocando em OK no celular
            }
        }
        Rede.principal.postDelayed({
            val d = um.deviceList.values.firstOrNull { it.deviceName == nome } ?: return@postDelayed
            tratar(ctx, d, DE_NOVO)
        }, espera)
    }

    /** O celular respondeu pelo cabo: some o aviso. */
    fun caboRespondeu() { aviso = "" }

    private fun ehAcessorio(d: UsbDevice) = d.vendorId == GOOGLE && d.productId in PIDS_ACESSORIO

    private fun temMtp(d: UsbDevice) = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

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
    private fun tratar(ctx: Context, d: UsbDevice, origem: Int) {
        if (!usarCabo(ctx)) return
        if (!ehAcessorio(d) && !pareceCelular(d)) return
        if (d.deviceName in alheios) return
        val agora = SystemClock.elapsedRealtime()
        if (origem != PERMITIU && origem != DE_NOVO && d.deviceName == ultimoTratado && agora - ultimoTratadoEm < 3000) return
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        // Conferência atrasada: o aparelho pode ter sido solto (ou trocado de modo) nesse meio tempo.
        if ((origem == REVER || origem == REVER_TARDE || origem == DE_NOVO) && um.deviceList.values.none { it.deviceName == d.deviceName }) return
        if (!um.hasPermission(d)) {
            when {
                // Com o "usar por padrão" marcado, o Android dá a permissão e abre a CaboActivity logo depois deste
                // aviso. Pedir agora mostraria o diálogo à toa: confere de novo em 1,5 s.
                origem == AVISO || origem == SISTEMA || origem == PROCURA -> Rede.principal.postDelayed({ tratar(ctx, d, REVER) }, 1_500)
                // Sem padrão, o próprio Android já mostra "Abrir com Painel ASX?" (com "usar por padrão"/"Sempre").
                // Não empilha o nosso diálogo em cima: só pede se, 15 s depois, ninguém respondeu ao dele. A exceção é
                // o celular em modo "arquivos" (MTP): aí o Android só põe um aviso na barra e quem pergunta somos nós.
                origem == REVER && !temMtp(d) -> Rede.principal.postDelayed({ tratar(ctx, d, REVER_TARDE) }, 15_000)
                else -> pedirPermissao(ctx, um, d)
            }
            return
        }
        if (origem == REVER_TARDE) return   // já resolvido pelo "Abrir com" do Android (a CaboActivity tratou)
        // Permissão sem pedir = o "usar por padrão" funciona: zera a contagem de pedidos.
        if (origem != PERMITIU && CaboCelular.prefs(ctx).getInt("cabo_pedidos", 0) != 0)
            CaboCelular.prefs(ctx).edit().putInt("cabo_pedidos", 0).apply()
        ultimoTratado = d.deviceName; ultimoTratadoEm = agora
        Thread({
            try {
                val aa = modo(ctx) == MODO_AA
                if (ehAcessorio(d)) {
                    val canal = abrir(um, d)
                    if (canal != null) {
                        Registro.anotar("cabo USB: canal aberto com o celular (${d.productName ?: hex(d)}) para o " + if (aa) "Android Auto" else "app do celular")
                        if (aa) AndroidAuto.entregarCabo(ctx, canal) else CaboCelular.entregarCabo(canal)
                    } else Registro.anotar("cabo USB: não abri o acessório (outro app, como o ZLink, pegou antes?)")
                } else if (trocarParaAcessorio(um, d, if (aa) TEXTOS_AA else TEXTOS)) {
                    trocouEm = SystemClock.elapsedRealtime()
                    Registro.anotar("cabo USB: pedi o modo acessório ao ${hex(d)} (" + (if (aa) "identidade Android Auto" else "identidade Painel ASX") + "); ele vai voltar como 18d1:2d00")
                }
            } catch (e: Exception) {
                Registro.anotar("ERRO cabo USB: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "cabo-usb").start()
    }

    /** Mostra o "Permitir" do Android (quem toca é o Leonardo). Nunca mais de [MAX_PEDIDOS] vezes seguidas. */
    private fun pedirPermissao(ctx: Context, um: UsbManager, d: UsbDevice) {
        val p = CaboCelular.prefs(ctx)
        val n = p.getInt("cabo_pedidos", 0)
        if (n >= MAX_PEDIDOS) {
            if (aviso.isEmpty() || !aviso.startsWith("sem permissão")) {
                aviso = "sem permissão do cabo: escolha o modo do cabo de novo e marque \"usar por padrão\""
                Registro.anotar("cabo USB: $aviso (não pergunto mais sozinho)")
            }
            return
        }
        p.edit().putInt("cabo_pedidos", n + 1).apply()
        // MUTABLE: o Android preenche a resposta (aparelho + "permitido") neste aviso. Explícito (só para nós).
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ACAO_PERMISSAO).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { um.requestPermission(d, pi) } catch (e: Exception) { Registro.anotar("cabo USB: pedido de permissão falhou (${e.javaClass.simpleName})") }
    }

    /** Celular normal -> modo acessório (AOA 51/52/53). */
    private fun trocarParaAcessorio(um: UsbManager, d: UsbDevice, textos: Array<String>): Boolean {
        val c = um.openDevice(d) ?: return false
        try {
            val b = ByteArray(2)
            val n = c.controlTransfer(0xC0, 51, 0, 0, b, 2, 1000)
            val versao = if (n == 2) (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) else 0
            if (versao < 1) { Registro.anotar("cabo USB: ${hex(d)} não aceita o modo acessório (n=$n)"); return false }
            for (i in textos.indices) {
                val dados = (textos[i] + "\u0000").toByteArray(Charsets.UTF_8)
                if (c.controlTransfer(0x40, 52, 0, i, dados, dados.size, 1000) < 0) return false
            }
            // -1 aqui é normal: o celular já começou a voltar como acessório antes de confirmar (o HUR aceita).
            c.controlTransfer(0x40, 53, 0, 0, null, 0, 1000)
            return true
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
        // Primeiro sem forçar (não toma o cabo de outro app à força); só força se não houver dono.
        if (!c.claimInterface(intf, false) && !c.claimInterface(intf, true)) { c.close(); return null }
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
        sb.append("modo do cabo: ").append(nomeModo(modo(ctx))).append(if (modo(ctx) == MODO_AA) " (padrão)" else "").append('\n')
        sb.append("pedidos de permissão seguidos: ").append(CaboCelular.prefs(ctx).getInt("cabo_pedidos", 0)).append(" de ").append(MAX_PEDIDOS)
            .append(if (aviso.isNotEmpty()) " · aviso: $aviso" else "").append('\n')
        sb.append("filtro USB do Painel (alias): ").append(
            when (pm.getComponentEnabledSetting(ComponentName(ctx.packageName, ALIAS))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ligado"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "desligado"
                else -> "padrão do manifesto (desligado; o app liga na 1ª vez)"
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
 * Tela invisível: o Android a abre quando o celular é plugado e o filtro USB do Painel está ligado
 * (modo do cabo "Android Auto pelo Painel" ou "app do celular"). Entrega o aparelho e fecha na hora.
 */
class CaboActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        val d = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (d != null) {
            CaboCelular.iniciar(applicationContext)
            CaboUsb.plugou(applicationContext, d, doSistema = true)
        }
        finish()
    }
}
