package br.com.leonardo.painelasx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
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

        /**
         * Esta central guarda a lista de Acessibilidade de um jeito diferente do Android
         * normal (o Controle funcionava e o app dizia "desligado"). Por isso confere de 3 jeitos.
         */
        fun ligado(ctx: Context): Boolean {
            if (ativo != null) return true
            try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val lista = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                if (lista.any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName }) return true
            } catch (_: Exception) {
            }
            val texto = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return texto.contains(ctx.packageName, ignoreCase = true)
        }
    }

    @Volatile
    var telaAtual = ""
        private set

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
        seguro("reabrir depois de atualizar") { Atualizador.reabrirSePrecisar(this) }
        seguro("procurar atualizacao") { Atualizador.verificarEmSegundoPlano(this) }
        seguro("rotina") { Rotina.talvezRodar(this, "controle ligado") }
        seguro("servico fixo") { ServicoFixo.iniciar(this) }
        seguro("limpar aviso") { Avisos.limpar(this, Avisos.ID_CONTROLE) }
        seguro("conector do Claude") { Conector.iniciar(this) }
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
            // A janela do Android pode demorar a mostrar o botao: tenta algumas vezes.
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            for (atraso in listOf(0L, 400L, 1000L, 2000L, 3500L, 6000L)) {
                h.postDelayed({
                    if (confirmarInstalacao && clicarTexto(BOTOES_INSTALAR)) {
                        Registro.anotar("atualizacao: confirmei a instalacao")
                        confirmarInstalacao = false
                    }
                }, atraso)
            }
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val classe = e.className?.toString() ?: ""
            val tela = "$pacote / $classe"
            if (tela != telaAtual && pacote != packageName) {
                telaAtual = tela
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
        var visivelSemBotao: AccessibilityNodeInfo? = null
        for (raiz in raizes) {
            for (t in textos) {
                val nos = raiz.findAccessibilityNodeInfosByText(t) ?: continue
                // Primeiro os que tem o texto EXATO (evita tocar em "Atualizar tudo" quando se pediu "Atualizar").
                val ordenados = nos.sortedBy { if (it.text?.toString()?.trim().equals(t, ignoreCase = true)) 0 else 1 }
                for (n in ordenados) {
                    var x: AccessibilityNodeInfo? = n
                    while (x != null && !x.isClickable) x = x.parent
                    if (x != null && x.isEnabled && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    if (visivelSemBotao == null && n.isVisibleToUser) visivelSemBotao = n
                }
            }
        }
        // Apps novos (Play Store, etc.) desenham botoes que nao dizem "sou clicavel":
        // toca na posicao do texto na tela, como um dedo de verdade.
        val alvo = visivelSemBotao ?: return false
        return tocarNoLugar(alvo)
    }

    private fun tocarNoLugar(n: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val caminho = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesto = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(caminho, 0, 60))
            .build()
        val ok = dispatchGesture(gesto, null, null)
        Registro.anotar("toque na posição (${r.centerX()}, ${r.centerY()}): ${if (ok) "ok" else "falhou"}")
        return ok
    }

    fun voltar() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun inicio() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recentes() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Os textos e os nomes dos botoes da tela atual (para o diagnostico). */
    fun lerTela(): String {
        // So agora (quando alguem pede) olha tambem os detalhes "escondidos" da tela; depois volta ao leve.
        val info = serviceInfo
        val antes = info?.flags ?: 0
        try {
            if (info != null) {
                info.flags = antes or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
            return lerTelaAgora()
        } finally {
            if (info != null) {
                info.flags = antes
                serviceInfo = info
            }
        }
    }

    private fun lerTelaAgora(): String {
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
