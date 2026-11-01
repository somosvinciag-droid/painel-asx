package br.com.leonardo.painelasx.zlink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Looper
import android.provider.Settings
import br.com.leonardo.painelasx.Props
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Diagnóstico "--- zlink ---" (SÓ LEITURA, nenhum toque, nada é gravado na central).
 * Entra no diagnóstico do conector (diagnostico_central), seção "--- zlink ---", por [paraDiagnostico].
 *
 * Responde, com dados da própria central:
 *  1. Como o ZLink está instalado (estado ligado/desligado, persistente, pasta) e o manifesto dele:
 *     receptores/serviços exportados, filtros e permissões (para saber se aceita algum aviso que o desliga).
 *  2. Strings do classes.dex/resources.arsc com USB/acessório/conexão automática (confirma se ele pega o
 *     USB por receptor registrado em tempo de execução: "android.hardware.usb.action.USB_DEVICE_ATTACHED"
 *     no dex, ou por varredura: "getDeviceList"), e os nomes dos ajustes internos dele.
 *  3. ADB local: propriedades e uma SONDAGEM em 127.0.0.1 (só manda CNXN; NÃO manda a chave, então
 *     não aparece nenhum aviso na tela). Diz se o adbd da central já escuta na rede.
 *  4. Fábrica NWD: bit CarPlay do Settings.System "factory_feature", se o app de fábrica existe e as
 *     listas de "não instalar no boot" (Carkit_Uninstall.ini), quando legíveis.
 */
object DiagnosticoZlink {
    const val ZLINK = "com.zjinnova.zlink"
    const val FABRICA = "com.nwd.factory.setting"
    private const val FEATURE_CARPLAY = 0x40000   // com.nwd.factory.FeatureSettingUtil.FEATURE_CARPLAY

    @Volatile private var guardado: String? = null
    @Volatile private var guardadoEm = 0L

