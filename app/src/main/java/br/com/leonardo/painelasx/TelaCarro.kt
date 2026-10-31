package br.com.leonardo.painelasx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Abre a tela do carro (de qualquer lugar). */
object Painel {
    fun abrir(ctx: Context, ouvir: Boolean = false) {
        try {
            val i = Intent(ctx, TelaCarro::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (ouvir) i.action = MainActivity.ACAO_OUVIR
            ctx.startActivity(i)
        } catch (e: Exception) {
            Registro.anotar("tela do carro: não abriu (${e.javaClass.simpleName})")
        }
    }
}

/**
 * Tela inicial do carro, estilo Mitsubishi (preto e vermelho):
 * velocidade grande (GPS), música tocando, viagem do dia, semana, tempo e os atalhos.
 * Abre sozinha quando o carro liga.
 */
class TelaCarro : Activity() {
    private val VERMELHO = Color.parseColor("#E4002B")
    private val VERMELHO_ESCURO = Color.parseColor("#3A0610")
    private val CARTAO = Color.parseColor("#141417")
    private val BORDA = Color.parseColor("#26262B")
    private val BRANCO = Color.parseColor("#F4F4F6")
    private val CINZA = Color.parseColor("#8B8B94")
    private val OURO = Color.parseColor("#F2A93B")

    private val PEDIDO_VOZ = 7
    private val relogio = Handler(Looper.getMainLooper())
    private var tique = 0

    private lateinit var raiz: LinearLayout
    private lateinit var hora: TextView
    private lateinit var velocidade: TextView
    private lateinit var limite: TextView
    private lateinit var clima: TextView
    private lateinit var musica: TextView
    private lateinit var hoje: TextView
    private lateinit var semana: TextView
    private lateinit var aviso: TextView
    private lateinit var sistema: Button
    private lateinit var anjo: Button

    private val atualizar = object : Runnable {
        override fun run() {
            try { pintar() } catch (_: Exception) { }
            tique++
            relogio.postDelayed(this, 1000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        Voz.iniciar(this)
        ServicoFixo.iniciar(this)
        montar()
        tratar(intent)
        // A central acabou de ligar/acordar e o Android nos abriu como tela inicial: o Jarvis cumprimenta.
        if (android.os.SystemClock.elapsedRealtime() - App.inicio < 20_000) {
            Registro.anotar("tela do carro: aberta pelo sistema (central ligou/acordou)")
            Jarvis.partida(applicationContext)
        }
    }

    /** Sendo a tela inicial, o "voltar" não fecha o sistema. */
    @Deprecated("API antiga")
    override fun onBackPressed() { }

    private fun virarTelaInicial() {
        Nwd.pedirProp(this, Nwd.PROP_LAUNCHER, packageName)
        relogio.postDelayed({
            if (Nwd.somosTelaInicial(this)) {
                Voz.falar("Pronto, senhor. Agora eu sou a tela inicial do carro.")
            } else {
                // A NWD não aceitou: abre a escolha de tela inicial do Android (marcar Painel ASX e "Sempre").
                try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) {
                    try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                }
                Voz.falar("Escolha Painel ASX e toque em Sempre.")
            }
        }, 1500)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        tratar(intent)
    }

    private fun tratar(i: Intent?) {
        if (i?.action == MainActivity.ACAO_OUVIR) relogio.postDelayed({ ouvir() }, 300)
    }

    override fun onResume() {
        super.onResume()
        tique = 0
        relogio.post(atualizar)
    }

    override fun onPause() {
        relogio.removeCallbacks(atualizar)
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fundo(cor: Int, raio: Int, borda: Int? = null) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dp(raio).toFloat()
        if (borda != null) setStroke(dp(1), borda)
    }

    private fun texto(t: String, tam: Float, cor: Int, negrito: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(cor)
        if (negrito) typeface = Typeface.DEFAULT_BOLD
    }

    private fun cartao(titulo: String): Pair<LinearLayout, TextView> {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fundo(CARTAO, 18, BORDA)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        c.addView(texto(titulo.uppercase(), 12f, VERMELHO, true).apply { letterSpacing = 0.12f })
        val corpo = texto("—", 18f, BRANCO).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.15f) }
        c.addView(corpo)
        return c to corpo
    }

