package br.com.leonardo.painelasx

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * O "dedo" do app. Com a Acessibilidade ligada:
 *  - toca em botoes pelo texto, volta, vai para o inicio;
 *  - anota que telas a central abre (camera de re, radio, etc.) -> diagnostico;
 *  - percebe quando a central acorda (carro ligado) e roda a rotina;
 *  - confirma sozinho a instalacao das atualizacoes do proprio app.
 */
class Controle : AccessibilityService() {

    companion object {
        @Volatile
        var ativo: Controle? = null

        /** Ligado pelo Atualizador enquanto uma atualizacao nossa esta a instalar. */
        @Volatile
        var confirmarInstalacao = false

        private val INSTALADORES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )
        private val BOTOES_INSTALAR = listOf("Instalar", "Atualizar", "Install", "Update")

        fun ligado(ctx: Context): Boolean {
            val lista = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return lista.split(':').any {
                it.startsWith(ctx.packageName + "/") && it.endsWith("Controle")
            }
        }
    }

    private var ultimaTela = ""

    private val acordou = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Registro.anotar("central: ${i.action?.substringAfterLast('.')}")
            Rotina.talvezRodar(this@Controle, i.action ?: "")
            Atualizador.verificarEmSegundoPlano(this@Controle)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ativo = this
        Registro.iniciar(this)
        Registro.anotar("controle: ligando (v${BuildConfig.VERSION_CODE})")
        // Cada parte isolada: se uma falhar, o Controle continua ligado e o erro fica anotado.
        seguro("voz") { Voz.iniciar(this) }
        seguro("avisos da central") {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(acordou, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(acordou, f)
            }
        }
        seguro("agendar atualizacao") { Atualizador.agendar(this) }
        seguro("rotina") { Rotina.talvezRodar(this, "controle ligado") }
        Registro.anotar("controle: ligado")
    }

    private fun seguro(nome: String, bloco: () -> Unit) {
        try {
            bloco()
        } catch (t: Throwable) {
            Registro.anotar("ERRO em $nome: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Registro.anotar("controle: o Android desligou o controle")
        ativo = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            tratar(e)
        } catch (t: Throwable) {
            Registro.anotar("ERRO no evento: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun tratar(e: AccessibilityEvent?) {
        if (e == null) return
        val pacote = e.packageName?.toString() ?: return

        if (confirmarInstalacao && pacote in INSTALADORES) {
            if (clicarTexto(BOTOES_INSTALAR)) {
                Registro.anotar("atualizacao: confirmei a instalacao")
                confirmarInstalacao = false
            }
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val classe = e.className?.toString() ?: ""
            val tela = "$pacote / $classe"
            if (tela != ultimaTela && pacote != packageName) {
                ultimaTela = tela
                Registro.anotar("tela: $tela")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            unregisterReceiver(acordou)
        } catch (_: Exception) {
        }
        ativo = null
        super.onDestroy()
    }

    /** Toca no primeiro botao da tela que tenha um destes textos. */
    fun clicarTexto(textos: List<String>): Boolean {
        val raizes = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { raizes.add(it) } }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { raizes.add(0, it) }
        for (raiz in raizes) {
            for (t in textos) {
                val nos = raiz.findAccessibilityNodeInfosByText(t) ?: continue
                for (n in nos) {
                    var x: AccessibilityNodeInfo? = n
                    while (x != null && !x.isClickable) x = x.parent
                    if (x != null && x.isEnabled && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun voltar() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun inicio() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recentes() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Os textos e os nomes dos botoes da tela atual (para o diagnostico). */
    fun lerTela(): String {
        val sb = StringBuilder()
        val raiz = rootInActiveWindow ?: return "(nao consegui ler a tela)"
        sb.append("app: ").append(raiz.packageName).append('\n')
        fun visitar(n: AccessibilityNodeInfo?, nivel: Int) {
            if (n == null || nivel > 25 || sb.length > 6000) return
            val txt = n.text?.toString() ?: n.contentDescription?.toString()
            val id = n.viewIdResourceName
            if (!txt.isNullOrBlank() || id != null) {
                sb.append("  ".repeat(nivel.coerceAtMost(8)))
                if (!txt.isNullOrBlank()) sb.append('"').append(txt.take(60)).append("\" ")
                if (id != null) sb.append('[').append(id.substringAfter(":id/")).append(']')
                if (n.isClickable) sb.append(" (toca)")
                sb.append('\n')
            }
            for (k in 0 until n.childCount) visitar(n.getChild(k), nivel + 1)
        }
        visitar(raiz, 0)
        return sb.toString()
    }
}
