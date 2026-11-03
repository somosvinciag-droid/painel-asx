package br.com.leonardo.painelasx

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import br.com.leonardo.painelasx.canal.AdbTransporte
import br.com.leonardo.painelasx.canal.CanalCabo
import br.com.leonardo.painelasx.canal.MicrofoneRemoto
import br.com.leonardo.painelasx.canal.ProxyDoApp
import br.com.leonardo.painelasx.canal.TransporteUsb
import org.json.JSONObject
import java.io.File

/**
 * Canal paralelo pelo MESMO cabo do Android Auto (decisão do Leonardo, 01/11): com a "Depuração USB" ligada no
 * celular, ele aparece como 18d1:2d01 (acessório + ADB). A interface do acessório é do Android Auto; a do ADB é daqui.
 *
 *  - Microfone: o Jarvis (Ouvido/Whisper) ouve pelo microfone do CELULAR ([microfone]); qualquer erro = o da central.
 *  - Internet: os pedidos HTTP(S) do próprio app (Conector, Jarvis, Clima, Atualizador) passam pelo celular só
 *    quando a central não tem outra internet (Wi-Fi/ancoragem). Downloads grandes não passam (Arquivo.baixar).
 *
 * Configura UMA vez no celular (Opções do desenvolvedor + Depuração USB + "Sempre permitir" na 1ª plugada); depois
 * é automático a cada plugada e na partida com o cabo já plugado. Sem a depuração (2d00) fica desligado e o Android
 * Auto segue igual. O cliente ADB e o servidor estão em `canal/` (AdbUsb.kt, CanalParalelo.kt) e `app/canal-servidor/`.
 */
object CanalDoCabo {
    /** Espera antes de começar: o Android Auto (versão + TLS) passa primeiro pelo cabo. */
    private const val ATRASO_MS = 1_500L

    @Volatile private var app: Context? = null
    @Volatile private var preparado = false

    /** De onde veio o áudio do último FALAR do Ouvido ("celular", "central", "central (…)"). */
    @Volatile var ultimoMic = ""

    private fun preparar(ctx: Context) {
        app = ctx.applicationContext
        if (preparado) return
        preparado = true
        ProxyDoApp.outraInternet = { temOutraInternet() }
    }