    /**
     * Para o Diagnostico. Fora da linha principal: raio-x (abre um socket local; com [comStrings] também lê o
     * classes.dex do ZLink, alguns MB, por isso só quando o Claude pede o diagnóstico) e guarda o resultado.
     * Na linha principal (botão "Enviar diagnóstico"): devolve o último guardado, sem rede nem disco.
     */
    fun paraDiagnostico(ctx: Context, comStrings: Boolean): String {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            val g = guardado ?: return "(o raio-x completo do ZLink vem pelo conector: diagnostico_central)\n"
            return "(raio-x guardado há ${(System.currentTimeMillis() - guardadoEm) / 60_000} min)\n$g"
        }
        val t = try { relatorio(ctx, comStrings).removePrefix("--- zlink ---\n") } catch (e: Throwable) {
            "(raio-x do ZLink falhou: ${e.javaClass.simpleName}: ${e.message})\n"
        }
        guardado = t
        guardadoEm = System.currentTimeMillis()
        return t
    }

    fun relatorio(ctx: Context, comStrings: Boolean = true): String = buildString {
        val pm = ctx.packageManager
        appendLine("--- zlink ---")
        val ai: ApplicationInfo? = try {
            pm.getApplicationInfo(ZLINK, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: Exception) { null }
        if (ai == null) {
            appendLine("zlink: NÃO instalado (ou escondido)")
        } else {
            val estado = try { pm.getApplicationEnabledSetting(ZLINK) } catch (e: Exception) { -1 }
            appendLine("zlink: estado=" + when (estado) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "padrão (ligado)"
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ligado"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DESLIGADO (disable)"
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "DESLIGADO (disable-user)"
                else -> "? ($estado)"
            } + " · enabled=${ai.enabled} · sistema=${ai.flags and ApplicationInfo.FLAG_SYSTEM != 0}" +
                " · persistente=${ai.flags and ApplicationInfo.FLAG_PERSISTENT != 0} · uid=${ai.uid}")
            val apk = File(ai.sourceDir)
            appendLine("apk: ${ai.sourceDir} (${apk.length() / 1024} KB, legível=${apk.canRead()})")
            try {
                append(RaioXApk.resumo(RaioXApk.lerManifesto(apk)))
            } catch (e: Exception) {
                appendLine("manifesto: não li (${e.javaClass.simpleName}: ${e.message})")
            }
            if (comStrings) {
                try {
                    appendLine("strings do dex (USB, conexão, avisos):")
                    RaioXApk.procurarNoDex(apk, RaioXApk.PALAVRAS_ZLINK, 250).forEach { appendLine("  $it") }
                } catch (e: Exception) { appendLine("  dex: não li (${e.javaClass.simpleName}: ${e.message})") }
                try {
                    appendLine("strings dos recursos (textos e nomes de ajustes):")
                    RaioXApk.procurarNoArsc(apk, RaioXApk.PALAVRAS_ZLINK, 150).forEach { appendLine("  $it") }
                } catch (e: Exception) { appendLine("  arsc: não li (${e.javaClass.simpleName}: ${e.message})") }
            }
        }

        appendLine("quem recebe estes avisos (pelo manifesto; receptores de tempo de execução não aparecem):")
        val acoes = listOf(UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_ACCESSORY_ATTACHED,
            "com.nwd.action.ACTION_SET_SYSTEM_PROP", "zjinnova.android.intent.action.START_DAEMON_SERVICE",
            "zjinnova.android.intent.action.ZLINK_SERVICE", "zjinnova.android.intent.action.ZLINK_MAIN")
        for (a in acoes) {
            val r = try { pm.queryBroadcastReceivers(Intent(a), PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { "receptor ${it.activityInfo.packageName}/${it.activityInfo.name}" } } catch (_: Exception) { emptyList() }
            val s = try { pm.queryIntentServices(Intent(a), PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { "serviço ${it.serviceInfo.packageName}/${it.serviceInfo.name}" } } catch (_: Exception) { emptyList() }
            val t = try { pm.queryIntentActivities(Intent(a), PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { "tela ${it.activityInfo.packageName}/${it.activityInfo.name}" } } catch (_: Exception) { emptyList() }
            appendLine("  $a: " + (r + s + t).ifEmpty { listOf("ninguém") }.joinToString())
        }

        appendLine("adb da central:")
        for (p in listOf("ro.adb.secure", "ro.debuggable", "ro.build.type", "ro.secure", "init.svc.adbd", "sys.usb.config",
            "sys.usb.state", "sys.usb.configfs", "sys.usb.controller", "persist.sys.usb.config", "service.adb.tcp.port",
            "persist.adb.tcp.port", "persist.sys.usb.panic.func")) appendLine("  $p=${Props.ler(p)}")
        for (porta in portasCandidatas()) appendLine("  sondagem 127.0.0.1:$porta -> ${sondarAdb(porta)}")
        val dialogo = ComponentName("com.android.systemui", "com.android.systemui.usb.UsbDebuggingActivity")
        val temDialogo = try { pm.getActivityInfo(dialogo, PackageManager.MATCH_DISABLED_COMPONENTS); true } catch (_: Exception) { false }
        appendLine("  aviso \"Permitir depuração USB?\" existe na central: $temDialogo")
        appendLine("  adb_enabled=${try { Settings.Global.getString(ctx.contentResolver, "adb_enabled") } catch (e: Exception) { "erro" }}" +
            " · development_settings_enabled=${try { Settings.Global.getString(ctx.contentResolver, "development_settings_enabled") } catch (e: Exception) { "erro" }}")

        appendLine("fábrica NWD:")
        val ff = try { Settings.System.getString(ctx.contentResolver, "factory_feature") } catch (e: Exception) { "erro ${e.javaClass.simpleName}" }
        val ffInt = ff?.trim()?.toIntOrNull()
        appendLine("  factory_feature=$ff" + (ffInt?.let { " · CarPlay(0x40000)=" + if (it and FEATURE_CARPLAY != 0) "LIGADO" else "desligado" } ?: ""))
        appendLine("  senha da fábrica (Settings nwd_factory_password)=" +
            (try { Settings.System.getString(ctx.contentResolver, "nwd_factory_password") } catch (_: Exception) { null } ?: "(vazio)"))
        val fab = try { pm.getPackageInfo(FABRICA, PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS) } catch (_: Exception) { null }
        appendLine("  app de fábrica $FABRICA: " + if (fab == null) "não encontrado" else
            "versão ${fab.versionName} · telas: " + (fab.activities?.joinToString { "${it.name.substringAfterLast('.')}(exportada=${it.exported})" } ?: "?"))
        if (comStrings && fab != null) {
            // Para guiar o Leonardo no menu de fábrica: nomes das funções (CarPlay/ZLink), telas e senhas que o app conhece.
            val palavras = listOf("carplay", "zlink", "carkit", "feature", "function", "password", "passwd", "pwd", "1617", "6699",
                "功能", "密码", "互联", "uninstall")
            try {
                val apk = File(fab.applicationInfo.sourceDir)
                appendLine("  textos do app de fábrica (recursos):")
                RaioXApk.procurarNoArsc(apk, palavras, 80).forEach { appendLine("    $it") }
                appendLine("  textos do app de fábrica (código):")
                RaioXApk.procurarNoDex(apk, palavras, 120).forEach { appendLine("    $it") }
            } catch (e: Exception) { appendLine("  app de fábrica: não li os textos (${e.javaClass.simpleName}: ${e.message})") }
        }
        val cfg = Props.ler("ro.nwd.config.path").ifBlank { "/data/nwdappconfig" }
        for (f in listOf("$cfg/app/UartConfig.ini", "$cfg/app/FactoryConfig.ini", "/system/config/app/Carkit_Uninstall.ini",
            "/cache/Carkit_Uninstall.ini", "/system/config/app/FactoryConfig.ini", "$cfg/app/need_hide_packname.ini")) {
            appendLine("  $f: " + lerPequeno(File(f), linhasCom = listOf("carplay", "zlink", "zjinnova", "uninstall", "fun", "install")))
        }
    }

    /** Portas onde o adbd pode estar: as das propriedades e a padrão (5555). */
    fun portasCandidatas(): List<Int> =
        (listOf(Props.ler("service.adb.tcp.port"), Props.ler("persist.adb.tcp.port")).mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 } + 5555).distinct()

    /**
     * Abre 127.0.0.1:porta, manda só o CNXN e lê a resposta. NÃO manda chave nenhuma: nenhum aviso aparece.
     * AUTH = adbd pede chave (ro.adb.secure=1); CNXN = adbd aceita sem chave.
     */
    fun sondarAdb(porta: Int, esperaMs: Int = 1500): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", porta), esperaMs)
            s.soTimeout = esperaMs
            val banner = "host::\u0000".toByteArray(Charsets.US_ASCII)
            val h = ByteBuffer.allocate(24 + banner.size).order(ByteOrder.LITTLE_ENDIAN)
            val cnxn = 0x4e584e43
            h.putInt(cnxn).putInt(0x01000001).putInt(256 * 1024).putInt(banner.size)
                .putInt(banner.sumOf { it.toInt() and 0xFF }).putInt(cnxn.inv()).put(banner)
            s.getOutputStream().apply { write(h.array()); flush() }
            val r = ByteArray(24)
            var n = 0
            while (n < 24) { val k = s.getInputStream().read(r, n, 24 - n); if (k < 0) break; n += k }
            if (n < 24) "porta ABERTA, mas não respondeu como adb"
            else when (ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).int) {
                0x48545541 -> "ADB ESCUTANDO (pede chave: na 1ª vez aparece \"Permitir depuração USB?\")"
                cnxn -> "ADB ESCUTANDO SEM CHAVE (ro.adb.secure=0: nenhum toque)"
                0x534c5453 -> "ADB pede TLS (não esperado no Android 10)"
                else -> "porta aberta, resposta desconhecida"
            }
        }
    } catch (e: java.net.ConnectException) {
        "fechada"
    } catch (e: Exception) {
        "erro ${e.javaClass.simpleName}"
    }

    private fun lerPequeno(f: File, linhasCom: List<String>): String = try {
        when {
            !f.exists() -> "não existe (ou pasta sem acesso)"
            !f.canRead() -> "existe, sem permissão de leitura"
            else -> f.readLines().filter { l -> linhasCom.any { l.lowercase().contains(it) } }.take(20)
                .joinToString(" | ").ifEmpty { "(nada sobre carplay/zlink; ${f.length()} bytes)" }
        }
    } catch (e: Exception) { "erro ${e.javaClass.simpleName}" }

    /** O manifesto binário cru do ZLink em base64 (poucos KB), se o Claude quiser conferir fora da central. */
    fun manifestoCruBase64(ctx: Context): String? = try {
        val ai = ctx.packageManager.getApplicationInfo(ZLINK, PackageManager.MATCH_DISABLED_COMPONENTS)
        RaioXApk.entrada(File(ai.sourceDir), "AndroidManifest.xml")?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
    } catch (_: Exception) { null }
}
