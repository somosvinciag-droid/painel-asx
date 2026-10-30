package br.com.leonardo.painelasx

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Tela principal, com cara de painel de carro:
 *  - em cima, 3 sinais claros (Controle, Claude, Memória);
 *  - alertas só quando precisa (Religar o Controle / Atualizar);
 *  - botões grandes (Falar, Turbo);
 *  - o resto em cartões. Em tela larga (a central), duas colunas.
 */
class MainActivity : Activity() {

    companion object {
        /** true enquanto o app esta aberto na tela (para reabrir depois de atualizar). */
        @Volatile
        var naTela = false

        /** Pedido vindo do microfone da barra lateral. */
        const val ACAO_OUVIR = "br.com.leonardo.painelasx.OUVIR"
    }

    private val PEDIDO_VOZ = 1

    // Paleta
    private val FUNDO = Color.parseColor("#0B1220")
    private val CARTAO = Color.parseColor("#131C2E")
    private val BORDA = Color.parseColor("#22304A")
    private val BOTAO = Color.parseColor("#1B2740")
    private val OURO = Color.parseColor("#F2A93B")
    private val TINTA_OURO = Color.parseColor("#1A1206")
    private val VERDE = Color.parseColor("#2BB673")
    private val VERMELHO = Color.parseColor("#E5484D")
    private val TEXTO = Color.parseColor("#EEF2F7")
    private val CINZA = Color.parseColor("#8E9BB0")

    private lateinit var resposta: TextView
    private lateinit var chipControle: TextView
    private lateinit var chipClaude: TextView
    private lateinit var chipMemoria: TextView
    private lateinit var alertaReligar: View
    private lateinit var alertaAtualizar: View
    private lateinit var textoAtualizar: TextView
    private lateinit var botaoMusica: Button
    private lateinit var botaoNavegacao: Button
    private lateinit var textoVolume: TextView
    private lateinit var permissoes: LinearLayout
    private lateinit var textoViagens: TextView
    private lateinit var textoJarvis: TextView

