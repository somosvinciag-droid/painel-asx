package br.com.leonardo.painelasx

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Tela principal: falar comando, rotina, diagnostico e atualizacao. Letras grandes (carro). */
class MainActivity : Activity() {

    private val PEDIDO_VOZ = 1
    private val FUNDO = Color.parseColor("#0E1A2B")
    private val CARTAO = Color.parseColor("#17263D")
    private val OURO = Color.parseColor("#F2A93B")
    private val TEXTO = Color.parseColor("#EEF2F7")
    private val CINZA = Color.parseColor("#9AA8BC")

    private lateinit var resposta: TextView
    private lateinit var estadoControle: TextView
    private lateinit var estadoInstalar: TextView
    private lateinit var botaoControle: Button
    private lateinit var botaoInstalar: Button
    private lateinit var botaoMusica: Button
    private lateinit var botaoNavegacao: Button
    private lateinit var interruptorRotina: Switch
    private lateinit var textoVolume: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Registro.iniciar(this)
        Voz.iniciar(this)
        Atualizador.agendar(this)
        montarTela()
        Atualizador.verificarEmSegundoPlano(this)
    }

    override fun onResume() {
        super.onResume()
        atualizarEstados()
    }

    // ---------------------------------------------------------------- tela

    private fun montarTela() {
        val rolagem = ScrollView(this).apply { setBackgroundColor(FUNDO) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(22), dp(28), dp(40))
        }
        rolagem.addView(col)
        setContentView(rolagem)

        col.addView(TextView(this).apply {
            text = "Painel ASX"
            setTextColor(TEXTO); textSize = 30f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "versão v${BuildConfig.VERSION_CODE} · atualiza sozinho"
            setTextColor(CINZA); textSize = 15f
            setPadding(0, 0, 0, dp(18))
        })

        // Falar
        val falar = botao("🎤  FALAR COMANDO", OURO, Color.parseColor("#1A1206"), 26f)
        falar.setOnClickListener { ouvir() }
        col.addView(falar, LinearLayout.LayoutParams(-1, dp(96)))

        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val campo = EditText(this).apply {
            hint = "ou escreva um comando…"
            setHintTextColor(CINZA); setTextColor(TEXTO); textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            background = fundo(CARTAO, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val ir = botao("Fazer", CARTAO, TEXTO, 18f)
        val fazer = { executar(campo.text.toString()); campo.setText("") }
        ir.setOnClickListener { fazer() }
        campo.setOnEditorActionListener { _, _, _ -> fazer(); true }
        linha.addView(campo, LinearLayout.LayoutParams(0, dp(60), 1f))
        linha.addView(ir, LinearLayout.LayoutParams(dp(120), dp(60)).apply { leftMargin = dp(10) })
        col.addView(linha)

        resposta = TextView(this).apply {
            setTextColor(OURO); textSize = 20f
            setPadding(0, dp(14), 0, dp(6))
            text = ""
        }
        col.addView(resposta)

        // Permissoes
        col.addView(titulo("Permissões"))
        estadoControle = info()
        botaoControle = botao("Ligar controle (Acessibilidade)", CARTAO, TEXTO, 17f)
        botaoControle.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Ligar o controle")
                .setMessage("Na próxima tela, toque em \"Painel ASX\" e ligue a chave. O Android vai mostrar um aviso: é normal, é o que deixa o app tocar na tela por você.")
                .setPositiveButton("Abrir") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("Agora não", null)
                .show()
        }
        estadoInstalar = info()
        botaoInstalar = botao("Permitir atualizações automáticas", CARTAO, TEXTO, 17f)
        botaoInstalar.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        }
        col.addView(estadoControle); col.addView(botaoControle, alto())
        col.addView(estadoInstalar); col.addView(botaoInstalar, alto())

        // Rotina
        col.addView(titulo("Quando o carro ligar"))
        interruptorRotina = Switch(this).apply {
            text = "Rodar a rotina sozinho"
            setTextColor(TEXTO); textSize = 19f
            isChecked = Rotina.ligada(this@MainActivity)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, v -> Rotina.prefs(this@MainActivity).edit().putBoolean("ligada", v).apply() }
        }
        col.addView(interruptorRotina)

        botaoMusica = botao("", CARTAO, TEXTO, 17f)
        botaoMusica.setOnClickListener { escolherApp("musica", "App de música") }
        botaoNavegacao = botao("", CARTAO, TEXTO, 17f)
        botaoNavegacao.setOnClickListener { escolherApp("navegacao", "App de GPS") }
        col.addView(botaoMusica, alto()); col.addView(botaoNavegacao, alto())

        val tocar = Switch(this).apply {
            text = "Dar play na música sozinho"
            setTextColor(TEXTO); textSize = 17f
            isChecked = Rotina.tocarSozinho(this@MainActivity)
            setPadding(0, dp(10), 0, dp(6))
            setOnCheckedChangeListener { _, v -> Rotina.prefs(this@MainActivity).edit().putBoolean("tocar", v).apply() }
        }
        col.addView(tocar)

        textoVolume = info()
        col.addView(textoVolume)
        col.addView(SeekBar(this).apply {
            max = 100
            progress = Rotina.volume(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, v: Int, u: Boolean) {
                    Rotina.prefs(this@MainActivity).edit().putInt("volume", v).apply()
                    textoVolume.text = "Volume ao ligar: $v%"
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(56)))

        val testar = botao("▶  Testar a rotina agora", CARTAO, OURO, 17f)
        testar.setOnClickListener { Rotina.rodar(this); mostrar("Rodando a rotina.") }
        col.addView(testar, alto())

        // Diagnostico e atualizacao
        col.addView(titulo("Diagnóstico e atualização"))
        val ver = botao("👀  Ver diagnóstico na tela (para foto)", CARTAO, OURO, 17f)
        ver.setOnClickListener {
            val t = TextView(this).apply {
                text = Registro.ultimas(30).joinToString("\n").ifEmpty { "(nada anotado ainda)" }
                setTextColor(Color.BLACK); textSize = 15f
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(this)
                .setTitle("v${BuildConfig.VERSION_CODE} · controle " + (if (Controle.ligado(this)) "LIGADO" else "DESLIGADO"))
                .setView(ScrollView(this).apply { addView(t) })
                .setPositiveButton("Fechar", null)
                .show()
        }
        col.addView(ver, alto())
        val diag = botao("📋  Enviar diagnóstico", CARTAO, TEXTO, 17f)
        diag.setOnClickListener { enviarDiagnostico() }
        col.addView(diag, alto())
        val atualizar = botao("⟳  Procurar atualização agora", CARTAO, TEXTO, 17f)
        atualizar.setOnClickListener {
            mostrar("Procurando atualização…", falar = false)
            Thread { Atualizador.verificar(applicationContext) { m -> runOnUiThread { mostrar(m, falar = false) } } }.start()
        }
        col.addView(atualizar, alto())

        // Ajuda
        col.addView(titulo("O que você pode falar"))
        col.addView(TextView(this).apply {
            text = Comandos.AJUDA.joinToString("\n") { "•  $it" }
            setTextColor(CINZA); textSize = 16f
            setLineSpacing(0f, 1.25f)
        })
    }

    private fun atualizarEstados() {
        val ctlOk = Controle.ligado(this)
        estadoControle.text = if (ctlOk) "✅ Controle ligado" else "⚠️ Controle desligado: comandos de tela e atualização sem toque não funcionam"
        botaoControle.visibility = if (ctlOk) View.GONE else View.VISIBLE
        val instOk = packageManager.canRequestPackageInstalls()
        estadoInstalar.text = if (instOk) "✅ Atualizações automáticas permitidas" else "⚠️ Atualizações automáticas ainda não permitidas"
        botaoInstalar.visibility = if (instOk) View.GONE else View.VISIBLE
        botaoMusica.text = "🎵  Música: " + Apps.nomeDe(this, Rotina.musica(this))
        botaoNavegacao.text = "🧭  GPS: " + Apps.nomeDe(this, Rotina.navegacao(this))
        textoVolume.text = "Volume ao ligar: ${Rotina.volume(this)}%"
    }

    // ---------------------------------------------------------------- acoes

    private fun ouvir() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fala o comando")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PEDIDO_VOZ)
        } catch (e: Exception) {
            mostrar("A central não tem reconhecimento de voz. Instale o app \"Google\" pela Play Store.")
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
        if (frase.isBlank()) return
        mostrar(Comandos.executar(this, frase))
    }

    private fun mostrar(t: String, falar: Boolean = true) {
        resposta.text = t
        if (falar) Voz.falar(t)
    }

    private fun escolherApp(chave: String, titulo: String) {
        val apps = Apps.listar(this)
        val nomes = (listOf("— nenhum —") + apps.map { it.nome }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setItems(nomes) { _, i ->
                val ed = Rotina.prefs(this).edit()
                if (i == 0) ed.remove(chave) else ed.putString(chave, apps[i - 1].pacote)
                ed.apply()
                atualizarEstados()
            }
            .show()
    }

    private fun enviarDiagnostico() {
        val t = Diagnostico.texto(this)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico Painel ASX")
            putExtra(Intent.EXTRA_TEXT, t)
        }
        try {
            startActivity(Intent.createChooser(i, "Enviar diagnóstico por…"))
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(t).setPositiveButton("OK", null).show()
        }
    }

    // ---------------------------------------------------------------- ajudantes visuais

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fundo(cor: Int, raio: Int) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dp(raio).toFloat()
    }

    private fun botao(t: String, cor: Int, corTexto: Int, tam: Float) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(corTexto)
        textSize = tam
        typeface = Typeface.DEFAULT_BOLD
        background = fundo(cor, 16)
        gravity = Gravity.CENTER
    }

    private fun alto() = LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(10) }

    private fun titulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(TEXTO); textSize = 21f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(28), 0, dp(4))
    }

    private fun info() = TextView(this).apply {
        setTextColor(CINZA); textSize = 16f
        setPadding(0, dp(10), 0, 0)
    }
}