    /** O celular (acessório) foi aberto pelo CaboUsb. */
    /**
     * v37: canal DESLIGADO por padrão. Teste de 03/11 15:45–15:57: com a Depuração USB ligada, o Android Auto caía a
     * cada 10–40 s sempre enquanto o canal ADB conectava/reiniciava ("cabeçalho impossível… bytes perdidos no cabo" 3 s
     * depois de "servidor do celular sem resposta; reiniciando"). Até o canal ser refeito (leituras ADB assíncronas),
     * ele só liga com a pref config/canal_ligado = true.
     */
    fun ligado(ctx: Context) = try {
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE).getBoolean("canal_ligado", false)
    } catch (_: Throwable) { false }

    @Volatile private var avisouDesligado = false

    fun plugou(ctx: Context, um: UsbManager, d: UsbDevice, episodio: Any? = null) {
        try {
            if (!ligado(ctx)) {
                if (!avisouDesligado) { avisouDesligado = true; Registro.anotar("canal: desligado nesta versão (o Android Auto fica sozinho no cabo)") }
                return
            }
            preparar(ctx)
            if (!CanalCabo.temAdb(d)) {
                if (CanalCabo.estado != CanalCabo.SEM_ADB) Registro.anotar("canal: o celular está sem a Depuração USB (só o Android Auto pelo cabo)")
                CanalCabo.semAdb(d.deviceName)
                return
            }
            val a = ctx.applicationContext
            CanalCabo.plugou(
                nome = d.deviceName,
                abrir = { abrirTransporte(um, d) },
                pasta = a.noBackupFilesDir,                  // a chave fica FORA do backup (mudar = "Permitir" de novo)
                jar = { a.assets.open("canal-servidor.jar").use { it.readBytes() } },
                log = { Registro.anotar(it) },
                avisar = { frase -> Rede.naTela { try { Voz.falar(frase, Voz.Tipo.IMPORTANTE) } catch (_: Throwable) { } } },
                atrasoMs = ATRASO_MS,
                episodio = episodio,
            )
        } catch (e: Throwable) {
            Registro.anotar("ERRO canal: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A interface ADB na MESMA conexão USB do Android Auto ([ConexoesUsb]). */
    private fun abrirTransporte(um: UsbManager, d: UsbDevice): AdbTransporte? {
        if (um.deviceList.values.none { it.deviceName == d.deviceName }) return null   // já saiu do cabo
        val c = ConexoesUsb.pegar(um, d) ?: return null
        return TransporteUsb.abrir(d, c) { ConexoesUsb.soltar(d.deviceName, c) }
    }

    fun soltou(nome: String) = try { CanalCabo.soltou(nome) } catch (_: Throwable) { }

    fun parar() = try { CanalCabo.parar() } catch (_: Throwable) { }

    /** Para o Ouvido: o microfone do celular, ou null (usa o da central). */
    fun microfone(): MicrofoneRemoto? = try { CanalCabo.microfone() } catch (_: Throwable) { null }

    val ligado: Boolean get() = CanalCabo.ligado

    // ---------------------------------------------------------------- internet

    @Volatile private var conferidoEm = 0L
    @Volatile private var outraInternet = false
    @Volatile private var avisouRota: Boolean? = null

    /** A central tem internet por outro caminho (rede ativa com internet conferida pelo Android)? Guardado por 3 s. */
    private fun temOutraInternet(): Boolean {
        val agora = SystemClock.elapsedRealtime()
        if (agora - conferidoEm < 3_000) return outraInternet
        val cm = app?.getSystemService(ConnectivityManager::class.java) ?: return false
        val tem = try {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
        outraInternet = tem
        conferidoEm = agora
        if (ProxyDoApp.porta != 0 && avisouRota != tem) {
            avisouRota = tem
            Registro.anotar(if (tem) "canal: internet do app pela rede da central" else "canal: internet do app pelo celular (a central está sem rede)")
        }
        return tem
    }

    // ---------------------------------------------------------------- conector e diagnóstico

    fun estadoConector(): JSONObject {
        val j = JSONObject()
        try { for ((k, v) in CanalCabo.estadoConector()) j.put(k, v) } catch (_: Throwable) { }
        j.put("microfone", ultimoMic)
        return j
    }

    fun diagnostico(): String {
        val sb = StringBuilder()
        sb.append("estado: ").append(CanalCabo.estado).append('\n')
        if (CanalCabo.modelo.isNotEmpty()) sb.append("celular: ").append(CanalCabo.modelo).append('\n')
        sb.append("microfone do último FALAR: ").append(ultimoMic.ifEmpty { "-" }).append('\n')
        sb.append("internet do app pelo celular agora: ").append(if (ProxyDoApp.emUso()) "sim" else "não")
            .append(if (ProxyDoApp.porta != 0) " (proxy 127.0.0.1:${ProxyDoApp.porta})" else "").append('\n')
        if (CanalCabo.ultimoErro.isNotEmpty()) sb.append("último erro: ").append(CanalCabo.ultimoErro).append('\n')
        val chave = app?.let { File(it.noBackupFilesDir, "adb/chave_central.pk8") }
        sb.append("chave da central: ").append(if (chave?.exists() == true) "criada" else "ainda não").append('\n')
        sb.append("linhas \"canal:\" do registro:\n")
        val linhas = Registro.ultimas(400).filter { it.contains("canal:") }.takeLast(30)
        if (linhas.isEmpty()) sb.append("  nenhuma\n") else linhas.forEach { sb.append("  ").append(it).append('\n') }
        return sb.toString()
    }
}
