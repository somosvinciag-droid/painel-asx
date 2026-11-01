package br.com.leonardo.painelasx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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
 * Tela do carro "F3 · Gran Turismo" (v20), escolhida pelo Leonardo:
 * faixa verde-branco-vermelho no topo; à esquerda (2/3) velocidade grande e fina, barra até o limite,
 * consumo (km/l) e custo por km, o Jarvis com o botão FALAR e os destinos; à direita (1/3) a música
 * com capa, tempo e botões grandes. Desenhada para 1280×720 e escalada para a tela da central.
 */
class TelaCarro : Activity() {
    private val FUNDO = Color.parseColor("#0D0F0E")
    private val PAINEL = Color.parseColor("#141615")
    private val LINHA = Color.parseColor("#262927")
    private val TEXTO = Color.parseColor("#EDEDE8")
    private val CINZA = Color.parseColor("#8C8F8B")
    private val VERDE = Color.parseColor("#009246")
    private val BRANCO_IT = Color.parseColor("#F1F2F1")
    private val VERMELHO = Color.parseColor("#CE2B37")

    private val PEDIDO_VOZ = 7
    private val relogio = Handler(Looper.getMainLooper())
    private var tique = 0
    private var s = 1f

    private lateinit var fino: Typeface
    private lateinit var normal: Typeface
    private lateinit var forte: Typeface

    private lateinit var hora: TextView
    private lateinit var clima: TextView
    private lateinit var celularBateria: TextView
    private lateinit var velocidade: TextView
    private lateinit var barra: BarraVelocidade
    private lateinit var limite: TextView
    private lateinit var consumo: TextView
    private lateinit var consumoNota: TextView
    private lateinit var custo: TextView
    private lateinit var custoNota: TextView
    private lateinit var estadoJarvis: TextView
    private lateinit var fala: TextView
    private lateinit var detalhe: TextView
    private lateinit var fonteMusica: TextView
    private lateinit var capa: Capa
    private lateinit var musica: TextView
    private lateinit var artista: TextView
    private lateinit var progresso: Progresso
    private lateinit var playIcone: Icone
    private lateinit var sistema: TextView
    private lateinit var anjo: TextView

    private val atualizar = object : Runnable {
        override fun run() {
            try { pintar() } catch (_: Exception) { }
            tique++
            relogio.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val t0 = android.os.SystemClock.elapsedRealtime()
        Voz.iniciar(this)
        ServicoFixo.iniciar(this)
        carregarFontes()
        montar()
        tratar(intent)
        Registro.anotar("inicio: tela do carro ${android.os.SystemClock.elapsedRealtime() - t0} ms")
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

    // ---------------------------------------------------------------- medidas e peças

    /** px do desenho (1280×720) → px da tela da central. */
    private fun px(v: Float) = Math.round(v * s)
    private fun px(v: Int) = px(v.toFloat())

    private fun carregarFontes() {
        val base = try { Typeface.createFromAsset(assets, "fontes/BigShouldersDisplay.ttf") } catch (_: Exception) { null }
        fun peso(w: Int): Typeface = try {
            Typeface.Builder(assets, "fontes/BigShouldersDisplay.ttf").setFontVariationSettings("'wght' $w").build() ?: base ?: Typeface.DEFAULT
        } catch (_: Exception) { base ?: Typeface.DEFAULT }
        fino = peso(300); normal = peso(400); forte = peso(600)
    }

    private fun texto(t: String, tam: Float, cor: Int, tf: Typeface = Typeface.DEFAULT, espaco: Float = 0f) = TextView(this).apply {
        text = t; setTextColor(cor); typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_PX, tam * s)
        letterSpacing = espaco
        includeFontPadding = false
    }

    private fun rotulo(t: String) = texto(t, 13f, CINZA, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 0.3f)

    private fun moldura(cor: Int, borda: Int? = null, raio: Float = 2f) = GradientDrawable().apply {
        setColor(cor); cornerRadius = raio * s
        if (borda != null) setStroke(maxOf(1, px(1)), borda)
    }

    private fun lp(w: Int, h: Int, peso: Float = 0f) = LinearLayout.LayoutParams(w, h, peso)
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun montar() {
        val dm = resources.displayMetrics
        val barraLateral = if (Barra.ligada(this)) Math.round(88 * dm.density) else 0
        s = minOf((dm.widthPixels - barraLateral) / 1280f, dm.heightPixels / 720f)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FUNDO)
            setPadding(barraLateral, 0, 0, 0)
        }
        setContentView(raiz)

