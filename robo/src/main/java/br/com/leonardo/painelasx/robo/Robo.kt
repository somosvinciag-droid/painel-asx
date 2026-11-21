package br.com.leonardo.painelasx.robo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * O "dedo" do Painel Redmi (Acessibilidade): lê a árvore da tela, toca, desliza, escreve e tira print.
 * Tudo é chamado pela linha dos comandos (nunca pela linha principal); as esperas usam CountDownLatch.
 */
class Robo : AccessibilityService() {

    companion object {
        @Volatile
        var ativo: Robo? = null
        @Volatile
        var pacoteAtual: String = ""
        private var ultimaReligada = 0L

        fun componente(ctx: Context) = ComponentName(ctx, Robo::class.java).flattenToString()

        /**
         * Se o adb deu WRITE_SECURE_SETTINGS (pm grant), o próprio app recoloca a Acessibilidade na lista
         * quando o sistema a tira (atualização, force-stop do HyperOS). Sem a permissão, não faz nada.
         */
        fun religarSePuder(ctx: Context) {
            if (ativo != null || !App.podeEscreverAjustes(ctx)) return
            if (System.currentTimeMillis() - ultimaReligada < 20_000) return
            ultimaReligada = System.currentTimeMillis()
            try {
                val cr = ctx.contentResolver
                val meu = componente(ctx)
                val atual = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val outros = atual.split(':').filter { it.isNotBlank() && !it.equals(meu, true) }
                // Tira e põe de novo: o Android só liga o serviço quando a lista MUDA.
                if (atual.contains(meu, true)) {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, outros.joinToString(":"))
                    Thread.sleep(300)
                }
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (outros + meu).joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Registro.anotar("acessibilidade: religada pelo próprio app")
            } catch (e: Exception) {
                Registro.anotar("ERRO acessibilidade: não consegui religar (${e.message})")
            }
        }

        fun precisa(): Robo = ativo ?: throw Exception(
            "A Acessibilidade do Painel Redmi está desligada. Ligue em Configurações > Acessibilidade > Painel Redmi."
        )

        fun semAcento(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()
    }

    private val principal = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ativo = this
        Registro.anotar("acessibilidade: ligada")
        App.garantir(this, "acessibilidade")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString() ?: return
            if (p != "com.android.systemui" && p != packageName) pacoteAtual = p
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ativo = null
        Registro.anotar("acessibilidade: desligada pelo sistema")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ativo = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ leitura da tela

    fun raizes(): List<AccessibilityNodeInfo> {
        val lista = ArrayList<AccessibilityNodeInfo>()
        try {
            // Janelas de cima para baixo (a do topo primeiro): teclado, diálogos, app.
            for (w in windows) w.root?.let { lista.add(it) }
        } catch (_: Exception) { }
        if (lista.isEmpty()) rootInActiveWindow?.let { lista.add(it) }
        return lista
    }

    data class Elemento(val no: AccessibilityNodeInfo, val caixa: Rect)

    fun todosOsNos(): List<Elemento> {
        val r = ArrayList<Elemento>()
        for (raiz in raizes()) andar(raiz, r, 0)
        return r
    }

    private fun andar(n: AccessibilityNodeInfo, r: MutableList<Elemento>, nivel: Int) {
        if (nivel > 60 || r.size > 3000) return
        val c = Rect()
        n.getBoundsInScreen(c)
        r.add(Elemento(n, c))
        for (i in 0 until n.childCount) {
            val f = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            andar(f, r, nivel + 1)
        }
    }

    fun textoDe(n: AccessibilityNodeInfo): String = (n.text?.toString() ?: "").replace('\n', ' ').trim()
    fun descDe(n: AccessibilityNodeInfo): String = (n.contentDescription?.toString() ?: "").replace('\n', ' ').trim()

    fun lerTela(todos: Boolean): String {
        val linhas = StringBuilder()
        val tela = tamanhoTela()
        linhas.append("app: ").append(rootInActiveWindow?.packageName ?: pacoteAtual)
            .append(" | tela ").append(tela.first).append("x").append(tela.second).append('\n')
        var n = 0
        for (e in todosOsNos()) {
            val no = e.no
            if (!no.isVisibleToUser) continue
            val t = textoDe(no)
            val d = descDe(no)
            val id = no.viewIdResourceName ?: ""
            val acoes = ArrayList<String>()
            if (no.isClickable) acoes.add("toca")
            if (no.isLongClickable) acoes.add("longo")
            if (no.isEditable) acoes.add("escreve")
            if (no.isScrollable) acoes.add("rola")
            if (no.isCheckable) acoes.add(if (no.isChecked) "marcado" else "desmarcado")
            if (no.isSelected) acoes.add("selecionado")
            if (no.isFocused) acoes.add("foco")
            if (!no.isEnabled) acoes.add("desativado")
            if (!todos && t.isEmpty() && d.isEmpty() && !no.isEditable && !(no.isClickable && id.isNotEmpty())) continue
            if (e.caixa.width() <= 0 || e.caixa.height() <= 0) continue
            val classe = (no.className?.toString() ?: "").substringAfterLast('.')
            linhas.append(n).append(". ").append(classe)
            if (t.isNotEmpty()) linhas.append(" \"").append(t.take(150)).append('"')
            if (d.isNotEmpty() && d != t) linhas.append(" (desc: ").append(d.take(150)).append(')')
            if (id.isNotEmpty()) linhas.append(" #").append(id.substringAfter(":id/"))
            if (acoes.isNotEmpty()) linhas.append(" {").append(acoes.joinToString(",")).append('}')
            linhas.append(" [").append(e.caixa.left).append(',').append(e.caixa.top).append(',')
                .append(e.caixa.right).append(',').append(e.caixa.bottom).append("] centro ")
                .append(e.caixa.centerX()).append(',').append(e.caixa.centerY()).append('\n')
            n++
            if (n >= 400) { linhas.append("... (cortado em 400)\n"); break }
        }
        return linhas.toString()
    }