    private val relogio = Handler(Looper.getMainLooper())
    private val atualizarSinais = object : Runnable {
        override fun run() {
            atualizarEstados()
            relogio.postDelayed(this, 3000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Registro.iniciar(this)
        Voz.iniciar(this)
        Atualizador.agendar(this)
        montarTela()
        Atualizador.verificarEmSegundoPlano(this)
        ServicoFixo.iniciar(this)
        Conector.iniciar(this)
        tratarPedido(intent)
        val p = getSharedPreferences("tela", MODE_PRIVATE)
        if (!p.getBoolean("pediu_permissoes", false)) {
            p.edit().putBoolean("pediu_permissoes", true).apply()
            pedirPermissoesSimples()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        tratarPedido(intent)
    }

    override fun onResume() {
        super.onResume()
        naTela = true
        relogio.post(atualizarSinais)
    }

    override fun onPause() {
        naTela = false
        relogio.removeCallbacks(atualizarSinais)
        super.onPause()
    }

    override fun onRequestPermissionsResult(codigo: Int, perms: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, perms, resultados)
        LeitorCarro.ligarGps(applicationContext)
        atualizarEstados()
    }

    private fun pedirPermissoesSimples() {
        val faltam = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (faltam.isNotEmpty()) requestPermissions(faltam.toTypedArray(), 7)
    }

    // ---------------------------------------------------------------- aprovação

    /** Veio do aviso "Atualização disponível": o toque do Leonardo É a aprovação. */
    private fun tratarPedido(i: Intent?) {
        if (i?.action == Avisos.ACAO_ATUALIZAR) aprovarAtualizacao()
        if (i?.action == ACAO_OUVIR) relogio.postDelayed({ ouvir() }, 300)
    }

    private fun aprovarAtualizacao() {
        mostrar("Atualizando… o app fecha e abre de novo. Depois, toque em \"Religar\" se aparecer.", falar = false)
        Registro.anotar("atualizacao: aprovada pelo Leonardo no painel")
        Thread {
            Atualizador.verificar(applicationContext, instalar = true) { m -> runOnUiThread { mostrar(m, falar = false) } }
        }.start()
    }

    private fun abrirAcessibilidade() {
        val componente = packageName + "/" + Controle::class.java.name
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        i.putExtra(":settings:fragment_args_key", componente)
        i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", componente) })
        try {
            startActivity(i)
            mostrar("Toque em \"Painel ASX\", ligue a chave e toque em Permitir.", falar = false)
        } catch (e: Exception) {
            mostrar("Abra Configurações → Acessibilidade → Painel ASX.", falar = false)
        }
    }

    // ---------------------------------------------------------------- tela

    private fun montarTela() {
        val larga = resources.displayMetrics.let { it.widthPixels / it.density } >= 720
        val raiz = LinearLayout(this).apply {
            orientation = if (larga) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(FUNDO)
        }
        setContentView(raiz)

        val esquerda = coluna()
        val direita = coluna()
        if (larga) {
            raiz.addView(rolar(esquerda), LinearLayout.LayoutParams(0, -1, 1f))
            raiz.addView(rolar(direita), LinearLayout.LayoutParams(0, -1, 1.15f))
        } else {
            val tudo = coluna()
            tudo.addView(esquerda); tudo.addView(direita)
            raiz.addView(rolar(tudo), LinearLayout.LayoutParams(-1, -1))
        }

        // ---------- coluna da esquerda: estado + ações ----------
        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topo.addView(TextView(this).apply {
            text = "Painel ASX"
            setTextColor(TEXTO); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topo.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_CODE}"
            setTextColor(CINZA); textSize = 14f
            background = fundo(BOTAO, 99)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        })
        esquerda.addView(topo)

        val sinais = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipControle = chip(); chipClaude = chip(); chipMemoria = chip()
        sinais.addView(chipControle, pesoChip(0)); sinais.addView(chipClaude, pesoChip(8)); sinais.addView(chipMemoria, pesoChip(8))
        esquerda.addView(sinais, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // Alertas (só aparecem quando precisa)
        alertaReligar = alerta(
            CINZA, "Controle (opcional) desligado",
            "Só serve para eu ler a tela e tocar em botões. Abrir apps, volume, Turbo e dados funcionam sem ele.", "Ligar"
        ) { abrirAcessibilidade() }
        esquerda.addView(alertaReligar)
        textoAtualizar = TextView(this)
        alertaAtualizar = alerta(
            OURO, "Atualização disponível",
            "Toque em Atualizar aqui, ou peça ao Claude no celular: \"pode atualizar\".", "Atualizar", textoAtualizar
        ) { aprovarAtualizacao() }
        esquerda.addView(alertaAtualizar)

        // Botões grandes
        val grandes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val falar = botao("🎤\nFalar", OURO, TINTA_OURO, 22f).apply { setOnClickListener { ouvir() } }
        val turbo = botao("⚡\nTurbo", Color.parseColor("#1E7A4F"), Color.WHITE, 22f).apply {
            setOnClickListener {
                mostrar("Liberando memória…", falar = false)
                Thread {
                    val r = Turbo.limpar(applicationContext)
                    runOnUiThread { mostrar(r, falar = false); atualizarEstados() }
                }.start()
            }
        }
        grandes.addView(falar, LinearLayout.LayoutParams(0, dp(116), 1f))
        grandes.addView(turbo, LinearLayout.LayoutParams(0, dp(116), 1f).apply { leftMargin = dp(12) })
        esquerda.addView(grandes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // Comando escrito
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val campo = EditText(this).apply {
            hint = "ou escreva: \"abre o Waze\", \"volume 30\"…"
            setHintTextColor(CINZA); setTextColor(TEXTO); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            background = fundo(CARTAO, 14, BORDA)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val ir = botao("Fazer", BOTAO, TEXTO, 17f)
        val fazer = { executar(campo.text.toString()); campo.setText("") }
        ir.setOnClickListener { fazer() }
        campo.setOnEditorActionListener { _, _, _ -> fazer(); true }
        linha.addView(campo, LinearLayout.LayoutParams(0, dp(58), 1f))
        linha.addView(ir, LinearLayout.LayoutParams(dp(100), dp(58)).apply { leftMargin = dp(10) })
        esquerda.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        resposta = TextView(this).apply {
            setTextColor(OURO); textSize = 18f
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        esquerda.addView(resposta)

        // ---------- coluna da direita: cartões ----------
        // Permissões que faltam (só aparece se faltar algo)
        permissoes = cartao(direita, "Falta liberar")

        // Barra lateral (estilo Android Auto)
        val barra = cartao(direita, "Barra lateral")
        barra.addView(rotulo("Fica do lado esquerdo, por cima de qualquer app, com os seus apps favoritos."))
        barra.addView(interruptor("Mostrar a barra lateral", Barra.ligada(this)) { Barra.mudarLigada(this, it) })
        barra.addView(botao("✏️  Escolher os apps da barra", BOTAO, TEXTO, 16f).apply {
            setOnClickListener { escolherAppsDaBarra() }
        }, alto())

        // Jarvis e a tela do carro
        val jv = cartao(direita, "Jarvis e o carro")
        textoJarvis = TextView(this).apply {
            setTextColor(TEXTO); textSize = 15f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6), 0, dp(4))
        }
        jv.addView(textoJarvis)
        jv.addView(botao("🚗  Abrir a tela do carro", Color.parseColor("#B0001F"), Color.WHITE, 16f).apply {
            setOnClickListener { Painel.abrir(this@MainActivity) }
        }, alto())
        jv.addView(interruptor("\"Iron Man\" no Spotify quando o carro liga", Jarvis.musicaDeEntrada(this)) {
            Jarvis.mudarMusicaDeEntrada(this, it)
        })
        jv.addView(botao("▶  Testar as boas-vindas", BOTAO, OURO, 16f).apply {
            setOnClickListener { Jarvis.partida(applicationContext) }
        }, alto())
        jv.addView(rotulo("Avisos no celular (estacionamento, lembretes): instale o app \"ntfy\" no celular e assine este canal:"))
        jv.addView(botao("📋  Copiar o canal: " + Config.topicoNtfy(this), BOTAO, TEXTO, 14f).apply {
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Canal ntfy", Config.topicoNtfy(this@MainActivity)))
                mostrar("Canal copiado. No app ntfy do celular: + (assinar) e cole o nome.", falar = false)
            }
        }, alto())
        jv.addView(interruptor("Já assinei o canal no celular", getSharedPreferences("config", MODE_PRIVATE).getBoolean("ntfy_ok", false)) {
            getSharedPreferences("config", MODE_PRIVATE).edit().putBoolean("ntfy_ok", it).apply()
        })

        // Viagens (GPS)
        val viag = cartao(direita, "Viagens")
        textoViagens = TextView(this).apply {
            setTextColor(TEXTO); textSize = 16f
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(6), 0, dp(4))
        }
        viag.addView(textoViagens)
        viag.addView(botao("📍  Onde está o carro", BOTAO, TEXTO, 16f).apply {
            setOnClickListener { executar("onde está o carro") }
        }, alto())

        // Rotina
        val rotina = cartao(direita, "Quando o carro ligar")
        rotina.addView(interruptor("Rodar a rotina sozinho", Rotina.ligada(this)) {
            Rotina.prefs(this).edit().putBoolean("ligada", it).apply()
        })
        botaoMusica = botao("", BOTAO, TEXTO, 16f).apply { setOnClickListener { escolherApp("musica", "App de música") } }
        botaoNavegacao = botao("", BOTAO, TEXTO, 16f).apply { setOnClickListener { escolherApp("navegacao", "App de GPS") } }
        rotina.addView(botaoMusica, alto()); rotina.addView(botaoNavegacao, alto())
        rotina.addView(interruptor("Dar play na música sozinho", Rotina.tocarSozinho(this)) {
            Rotina.prefs(this).edit().putBoolean("tocar", it).apply()
        })
        textoVolume = rotulo("")
        rotina.addView(textoVolume)
        rotina.addView(SeekBar(this).apply {
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
        }, LinearLayout.LayoutParams(-1, dp(48)))
        rotina.addView(botao("▶  Testar a rotina agora", BOTAO, OURO, 16f).apply {
            setOnClickListener { Rotina.rodar(this@MainActivity); mostrar("Rodando a rotina.") }
        }, alto())

        // Claude
        val claude = cartao(direita, "Seu Claude")
        claude.addView(rotulo("Peça pelo Claude (celular ou aqui) e eu faço na central."))
        claude.addView(botao("🔗  Copiar link do conector", BOTAO, TEXTO, 16f).apply {
            setOnClickListener { copiarLink() }
        }, alto())
        claude.addView(botao("Gerar link novo (desliga o antigo)", FUNDO, CINZA, 14f).apply {
            setOnClickListener { gerarLinkNovo() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        claude.addView(rotulo("Reinstalou o app ou trocou a central? Cole aqui o link antigo do conector para trazer tudo de volta:"))
        val campoLink = EditText(this).apply {
            hint = "https://…/mcp/…"
            setHintTextColor(CINZA); setTextColor(TEXTO); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = fundo(FUNDO, 12, BORDA)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        claude.addView(campoLink, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        claude.addView(botao("♻  Recuperar com o link antigo", BOTAO, TEXTO, 15f).apply {
            setOnClickListener {
                val link = campoLink.text.toString()
                mostrar("Recuperando…", falar = false)
                Thread {
                    val r = try { Conector.recuperar(applicationContext, link) } catch (e: Exception) { "Falhou: ${e.message}" }
                    runOnUiThread {
                        mostrar(r, falar = false)
                        if (r.startsWith("Pronto")) reiniciarApp()
                    }
                }.start()
            }
        }, alto())

        // Atualizações
        val atual = cartao(direita, "Atualizações")
        atual.addView(rotulo("Quando sair versão nova, aparece um aviso e você aprova com um toque (ou pede ao Claude)."))
        atual.addView(botao("⟳  Procurar agora", BOTAO, TEXTO, 16f).apply {
            setOnClickListener {
                mostrar("Procurando…", falar = false)
                Thread {
                    Atualizador.verificar(applicationContext, instalar = false) { m -> runOnUiThread { mostrar(m, falar = false); atualizarEstados() } }
                }.start()
            }
        }, alto())
        atual.addView(interruptor("Instalar sozinho, sem perguntar", Atualizador.sozinho(this)) {
            Atualizador.mudarSozinho(this, it)
        })

        // Diagnóstico
        val diag = cartao(direita, "Diagnóstico")
        diag.addView(botao("👀  Ver o registro na tela", BOTAO, TEXTO, 16f).apply { setOnClickListener { verRegistro() } }, alto())
        diag.addView(botao("📋  Enviar diagnóstico", BOTAO, TEXTO, 16f).apply { setOnClickListener { enviarDiagnostico() } }, alto())

        // Ajuda
        val ajuda = cartao(direita, "O que você pode falar")
        ajuda.addView(TextView(this).apply {
            text = Comandos.AJUDA.joinToString("\n") { "•  $it" }
            setTextColor(CINZA); textSize = 15f
            setLineSpacing(0f, 1.25f)
        })

        atualizarEstados()
    }

    private fun atualizarEstados() {
        if (!::chipControle.isInitialized) return
        val ctlOk = Controle.ligado(this)
        pintarChip(chipControle, "Controle", if (ctlOk) "Ligado" else "Opcional", if (ctlOk) VERDE else CINZA)
        val (claudeTxt, claudeCor) = when {
            !Conector.configurado() -> "—" to CINZA
            Conector.online -> "Conectado" to VERDE
            else -> "Sem internet" to CINZA
        }
        pintarChip(chipClaude, "Claude", claudeTxt, claudeCor)
        val (livre, pct) = memoria()
        pintarChip(chipMemoria, "Memória", "$pct% livre", if (pct < 15) VERMELHO else if (pct < 25) OURO else VERDE)
        chipMemoria.tag = livre

        // O Controle é opcional e a central o desliga a cada partida: sem alerta (só o chip "Opcional").
        alertaReligar.visibility = View.GONE
        val n = Atualizador.disponivel(this)
        alertaAtualizar.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) textoAtualizar.text = "Atualização v$n disponível"

        try {
            val u = Viagens.ultima()
            val s = Viagens.resumo(7)
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR"))
            textoViagens.text = (if (u != null)
                "Última: %.1f km · %d min · máx %d km/h (%s)\n".format(
                    u.optDouble("km"), u.optLong("duracao_min"), u.optLong("vel_max_kmh"), fmt.format(java.util.Date(u.optLong("inicio"))))
            else "Ainda sem viagens registradas.\n") +
                "Últimos 7 dias: ${s.optInt("viagens")} viagens · ${s.optDouble("km_total")} km · ${s.optDouble("horas_dirigindo")} h" +
                (if (s.optBoolean("em_andamento")) "\n🟢 Viagem em andamento" else "")
        } catch (_: Exception) {
        }
        try {
            val odo = Diario.odometro(this)
            textoJarvis.text = "Limite de velocidade: ${Config.limite(this)} km/h (fale \"limite 100\")\n" +
                "Tempo: ${Clima.curto()}\n" +
                "Conversa com o Claude: " + (if (Config.jarvisConversa) "ligada" + (if (Config.jarvisAtlas) " (com a Atlas)" else "") else "falta a chave da API no servidor") + "\n" +
                (if (odo != null) "Odômetro estimado: $odo km\n" else "Odômetro: fale \"odômetro 123456\"\n") +
                Diario.manutencao(this).joinToString("\n") { (if (it.second) "⚠ " else "✓ ") + it.first }
        } catch (_: Exception) {
        }
        botaoMusica.text = "🎵  Música: " + Apps.nomeDe(this, Rotina.musica(this))
        botaoNavegacao.text = "🧭  GPS: " + Apps.nomeDe(this, Rotina.navegacao(this))
        textoVolume.text = "Volume ao ligar: ${Rotina.volume(this)}%"

        // Permissões que faltam
        permissoes.removeViews(1, (permissoes.childCount - 1).coerceAtLeast(0))
        var falta = false
        if (!packageManager.canRequestPackageInstalls()) {
            falta = true
            permissoes.addView(botao("Permitir instalar as atualizações", BOTAO, TEXTO, 16f).apply {
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            }, alto())
        }
        if (!Settings.canDrawOverlays(this)) {
            falta = true
            permissoes.addView(botao("Permitir abrir apps sozinho (1 vez só)", OURO, TINTA_OURO, 16f).apply {
                setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        mostrar("Ligue a chave \"Permitir sobreposição\" do Painel ASX e volte.", falar = false)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
            }, alto())
        }
        if (!Musica.podeVerMusica(this)) {
            falta = true
            permissoes.addView(botao("Mostrar o nome da música na tela do carro", BOTAO, TEXTO, 15f).apply {
                setOnClickListener {
                    try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) } catch (_: Exception) { }
                    mostrar("Ligue o \"Painel ASX\" na lista e volte.", falar = false)
                }
            }, alto())
        }
        if (!Settings.System.canWrite(this)) {
            falta = true
            permissoes.addView(botao("Modo noite: deixar ajustar o brilho", BOTAO, TEXTO, 15f).apply {
                setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
                }
            }, alto())
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            falta = true
            permissoes.addView(botao("Câmera (foto para o Claude, experimental)", BOTAO, TEXTO, 15f).apply {
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.CAMERA), 9) }
            }, alto())
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            falta = true
            permissoes.addView(botao("Permitir GPS (velocidade e viagens)", BOTAO, TEXTO, 16f).apply {
                setOnClickListener { pedirPermissoesSimples() }
            }, alto())
        }
        permissoes.visibility = if (falta) View.VISIBLE else View.GONE
    }

    private fun memoria(): Pair<Long, Int> {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val pct = if (mi.totalMem > 0) (mi.availMem * 100 / mi.totalMem).toInt() else 0
        return (mi.availMem / (1024 * 1024)) to pct
    }

    // ---------------------------------------------------------------- ações

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

    /** Reinicia o app (depois de recuperar o backup). */
    private fun reiniciarApp() {
        relogio.postDelayed({
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(this, 5, i, android.app.PendingIntent.FLAG_IMMUTABLE)
            (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
                .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 2500)
    }

    private fun copiarLink() {
        if (!Conector.configurado()) { mostrar("O conector ainda não está configurado nesta versão.", falar = false); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Conector Painel ASX", Conector.linkDoClaude(this)))
        AlertDialog.Builder(this)
            .setTitle("Link copiado ✅")
            .setMessage("No Claude (claude.ai): Configurações → Conectores → Adicionar conector personalizado → Nome: Painel ASX → cole o link.\n\nEsse link é como uma senha: não mande para ninguém.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun gerarLinkNovo() {
        AlertDialog.Builder(this)
            .setTitle("Gerar link novo?")
            .setMessage("O link antigo para de funcionar e você vai ter de colar o novo no Claude.")
            .setPositiveButton("Gerar") { _, _ -> Conector.gerarLinkNovo(this); mostrar("Link novo gerado. Copie e cole no Claude.", falar = false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun verRegistro() {
        val t = TextView(this).apply {
            text = Registro.ultimas(40).joinToString("\n").ifEmpty { "(nada anotado ainda)" }
            setTextColor(Color.BLACK); textSize = 14f
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("v${BuildConfig.VERSION_CODE} · Controle " + (if (Controle.ligado(this)) "LIGADO" else "DESLIGADO"))
            .setView(ScrollView(this).apply { addView(t) })
            .setPositiveButton("Fechar", null)
            .show()
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

    private fun escolherAppsDaBarra() {
        val todos = Apps.listar(this).filter { it.pacote != packageName }
        val atuais = Barra.apps(this)
        val marcados = BooleanArray(todos.size) { todos[it].pacote in atuais }
        AlertDialog.Builder(this)
            .setTitle("Apps da barra")
            .setMultiChoiceItems(todos.map { it.nome }.toTypedArray(), marcados) { _, i, v -> marcados[i] = v }
            .setPositiveButton("Salvar") { _, _ ->
                val escolhidos = todos.filterIndexed { i, _ -> marcados[i] }.map { it.pacote }
                // mantém a ordem antiga e põe os novos no fim
                val ordem = atuais.filter { it in escolhidos } + escolhidos.filter { it !in atuais }
                Barra.salvarApps(this, ordem)
                mostrar("Barra atualizada.", falar = false)
            }
            .setNegativeButton("Cancelar", null)
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

    // ---------------------------------------------------------------- peças visuais

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fundo(cor: Int, raio: Int, borda: Int? = null) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dp(raio).toFloat()
        if (borda != null) setStroke(dp(1), borda)
    }

    private fun coluna() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(28))
    }

    private fun rolar(v: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(v, ViewGroup.LayoutParams(-1, -2))
    }

    private fun botao(t: String, cor: Int, corTexto: Int, tam: Float) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(corTexto)
        textSize = tam
        typeface = Typeface.DEFAULT_BOLD
        background = fundo(cor, 16)
        gravity = Gravity.CENTER
        stateListAnimator = null
    }

    private fun alto() = LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) }

    private fun rotulo(t: String) = TextView(this).apply {
        text = t
        setTextColor(CINZA); textSize = 15f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun chip() = TextView(this).apply {
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = fundo(CARTAO, 14, BORDA)
    }

    private fun pesoChip(margem: Int) = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(margem) }

    private fun pintarChip(c: TextView, nome: String, valor: String, cor: Int) {
        val hexCor = String.format("#%06X", 0xFFFFFF and cor)
        val hexCinza = String.format("#%06X", 0xFFFFFF and CINZA)
        @Suppress("DEPRECATION")
        c.text = android.text.Html.fromHtml(
            "<font color='$hexCinza'><small>$nome</small></font><br><font color='$hexCor'>● </font><font color='#EEF2F7'>$valor</font>"
        )
    }

    /** Cartão com título; devolve o miolo onde se colocam as coisas. */
    private fun cartao(pai: LinearLayout, titulo: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fundo(CARTAO, 18, BORDA)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        c.addView(TextView(this).apply {
            text = titulo
            setTextColor(TEXTO); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        pai.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return c
    }

    private fun interruptor(t: String, valor: Boolean, mudou: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        setTextColor(TEXTO); textSize = 16f
        isChecked = valor
        setPadding(0, dp(10), 0, dp(6))
        setOnCheckedChangeListener { _, v -> mudou(v) }
    }

    private fun alerta(cor: Int, titulo: String, texto: String, acao: String, tituloView: TextView? = null, clique: () -> Unit): View {
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fundo(Color.argb(40, Color.red(cor), Color.green(cor), Color.blue(cor)), 16, cor)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            visibility = View.GONE
        }
        val textos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = tituloView ?: TextView(this)
        t.text = titulo
        t.setTextColor(TEXTO); t.textSize = 17f; t.typeface = Typeface.DEFAULT_BOLD
        textos.addView(t)
        textos.addView(TextView(this).apply { text = texto; setTextColor(CINZA); textSize = 14f })
        caixa.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        caixa.addView(botao(acao, cor, if (cor == OURO) TINTA_OURO else Color.WHITE, 16f).apply {
            setOnClickListener { clique() }
        }, LinearLayout.LayoutParams(dp(130), dp(54)).apply { leftMargin = dp(10) })
        val envolve = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        envolve.addView(caixa, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        // a visibilidade e controlada no "envolve"
        caixa.visibility = View.VISIBLE
        envolve.visibility = View.GONE
        return envolve
    }
}