        // faixa verde-branco-vermelho
        val faixa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in listOf(VERDE, BRANCO_IT, VERMELHO)) faixa.addView(View(this).apply { setBackgroundColor(c) }, lp(0, MATCH, 1f))
        raiz.addView(faixa, lp(MATCH, maxOf(3, px(6))))

        val corpo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        raiz.addView(corpo, lp(MATCH, 0, 1f))

        // ================= esquerda (2/3)
        val esq = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(40), 0, px(40), px(24)) }
        corpo.addView(esq, lp(0, MATCH, 2f))

        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topo.addView(texto("ASX GT", 22f, TEXTO, forte, 0.5f).apply {
            setOnLongClickListener { startActivity(Intent(this@TelaCarro, MainActivity::class.java)); true }
        }, lp(0, WRAP, 1f))
        // Bateria do celular (só aparece com o app "Painel ASX Celular" ligado).
        celularBateria = texto("", 18f, CINZA, normal, 0.1f).apply { visibility = View.GONE }
        topo.addView(celularBateria, lp(WRAP, WRAP).apply { rightMargin = px(18) })
        clima = texto("—", 20f, CINZA, normal)
        topo.addView(clima, lp(WRAP, WRAP).apply { rightMargin = px(18) })
        hora = texto("--:--", 28f, TEXTO, normal)
        topo.addView(hora)
        topo.addView(texto("⚙", 22f, CINZA).apply {
            setPadding(px(22), px(10), 0, px(10))
            setOnClickListener { startActivity(Intent(this@TelaCarro, MainActivity::class.java)) }
            contentDescription = "Configurações"
        })
        esq.addView(topo, lp(MATCH, px(60)))

        val meio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        esq.addView(meio, lp(MATCH, 0, 1f))

        // velocidade + barra + consumo
        val colVel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val linhaVel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        velocidade = texto("0", 260f, TEXTO, fino, -0.02f)
        linhaVel.addView(velocidade)
        linhaVel.addView(texto("km/h", 30f, CINZA, normal).apply { setPadding(px(12), 0, 0, px(22)) })
        colVel.addView(linhaVel)
        val linhaBarra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barra = BarraVelocidade(this)
        linhaBarra.addView(barra, lp(0, px(14), 1f))
        limite = texto("LIMITE 110", 15f, CINZA, Typeface.DEFAULT, 0.2f).apply {
            setPadding(px(14), px(8), 0, px(8))
            setOnClickListener { Voz.falar("Para mudar, fale: limite 100.") }
        }
        linhaBarra.addView(limite)
        colVel.addView(linhaBarra, lp(MATCH, WRAP).apply { topMargin = px(18) })

        val dados = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun dado(r: String): Triple<LinearLayout, TextView, TextView> {
            val d = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            d.addView(rotulo(r))
            val v = texto("—", 52f, TEXTO, normal)
            d.addView(v, lp(WRAP, WRAP).apply { topMargin = px(4) })
            val n = texto("", 14f, CINZA)
            d.addView(n, lp(WRAP, WRAP).apply { topMargin = px(4) })
            return Triple(d, v, n)
        }
        val (dC, vC, nC) = dado("CONSUMO")
        consumo = vC; consumoNota = nC
        dados.addView(dC)
        dados.addView(View(this).apply { setBackgroundColor(LINHA) }, lp(maxOf(1, px(1)), MATCH).apply { leftMargin = px(28); rightMargin = px(28) })
        val (dK, vK, nK) = dado("CUSTO")
        custo = vK; custoNota = nK
        dados.addView(dK)
        dados.setOnClickListener { executar("consumo") }
        colVel.addView(dados, lp(WRAP, WRAP).apply { topMargin = px(26) })
        meio.addView(colVel, lp(0, WRAP, 1f))

        // Jarvis
        meio.addView(View(this).apply { setBackgroundColor(LINHA) }, lp(maxOf(1, px(1)), px(280)).apply { leftMargin = px(32) })
        val colJ = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(28), 0, 0, 0) }
        estadoJarvis = rotulo("JARVIS")
        colJ.addView(estadoJarvis)
        fala = texto("Às suas ordens.", 40f, TEXTO, normal).apply { maxLines = 3; setLineSpacing(0f, 1.05f) }
        colJ.addView(fala, lp(MATCH, WRAP).apply { topMargin = px(12) })
        detalhe = texto("", 17f, CINZA).apply { maxLines = 3; setLineSpacing(0f, 1.3f) }
        colJ.addView(detalhe, lp(MATCH, WRAP).apply { topMargin = px(12) })
        val falar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = moldura(Color.TRANSPARENT, TEXTO)
            isClickable = true
            setOnClickListener { ouvir() }
            contentDescription = "Falar com o Jarvis"
        }
        falar.addView(Icone(this, Icone.MIC, VERMELHO), lp(px(28), px(28)))
        falar.addView(texto("FALAR", 22f, TEXTO, Typeface.create(Typeface.DEFAULT, Typeface.BOLD), 0.24f), lp(WRAP, WRAP).apply { leftMargin = px(12) })
        colJ.addView(falar, lp(MATCH, px(86)).apply { topMargin = px(20) })
        meio.addView(colJ, lp(px(300), WRAP))

        // destinos
        val destinos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        esq.addView(View(this).apply { setBackgroundColor(LINHA) }, lp(MATCH, maxOf(1, px(1))))
        val itens = listOf(
            Triple("Casa", "Caxambu", "rota para casa"),
            Triple("Mineirita", "Poços de Caldas", "rota para mineirita"),
            Triple("Família", "Pouso Alegre", "rota para familia"),
            Triple("Apps", "todos", "")
        )
        itens.forEachIndexed { i, (nome, sub, cmd) ->
            if (i > 0) destinos.addView(View(this).apply { setBackgroundColor(LINHA) }, lp(maxOf(1, px(1)), MATCH))
            val d = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(if (i == 0) px(4) else px(20), 0, px(8), 0)
                isClickable = true
                setOnClickListener {
                    // Com o Android Auto ligado pelo cabo, "Apps" abre a tela dele (os apps do celular).
                    if (cmd.isEmpty()) { if (!AndroidAuto.abrirTela(this@TelaCarro)) TodosApps.abrir(this@TelaCarro) }
                    else executar(cmd)
                }
            }
            d.addView(texto(nome, 28f, TEXTO, forte))
            d.addView(texto(sub, 14f, CINZA), lp(WRAP, WRAP).apply { topMargin = px(4) })
            destinos.addView(d, lp(0, MATCH, 1f))
        }
        esq.addView(destinos, lp(MATCH, px(96)))

        // ================= direita (1/3): música
        val dir = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PAINEL); setPadding(px(30), px(30), px(30), px(24)) }
        corpo.addView(dir, lp(0, MATCH, 1f))
        fonteMusica = rotulo("MÚSICA")
        dir.addView(fonteMusica)
        val caixaCapa = FrameLayout(this)
        capa = Capa(this)
        caixaCapa.addView(capa, FrameLayout.LayoutParams(px(310), px(310)))
        // botões de configuração única (somem quando já estão feitos)
        val avisos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        anjo = texto("🛡  Ligar o Jarvis voltar sozinho", 16f, TEXTO).apply {
            background = moldura(Color.parseColor("#2A0B0E"), VERMELHO, 6f); setPadding(px(14), px(12), px(14), px(12))
            setOnClickListener { ligarAnjo() }
        }
        sistema = texto("★  Fazer do Painel ASX o sistema do carro", 16f, TEXTO).apply {
            background = moldura(Color.parseColor("#2A0B0E"), VERMELHO, 6f); setPadding(px(14), px(12), px(14), px(12))
            setOnClickListener { virarTelaInicial() }
        }
        avisos.addView(anjo)
        avisos.addView(sistema, lp(WRAP, WRAP).apply { topMargin = px(8) })
        caixaCapa.addView(avisos, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12); leftMargin = px(12) })
        dir.addView(caixaCapa, lp(MATCH, px(310)).apply { topMargin = px(14) })
        musica = texto("Nada tocando", 38f, TEXTO, forte).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        dir.addView(musica, lp(MATCH, WRAP).apply { topMargin = px(16) })
        artista = texto("—", 17f, CINZA).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        dir.addView(artista, lp(MATCH, WRAP).apply { topMargin = px(6) })
        dir.addView(View(this), lp(MATCH, 0, 1f))
        progresso = Progresso(this)
        dir.addView(progresso, lp(MATCH, maxOf(2, px(2))))
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun botao(tipo: Int, cheio: Boolean, acao: () -> Unit): Icone {
            val f = FrameLayout(this).apply {
                background = if (cheio) moldura(TEXTO) else moldura(Color.TRANSPARENT, Color.parseColor("#2C2F2D"))
                isClickable = true
                setOnClickListener { acao() }
            }
            val ic = Icone(this, tipo, if (cheio) FUNDO else TEXTO)
            f.addView(ic, FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER))
            ctrl.addView(f, lp(0, px(80), 1f).apply { if (ctrl.childCount > 0) leftMargin = px(8) })
            return ic
        }
        // Música tocando na própria central (Spotify Connect): a tecla de mídia vai direto para ela (na hora).
        // Senão: Android Auto pelo cabo (teclas pelo canal de entrada); senão o app do celular; senão a tecla de sempre.
        botao(Icone.ANTERIOR, false) {
            if (Musica.centralTocando(this) || !(AndroidAuto.controle("anterior") || Celular.controle("anterior")))
                Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        playIcone = botao(Icone.PAUSA, true) {
            if (!Musica.pararRadio() && (Musica.centralTocando(this) || !(AndroidAuto.controle("alternar") || Celular.controle("alternar"))))
                Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        botao(Icone.PROXIMA, false) {
            if (Musica.centralTocando(this) || !(AndroidAuto.controle("proxima") || Celular.controle("proxima")))
                Rotina.teclaMidia(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        dir.addView(ctrl, lp(MATCH, px(80)).apply { topMargin = px(14) })

        anjo.visibility = if (Musica.podeVerMusica(this)) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- atualização (1 vez por segundo)

    private val BR = Locale("pt", "BR")
    private val formatoHora = SimpleDateFormat("HH:mm", BR)

    /**
     * Só troca o texto se mudou. Trocar o texto de um TextView "do tamanho do conteúdo" (hora, velocidade...)
     * pede para medir e arrumar a tela inteira de novo; antes isso acontecia todo segundo, mesmo sem mudar nada.
     */
    private fun TextView.por(t: CharSequence) { if (!android.text.TextUtils.equals(text, t)) text = t }

    private fun pintar() {
        hora.por(formatoHora.format(Date()))
        val kmh = Carro.velocidadeKmh.toInt()
        val lim = Config.limite(this)
        velocidade.por(kmh.toString())
        velocidade.setTextColor(if (kmh > lim) VERMELHO else TEXTO)
        barra.mostrar(kmh, lim)
        limite.por("LIMITE $lim")
        if (Voz.ultimaFala.isNotBlank()) fala.por(Voz.ultimaFala)

        if (tique % 2 == 0) {
            val t = Musica.tocando(this)
            if (t == null) {
                fonteMusica.por("MÚSICA")
                musica.por(if (Voz.tocandoMusica()) "Música tocando" else "Nada tocando")
                artista.por(if (!Musica.podeVerMusica(this)) "Ligue o \"voltar sozinho\" para ver a música" else "—")
                capa.mostrar(null); progresso.mostrar(0f)
            } else {
                fonteMusica.por(t.fonte)
                musica.por(t.titulo)
                artista.por(t.artista.ifBlank { "—" })
                capa.mostrar(t.capa)
                progresso.mostrar(if (t.duracaoMs > 0) (t.posicaoMs.toFloat() / t.duracaoMs).coerceIn(0f, 1f) else 0f)
                playIcone.tipo = if (t.tocando) Icone.PAUSA else Icone.PLAY
            }
            // Próxima curva do Android Auto: discreta, no rótulo pequeno embaixo do Jarvis (no lugar dos km do dia).
            val nav = AndroidAuto.navegacaoTexto()
            if (nav.isNotEmpty()) detalhe.por(nav)
        }
        if (tique % 5 == 0) {
            val cel = Celular.bateriaCurta()
            celularBateria.por(cel)
            celularBateria.visibility = if (cel.isEmpty()) View.GONE else View.VISIBLE
            clima.por(Clima.curto())
            val kml = Diario.consumoMedio(this)
            consumo.por(kml?.let { "%.1f".format(BR, it) + " km/l" } ?: "— km/l")
            consumoNota.por(if (kml != null) "média do último tanque" else "fale \"abasteci 40 litros 230 reais\"")
            val ck = Diario.custoPorKm(this)
            custo.por(ck?.let { "%.2f".format(BR, it.first) + " R$/km" } ?: "— R$/km")
            custoNota.por(ck?.let { "combustível a R$ " + "%.2f".format(BR, it.second) } ?: "depois do 2º abastecimento")
            val d = Viagens.resumo(1, comLista = false)
            val km = "%.1f".format(BR, d.optDouble("km_total", 0.0))
            val manut = try { Diario.manutencao(this).firstOrNull { it.second }?.first } catch (_: Exception) { null }
            if (AndroidAuto.navegacaoTexto().isEmpty()) detalhe.por("Hoje $km km em ${d.optInt("viagens")} viagens." + (manut?.let { " ${it.replaceFirstChar { c -> c.uppercase() }}." } ?: ""))
        }
        if (tique % 30 == 0) Rede.emSegundoPlano("tela-inicial") {
            val ja = Nwd.somosTelaInicial(this)
            Rede.naTela {
                sistema.visibility = if (ja) View.GONE else View.VISIBLE
                anjo.visibility = if (Musica.podeVerMusica(this)) View.GONE else View.VISIBLE
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
        if (Ouvido.ouvindo || FontesDeVoz.algumaOuvindo) return
        // O FALAR é sempre do Jarvis. Fonte de fora (app do celular) ouve e manda o texto; erro ou 8 s sem resposta:
        // ouvido da central. Com o Android Auto pelo cabo: ouvido da central (ver FonteDeVoz).
        val fonte = FontesDeVoz.escolher() ?: run { ouvirNaCentral(); return }
        val pediu = fonte.ouvir({ etapa ->
            estadoJarvis.text = "JARVIS · $etapa"
            estadoJarvis.setTextColor(VERMELHO)
        }) { texto, _ ->
            estadoJarvis.setTextColor(CINZA)
            if (texto != null) {
                estadoJarvis.text = "OUVI: " + texto.uppercase().take(42)
                relogio.postDelayed({ if (!Ouvido.ouvindo && !FontesDeVoz.algumaOuvindo) estadoJarvis.text = "JARVIS" }, 8000)
                executar(texto)
            } else {
                estadoJarvis.text = "JARVIS"
                ouvirNaCentral()
            }
        }
        if (!pediu) ouvirNaCentral()
    }

    /** Ouvido da própria central (como antes do celular). */
    private fun ouvirNaCentral() {
        if (Ouvido.ouvindo) return
        // Ouvido próprio (sem internet). Se ainda não baixou e a central tiver o app Google, usa o do Google.
        if (!Ouvido.baixado(this) && reconhecedorDoAndroid()) { ouvirPeloAndroid(); return }
        if (!Ouvido.temPermissao(this)) {
            Voz.falar("Toque em Permitir para eu usar o microfone, senhor.")
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), Ouvido.PEDIDO_MIC)
            return
        }
        Ouvido.ouvir(this, { etapa ->
            estadoJarvis.text = "JARVIS · $etapa"
            estadoJarvis.setTextColor(VERMELHO)
        }) { texto, problema ->
            estadoJarvis.setTextColor(CINZA)
            if (texto != null) {
                estadoJarvis.text = "OUVI: " + texto.uppercase().take(42)
                relogio.postDelayed({ if (!Ouvido.ouvindo) estadoJarvis.text = "JARVIS" }, 8000)
                executar(texto)
            } else {
                estadoJarvis.text = "JARVIS"
                val p = problema ?: "Não ouvi nada, senhor."
                fala.text = p
                Voz.falar(p)
            }
        }
    }

    override fun onRequestPermissionsResult(codigo: Int, perms: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, perms, resultados)
        if (codigo != Ouvido.PEDIDO_MIC) return
        if (resultados.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Registro.anotar("ouvido: microfone liberado")
            relogio.postDelayed({ ouvirNaCentral() }, 400)
        } else Voz.falar("Sem o microfone eu não consigo ouvir, senhor.")
    }

    private fun reconhecedorDoAndroid() =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(packageManager) != null

    private fun ouvirPeloAndroid() {
        estadoJarvis.text = "JARVIS · OUVINDO"
        estadoJarvis.setTextColor(VERMELHO)
        relogio.postDelayed({ estadoJarvis.text = "JARVIS"; estadoJarvis.setTextColor(CINZA) }, 6000)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Às suas ordens")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PEDIDO_VOZ)
        } catch (e: Exception) {
            Voz.falar(Ouvido.explicacao())
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
        fala.text = r
        Voz.falar(r)
    }

    // ---------------------------------------------------------------- peças desenhadas

    /** Linha fina com a velocidade (branca) e a marca vermelha do limite. Escala 0–180 km/h. */
    private inner class BarraVelocidade(c: Context) : View(c) {
        private var v = 0; private var lim = 110
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun mostrar(vel: Int, limite: Int) { if (vel != v || limite != lim) { v = vel; lim = limite; invalidate() } }
        override fun onDraw(cv: Canvas) {
            val meio = height / 2f; val esp = maxOf(2f, 2 * s)
            p.color = LINHA; cv.drawRect(0f, meio - esp / 2, width.toFloat(), meio + esp / 2, p)
            p.color = if (v > lim) VERMELHO else TEXTO
            cv.drawRect(0f, meio - esp / 2, width * (v / 180f).coerceIn(0f, 1f), meio + esp / 2, p)
            val x = width * (lim / 180f).coerceIn(0f, 1f)
            p.color = VERMELHO; cv.drawRect(x - esp / 2, 0f, x + esp / 2, height.toFloat(), p)
        }
    }

    private inner class Progresso(c: Context) : View(c) {
        private var f = 0f
        private val p = Paint()
        fun mostrar(fr: Float) { if (fr != f) { f = fr; invalidate() } }
        override fun onDraw(cv: Canvas) {
            p.color = LINHA; cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            p.color = TEXTO; cv.drawRect(0f, 0f, width * f, height.toFloat(), p)
        }
    }

    /** Capa do álbum; sem capa, três faixas (grafite, grafite claro, vermelho). */
    private inner class Capa(c: Context) : View(c) {
        private var bmp: Bitmap? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun mostrar(b: Bitmap?) { if (b !== bmp) { bmp = b; invalidate() } }
        override fun onDraw(cv: Canvas) {
            val b = bmp
            if (b != null && !b.isRecycled) {
                val lado = minOf(b.width, b.height)
                val src = Rect((b.width - lado) / 2, (b.height - lado) / 2, (b.width + lado) / 2, (b.height + lado) / 2)
                cv.drawBitmap(b, src, RectF(0f, 0f, width.toFloat(), height.toFloat()), p)
            } else {
                val t = width / 3f
                for ((i, c) in listOf(Color.parseColor("#1D201E"), Color.parseColor("#2A2D2B"), VERMELHO).withIndex()) {
                    p.color = c; cv.drawRect(i * t, 0f, (i + 1) * t, height.toFloat(), p)
                }
            }
        }
    }

    /** Ícones de traço simples (microfone, anterior, play, pausa, próxima). */
    private class Icone(c: Context, tipoInicial: Int, private val cor: Int) : View(c) {
        companion object { const val MIC = 0; const val ANTERIOR = 1; const val PLAY = 2; const val PAUSA = 3; const val PROXIMA = 4 }
        var tipo = tipoInicial
            set(v) { if (v != field) { field = v; invalidate() } }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        override fun onDraw(cv: Canvas) {
            val u = width / 24f
            p.color = cor; p.strokeWidth = 2f * u
            val path = Path()
            when (tipo) {
                MIC -> {
                    cv.drawRoundRect(RectF(9 * u, 3 * u, 15 * u, 14 * u), 3 * u, 3 * u, p)
                    cv.drawArc(RectF(5 * u, 4 * u, 19 * u, 18 * u), 0f, 180f, false, p)
                    cv.drawLine(12 * u, 18 * u, 12 * u, 21 * u, p)
                }
                ANTERIOR -> { cv.drawLine(6 * u, 5 * u, 6 * u, 19 * u, p); path.moveTo(19 * u, 5 * u); path.lineTo(9 * u, 12 * u); path.lineTo(19 * u, 19 * u); path.close(); cv.drawPath(path, p) }
                PROXIMA -> { cv.drawLine(18 * u, 5 * u, 18 * u, 19 * u, p); path.moveTo(5 * u, 5 * u); path.lineTo(15 * u, 12 * u); path.lineTo(5 * u, 19 * u); path.close(); cv.drawPath(path, p) }
                PAUSA -> { p.strokeWidth = 2.6f * u; cv.drawLine(8 * u, 5 * u, 8 * u, 19 * u, p); cv.drawLine(16 * u, 5 * u, 16 * u, 19 * u, p) }
                PLAY -> { path.moveTo(7 * u, 4 * u); path.lineTo(19 * u, 12 * u); path.lineTo(7 * u, 20 * u); path.close(); cv.drawPath(path, p) }
            }
        }
    }
}