    fun tamanhoTela(): Pair<Int, Int> {
        val wm = getSystemService(android.view.WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    /** Procura elementos visíveis por texto, descrição ou id. Exatos primeiro, depois "contém". */
    fun procurar(texto: String?, desc: String?, id: String?, exato: Boolean): List<Elemento> {
        val nos = todosOsNos().filter { it.no.isVisibleToUser && it.caixa.width() > 0 && it.caixa.height() > 0 }
        if (!id.isNullOrBlank()) {
            val alvo = id.trim()
            return nos.filter {
                val v = it.no.viewIdResourceName ?: ""
                v == alvo || v.endsWith(":id/$alvo")
            }
        }
        val busca = semAcento(texto ?: desc ?: return emptyList())
        if (busca.isEmpty()) return emptyList()
        fun valores(e: Elemento): List<String> = when {
            texto != null && desc == null -> listOf(semAcento(textoDe(e.no)), semAcento(descDe(e.no)))
            else -> listOf(semAcento(descDe(e.no)))
        }
        val iguais = nos.filter { e -> valores(e).any { it == busca } }
        if (exato || iguais.isNotEmpty()) return iguais
        return nos.filter { e -> valores(e).any { it.contains(busca) } }
    }

    // ------------------------------------------------------------------ ações

    fun gesto(caminho: Path, ms: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0, ms.coerceIn(1, 10_000)))
            .build()
        val fim = CountDownLatch(1)
        val ok = AtomicReference(false)
        principal.post {
            val aceito = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { ok.set(true); fim.countDown() }
                override fun onCancelled(d: GestureDescription?) { fim.countDown() }
            }, null)
            if (!aceito) fim.countDown()
        }
        fim.await(ms + 3000, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    fun tocarXY(x: Float, y: Float, longo: Boolean): Boolean {
        val p = Path()
        p.moveTo(x, y)
        return gesto(p, if (longo) 900 else 60)
    }

    fun deslizar(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        return gesto(p, ms)
    }

    /** Clica pelo Android (ACTION_CLICK no próprio elemento ou no pai clicável); sem isso, toque de verdade no centro. */
    fun tocarElemento(e: Elemento, longo: Boolean, gestoForcado: Boolean): String {
        if (!gestoForcado) {
            var n: AccessibilityNodeInfo? = e.no
            var subidas = 0
            val acao = if (longo) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            while (n != null && subidas < 6) {
                if ((if (longo) n.isLongClickable else n.isClickable) && n.isEnabled) {
                    if (n.performAction(acao)) return "toquei (clique) em [${e.caixa.centerX()},${e.caixa.centerY()}]"
                    break
                }
                n = n.parent
                subidas++
            }
        }
        val ok = tocarXY(e.caixa.exactCenterX(), e.caixa.exactCenterY(), longo)
        if (!ok) throw Exception("o toque não foi aceito pelo Android")
        return "toquei (dedo) em [${e.caixa.centerX()},${e.caixa.centerY()}]"
    }

    fun global(acao: Int): Boolean {
        val fim = CountDownLatch(1)
        val ok = AtomicReference(false)
        principal.post { ok.set(performGlobalAction(acao)); fim.countDown() }
        fim.await(3, TimeUnit.SECONDS)
        return ok.get()
    }

    fun campoComFoco(): AccessibilityNodeInfo? {
        for (r in raizes()) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null && f.isEditable) return f
        }
        return todosOsNos().firstOrNull { it.no.isEditable && it.no.isFocused }?.no
    }

    // ------------------------------------------------------------------ print

    /** Foto da tela pela Acessibilidade (Android 11+): não pede permissão nenhuma. */
    fun print(largura: Int, qualidade: Int): org.json.JSONObject {
        var tentativa = 0
        while (true) {
            val fim = CountDownLatch(1)
            val bmp = AtomicReference<Bitmap?>(null)
            val erro = AtomicReference(-1)
            principal.post {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                            bmp.set(hw?.copy(Bitmap.Config.ARGB_8888, false))
                            hw?.recycle()
                            r.hardwareBuffer.close()
                        } catch (_: Exception) { }
                        fim.countDown()
                    }
                    override fun onFailure(codigo: Int) { erro.set(codigo); fim.countDown() }
                })
            }
            fim.await(8, TimeUnit.SECONDS)
            val b = bmp.get()
            if (b != null) {
                val w = largura.coerceIn(200, b.width)
                val h = (b.height.toLong() * w / b.width).toInt()
                val pequena = if (w != b.width) Bitmap.createScaledBitmap(b, w, h, true) else b
                val saida = ByteArrayOutputStream()
                pequena.compress(Bitmap.CompressFormat.JPEG, qualidade.coerceIn(20, 95), saida)
                val o = org.json.JSONObject()
                    .put("imagem", Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP))
                    .put("largura", w).put("altura", h)
                    .put("tela_largura", b.width).put("tela_altura", b.height)
                    .put("escala", Math.round(b.width * 100.0 / w) / 100.0)
                if (pequena !== b) pequena.recycle()
                b.recycle()
                return o
            }
            // 3 = pedido rápido demais (1 por segundo): espera e tenta de novo.
            if (erro.get() == 3 && tentativa < 3) {
                tentativa++
                Thread.sleep(1100)
                continue
            }
            throw Exception("não consegui tirar o print (código ${erro.get()}; tela apagada ou app protegido?)")
        }
    }
}
