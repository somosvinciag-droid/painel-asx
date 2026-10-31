package br.com.leonardo.painelasx.celular

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela do "Painel ASX Celular": se está ligado ao carro, os passos de configuração do HyperOS
 * (cada um com um botão que abre o ajuste certo) e o pareamento com a central.
 */
class MainActivity : Activity() {
    private val FUNDO = Color.parseColor("#0D0F0E")
    private val CARTAO = Color.parseColor("#161917")
    private val BORDA = Color.parseColor("#2A2E2B")
    private val TEXTO = Color.parseColor("#EDEDE8")
    private val CINZA = Color.parseColor("#9A9D99")
    private val VERDE = Color.parseColor("#2BB673")
    private val VERMELHO = Color.parseColor("#CE2B37")

    private val relogio = Handler(Looper.getMainLooper())
    private lateinit var estado: TextView
    private lateinit var detalhe: TextView
    private lateinit var passos: LinearLayout
    private lateinit var pareamento: LinearLayout
    private var ultimaAssinatura = ""

    private val atualizar = object : Runnable {
        override fun run() {
            try { pintar() } catch (_: Exception) { }
            relogio.postDelayed(this, 2000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        montar()
    }

    override fun onResume() {
        super.onResume()
        // Com o app na frente, a ponte liga (ou ganha) o microfone.
        Ponte.iniciar(this, comMicrofone = true)
        relogio.post(atualizar)
    }

    override fun onPause() {
        relogio.removeCallbacks(atualizar)
        super.onPause()
    }

    override fun onRequestPermissionsResult(codigo: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(codigo, perms, res)
        Ponte.iniciar(this, comMicrofone = true)
        pintar()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fundo(cor: Int, raio: Int, borda: Int? = null) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dp(raio).toFloat()
        if (borda != null) setStroke(dp(1), borda)
    }

    private fun texto(t: String, tam: Float, cor: Int, negrito: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(cor)
        if (negrito) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
    }

    private fun botao(t: String, acao: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; setTextColor(TEXTO)
        background = fundo(Color.parseColor("#232724"), 12, BORDA)
        setOnClickListener { try { acao() } catch (_: Exception) { abrirPaginaDoApp() } }
    }

    private fun cartao(pai: LinearLayout, titulo: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fundo(CARTAO, 16, BORDA)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(texto(titulo, 18f, TEXTO, true))
        pai.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        return c
    }

    private fun montar() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val rolar = ScrollView(this).apply { setBackgroundColor(FUNDO); addView(col) }
        // Android 15: a tela vai até as bordas; deixa espaço para as barras do sistema.
        rolar.setOnApplyWindowInsetsListener { v, ins ->
            val s = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            ins
        }
        setContentView(rolar)

        // faixa verde-branco-vermelho (igual à tela do carro)
        val faixa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in listOf(Color.parseColor("#009246"), Color.parseColor("#F1F2F1"), VERMELHO))
            faixa.addView(View(this).apply { setBackgroundColor(c) }, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(faixa, LinearLayout.LayoutParams(-1, dp(4)))
        col.addView(texto("Painel ASX Celular", 26f, TEXTO, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        estado = texto("", 18f, TEXTO, true)
        col.addView(estado, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        detalhe = texto("", 14f, CINZA)
        col.addView(detalhe)

        passos = cartao(col, "Configurar (uma vez só)")
        pareamento = cartao(col, "Carro permitido")

        val priv = cartao(col, "Privacidade")
        priv.addView(texto(
            "Este app só conversa com a central do seu carro (pela ancoragem Bluetooth ou pelo cabo). " +
                "Ele não manda nada para a internet. As mensagens só saem do celular se você ligar " +
                "\"Ler mensagens\" no Painel ASX do carro, e lá elas só são faladas, nunca guardadas.", 14f, CINZA))
        pintar()
    }

    // ---------------------------------------------------------------- estado e passos

    private fun temPermissao(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun acessoNotificacoes(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun semRestricaoBateria() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun pintar() {
        val ligado = Ponte.ligadoAoCarro()
        estado.text = (if (ligado) "🟢  " else "⚪  ") + Ponte.estadoTexto()
        estado.setTextColor(if (ligado) VERDE else TEXTO)
        detalhe.text = buildString {
            append("Microfone para o carro: ").append(if (Ponte.comMicrofone) "liberado" else "abra este app uma vez com o celular desbloqueado")
            append("\nRede: ").append(Ponte.estadoServidor())
            if (Ponte.ultimoMotivo.isNotEmpty()) append("\nÚltima queda: ").append(Ponte.ultimoMotivo)
        }

        // Só remonta os passos quando algo mudou (senão o botão "pisca" debaixo do dedo).
        val assinatura = listOf(temPermissao(Manifest.permission.POST_NOTIFICATIONS), temPermissao(Manifest.permission.RECORD_AUDIO),
            acessoNotificacoes(), semRestricaoBateria(), Ponte.ancoragemLigada(), Pareamento.pendente, Ponte.esperandoPermissao(),
            Pareamento.permitidas(this).sorted()).joinToString("|")
        if (assinatura == ultimaAssinatura) return
        ultimaAssinatura = assinatura
        passos.removeViews(1, (passos.childCount - 1).coerceAtLeast(0))
        passo("1. Avisos do app", temPermissao(Manifest.permission.POST_NOTIFICATIONS), "Permitir avisos") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        passo("2. Microfone (para o FALAR do carro)", temPermissao(Manifest.permission.RECORD_AUDIO), "Permitir o microfone") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }
        passo("3. Acesso às notificações (música e mensagens)", acessoNotificacoes(), "Abrir acesso às notificações",
            "Ligue \"Painel ASX Celular\". Se aparecer \"configuração restrita\", toque em \"Página do app\" abaixo, " +
                "nos 3 pontinhos lá em cima e em \"Permitir configurações restritas\". Depois volte aqui.") {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, Ouvinte::class.java).flattenToString())
            try { startActivity(i) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        passo("4. Bateria sem restrição", semRestricaoBateria(), "Liberar a bateria",
            "Se o HyperOS mostrar opções, escolha \"Sem restrições\".") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        passo("5. Início automático (Xiaomi)", null, "Abrir início automático",
            "Ligue \"Painel ASX Celular\" na lista. Se não abrir, use \"Página do app\".") {
            startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        }
        passo("6. Página do app", null, "Página do app",
            "Lá: Economia de bateria → Sem restrições; desligue \"Pausar atividade do app se não for usado\".") {
            abrirPaginaDoApp()
        }
        passo("7. Ancoragem Bluetooth ligada (no carro)", Ponte.ancoragemLigada(), "Abrir ancoragem",
            "É a mesma \"internet por Bluetooth\" que você já usa para a central.") {
            try { startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }

        pintarPareamento()
    }

    /** Um passo: título com ✅ (feito), ⬜ (falta) ou • (não dá para conferir), dica e botão. */
    private fun passo(titulo: String, feito: Boolean?, rotuloBotao: String, dica: String? = null, acao: () -> Unit) {
        val marca = when (feito) { true -> "✅  "; false -> "⬜  "; null -> "•  " }
        passos.addView(texto(marca + titulo, 16f, if (feito == true) CINZA else TEXTO, feito != true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (feito == true) return
        if (dica != null) passos.addView(texto(dica, 13f, CINZA))
        passos.addView(botao(rotuloBotao, acao), LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
    }

    private fun abrirPaginaDoApp() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }

    private fun pintarPareamento() {
        pareamento.removeViews(1, (pareamento.childCount - 1).coerceAtLeast(0))
        val pend = Pareamento.pendente
        if (pend != null && Ponte.esperandoPermissao()) {
            pareamento.addView(texto("Uma central quer se ligar: código ${Pareamento.codigo(pend)}. " +
                "Confira o código nos ajustes do Painel ASX no carro.", 15f, TEXTO))
            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            linha.addView(botao("Permitir") { Pareamento.permitir(applicationContext, pend); ultimaAssinatura = "" }, LinearLayout.LayoutParams(0, dp(50), 1f))
            linha.addView(botao("Recusar") { Pareamento.recusar(applicationContext, pend) },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(10) })
            pareamento.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val ok = Pareamento.permitidas(this)
        pareamento.addView(texto(
            if (ok.isEmpty()) "Nenhuma ainda. Na primeira vez que o carro se ligar, aparece o aviso \"Permitir o Painel ASX do carro?\". Toque em Permitir."
            else "Permitida: central " + ok.joinToString(", ") { "código " + Pareamento.codigo(it) } + ".",
            14f, CINZA), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (ok.isNotEmpty()) pareamento.addView(botao("Esquecer o carro") {
            AlertDialog.Builder(this)
                .setTitle("Esquecer o carro?")
                .setMessage("Na próxima conexão, o celular vai perguntar de novo.")
                .setPositiveButton("Esquecer") { _, _ -> Pareamento.esquecerTudo(applicationContext); ultimaAssinatura = ""; pintar() }
                .setNegativeButton("Cancelar", null)
                .show()
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
    }
}