    private fun botao(t: String, cor: Int, corTexto: Int, tam: Float, clique: () -> Unit) = Button(this).apply {
        text = t; textSize = tam; setTextColor(corTexto); isAllCaps = false
        background = fundo(cor, 18)
        setOnClickListener { clique() }
    }

    private fun lp(peso: Float, margem: Int = 0) = LinearLayout.LayoutParams(0, -1, peso).apply { leftMargin = dp(margem) }
    private fun linha(alt: Int, cima: Int = 10) = LinearLayout.LayoutParams(-1, alt).apply { topMargin = dp(cima) }

    private fun montar() {
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            val esquerda = if (Barra.ligada(this@TelaCarro)) dp(88) else dp(16)
            setPadding(esquerda, dp(14), dp(16), dp(14))
        }
        setContentView(raiz)

        // ---------- coluna 1: velocidade, hora, tempo
        val c1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topo.addView(texto("ASX", 30f, VERMELHO, true).apply { letterSpacing = 0.2f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC) },
            LinearLayout.LayoutParams(0, -2, 1f))
        hora = texto("", 26f, BRANCO, true)
        topo.addView(hora)
        c1.addView(topo)
        velocidade = texto("0", 120f, BRANCO, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        c1.addView(velocidade, LinearLayout.LayoutParams(-1, 0, 1f))
        c1.addView(texto("km/h", 18f, CINZA).apply { gravity = Gravity.CENTER })
        limite = texto("", 15f, BRANCO, true).apply {
            gravity = Gravity.CENTER
            background = fundo(VERMELHO_ESCURO, 99, VERMELHO)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { Voz.falar("Para mudar, fale: limite 100.") }
        }
        c1.addView(limite, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        clima = texto("—", 17f, BRANCO).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        c1.addView(clima)
        raiz.addView(c1, lp(1.05f))

        // ---------- coluna 2: música, hoje, semana, aviso
        val c2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (cm, tm) = cartao("Tocando agora")
        musica = tm
        val controles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controles.addView(botao("⏮", BORDA, BRANCO, 22f) { Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        controles.addView(botao("⏯", VERMELHO, Color.WHITE, 22f) {
            if (Musica.pararRadio()) return@botao
            Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
        controles.addView(botao("⏭", BORDA, BRANCO, 22f) { Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_NEXT) }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(8) })
        cm.addView(controles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        c2.addView(cm)
        val (ch, th) = cartao("Hoje"); hoje = th; c2.addView(ch, linha(-2))
        val (cs, ts) = cartao("Últimos 7 dias"); semana = ts; c2.addView(cs, linha(-2))
        aviso = texto("", 16f, OURO).apply { setPadding(dp(4), dp(10), dp(4), 0); maxLines = 3 }
        c2.addView(aviso)
        raiz.addView(c2, lp(1.25f, 14))

        // ---------- coluna 3: atalhos
        val c3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c3.addView(botao("🎤\nFalar", VERMELHO, Color.WHITE, 22f) { ouvir() }, LinearLayout.LayoutParams(-1, 0, 1.6f))
        for ((nome, rotulo) in listOf("casa" to "🏠  Casa", "mineirita" to "📍  Mineirita", "familia" to "👨‍👩‍👦  Família")) {
            c3.addView(botao(rotulo, CARTAO, BRANCO, 17f) { executar("rota para $nome") }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        }
        val linhaBaixo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linhaBaixo.addView(botao("▦  Apps", BORDA, BRANCO, 15f) { TodosApps.abrir(this) }, LinearLayout.LayoutParams(0, -1, 1f))
        linhaBaixo.addView(botao("⚙", BORDA, CINZA, 18f) {
            startActivity(Intent(this, MainActivity::class.java))
        }, LinearLayout.LayoutParams(0, -1, 0.6f).apply { leftMargin = dp(8) })
        c3.addView(linhaBaixo, LinearLayout.LayoutParams(-1, 0, 0.8f).apply { topMargin = dp(10) })
        sistema = botao("★  Fazer do Painel ASX o sistema do carro", VERMELHO_ESCURO, BRANCO, 14f) { virarTelaInicial() }
        c3.addView(sistema, LinearLayout.LayoutParams(-1, 0, 0.7f).apply { topMargin = dp(10) })
        anjo = botao("🛡  Ligar o Jarvis voltar sozinho", VERMELHO_ESCURO, BRANCO, 14f) { ligarAnjo() }
        anjo.visibility = if (Musica.podeVerMusica(this)) View.GONE else View.VISIBLE
        c3.addView(anjo, LinearLayout.LayoutParams(-1, 0, 0.7f).apply { topMargin = dp(10) })
        raiz.addView(c3, lp(0.75f, 14))
    }

    private fun pintar() {
        val noite = Clima.ehNoite()
        raiz.setBackgroundColor(if (noite) Color.BLACK else Color.parseColor("#0A0A0C"))
        hora.text = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())
        val kmh = Carro.velocidadeKmh.toInt()
        val lim = Config.limite(this)
        velocidade.text = kmh.toString()
        velocidade.setTextColor(if (kmh > lim) VERMELHO else if (noite) Color.parseColor("#D8D8DC") else BRANCO)
        limite.text = "LIMITE  $lim"
        if (tique % 5 == 0) {
            clima.text = Clima.curto()
            musica.text = Musica.tocandoAgora(this)
                ?: if (Voz.tocandoMusica()) "🎵 Música tocando" + (if (!Musica.podeVerMusica(this)) "\n(libere \"ver a música\" no Painel)" else "")
                else "Nada tocando"
            val d = Viagens.resumo(1)
            val u = Viagens.ultima()
            hoje.text = "${d.optDouble("km_total")} km · ${d.optInt("viagens")} viagens · ${d.optDouble("horas_dirigindo")} h" +
                (if (u?.has("nota") == true) "\nÚltima viagem: nota ${u.optInt("nota")}" else "") +
                (if (d.optBoolean("em_andamento")) "\n🟢 Em viagem" else "")
            val s = Viagens.resumo(7)
            semana.text = "${s.optDouble("km_total")} km · ${s.optInt("viagens")} viagens · ${s.optDouble("horas_dirigindo")} h" +
                (if (!s.isNull("nota_media")) "\nNota média: ${s.optInt("nota_media")}" else "")
            aviso.text = Voz.ultimaFala
            if (tique % 30 == 0) Rede.emSegundoPlano("tela-inicial") {
                val ja = Nwd.somosTelaInicial(this)
                Rede.naTela {
                    sistema.visibility = if (ja) View.GONE else View.VISIBLE
                    anjo.visibility = if (Musica.podeVerMusica(this)) View.GONE else View.VISIBLE
                }
            }
        }
    }

    /** Uma vez só: liga o "acesso às notificações", que o Android religa sozinho depois que a central fecha o app. */
    private fun ligarAnjo() {
        Voz.falar("Na próxima tela, ligue o Painel ASX. É uma vez só, senhor.", Voz.Tipo.RESPOSTA)
        try {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Registro.anotar("anjo da guarda: tela de ajustes não abriu (${e.javaClass.simpleName})")
            Voz.falar("Não consegui abrir os ajustes. Peça ao Claude para ligar pelo notebook.", Voz.Tipo.RESPOSTA)
        }
    }

    // ---------------------------------------------------------------- voz

    private fun ouvir() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Às suas ordens")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PEDIDO_VOZ)
        } catch (e: Exception) {
            Voz.falar("A central não tem reconhecimento de voz. Instale o app Google pela Play Store.")
        }
    }

    @Deprecated("API antiga, mas simples e funciona em todas as centrais")
    override fun onActivityResult(pedido: Int, resultado: Int, dados: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(pedido, resultado, dados)
        if (pedido != PEDIDO_VOZ || resultado != RESULT_OK) return
        val frase = dados?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        executar(frase)
    }

    private fun executar(frase: String) {
        val r = Comandos.executar(this, frase)
        aviso.text = r
        Voz.falar(r)
    }
}
