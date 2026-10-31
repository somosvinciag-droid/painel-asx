package br.com.leonardo.painelasx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject

/**
 * O que a central sabe do celular (app "Painel ASX Celular"): música com capa, bateria, ligação e sinal.
 *
 * PRIVACIDADE: o texto das mensagens NUNCA entra no Registro, no conector, no backup nem no Jarvis/Claude.
 * Ele só é falado no carro, e só se o Leonardo ligar "Ler mensagens" (padrão desligado). Nada fica guardado.
 */
object Celular {
    @Volatile var ligado = false
        private set
    @Volatile var transporte = ""
        private set
    @Volatile var modelo = ""
        private set
    @Volatile var android = ""
        private set
    @Volatile var versaoApp = 0L
        private set
    @Volatile var pareando = false
        private set
    @Volatile var microfone = false
        private set

    @Volatile var bateria = -1
        private set
    @Volatile var carregando = false
        private set

    @Volatile var operadora = ""
        private set
    @Volatile var nivelSinal = -1
        private set
    @Volatile var rede = ""
        private set

    /** "tocando", "atendida" ou "fim". Quem liga fica só na memória. */
    @Volatile var ligacao = "fim"
        private set

    class Mus(
        val titulo: String, val artista: String, val app: String, val duracaoMs: Long,
        val posicaoMs: Long, val tocando: Boolean, val capaId: String, val recebidoEm: Long
    )

    @Volatile private var musica: Mus? = null
    @Volatile private var capa: Bitmap? = null
    @Volatile private var capaId = ""

    // ------------------------------------------------------------------ ajustes

    fun lerMensagens(ctx: Context) = CaboCelular.prefs(ctx).getBoolean("ler_mensagens", false)

    fun mudarLerMensagens(ctx: Context, v: Boolean) {
        CaboCelular.prefs(ctx).edit().putBoolean("ler_mensagens", v).apply()
        CaboCelular.mandar(JSONObject().put("t", "ajustes").put("mensagens", v))
        Registro.anotar("celular: ler mensagens " + if (v) "ligado" else "desligado")
        if (!v) synchronized(guardadas) { guardadas.clear() }
    }

    // ------------------------------------------------------------------ mensagens que chegam

    @Volatile private var app: Context? = null

    fun receber(ctx: Context, cx: Conexao, o: JSONObject) {
        app = ctx.applicationContext
        when (o.optString("t")) {
            "ola" -> {
                cx.respondeu = true
                if (cx.canal.tipo == "cabo") CaboUsb.caboRespondeu()
                ligado = true
                pareando = false
                transporte = cx.canal.tipo
                modelo = o.optString("modelo").take(40)
                android = o.optString("android").take(10)
                versaoApp = o.optLong("app")
                microfone = o.optBoolean("mic")
                Registro.anotar("celular: ligado pela ${if (transporte == "cabo") "cabo USB" else "rede Bluetooth"} ($modelo, Android $android, app c$versaoApp)")
            }
            "pareando" -> {
                cx.respondeu = true
                if (!pareando) {
                    pareando = true
                    Registro.anotar("celular: esperando o Leonardo tocar em Permitir no celular (código ${o.optString("codigo").take(8)})")
                    Rede.naTela { Voz.falar("Toque em Permitir no celular para ligar o painel, senhor.", Voz.Tipo.INFO) }
                }
            }
            "recusado" -> {
                cx.respondeu = true
                pareando = false
                Registro.anotar("celular: o celular recusou esta central (tento de novo em 10 min)")
                CaboCelular.pausar(10 * 60_000L)
            }
            "pong" -> { }
            "bateria" -> {
                bateria = o.optInt("nivel", -1).coerceIn(-1, 100)
                carregando = o.optBoolean("carregando")
            }
            "musica" -> {
                musica = if (!o.optBoolean("ativa")) null else Mus(
                    o.optString("titulo").take(200), o.optString("artista").take(200), o.optString("app").take(40),
                    o.optLong("duracao_ms"), o.optLong("posicao_ms"), o.optBoolean("tocando"), o.optString("capa"),
                    SystemClock.elapsedRealtime()
                )
            }
            "capa" -> {
                val id = o.optString("id")
                if (id.isNotEmpty() && id != capaId) {
                    val b = try {
                        val bytes = Base64.decode(o.optString("jpg"), Base64.DEFAULT)
                        // Confere o tamanho antes (256 KB de JPEG podem dizer 20000x20000 px = falta de memória).
                        val dim = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dim)
                        if (dim.outWidth in 1..1024 && dim.outHeight in 1..1024) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        else null
                    } catch (_: Exception) { null }
                    if (b != null) { capa = b; capaId = id }
                }
            }
            "sinal" -> {
                operadora = o.optString("operadora").take(30)
                nivelSinal = o.optInt("nivel", -1)
                rede = o.optString("rede").take(5)
            }
            "ligacao" -> mudouLigacao(o.optString("estado"))
            "aviso" -> aviso(ctx, o)
            "ouvindo" -> ouvindoAgora()
            "ouvi" -> ouviu(ctx, o)
        }
    }

    fun caiu(motivo: String) {
        val estava = ligado
        ligado = false
        pareando = false
        musica = null
        ligacao = "fim"
        bateria = -1
        // Mensagens guardadas durante uma ligação não ficam para a "próxima" ligação (horas depois).
        synchronized(guardadas) { guardadas.clear() }
        pedido?.let { p -> Rede.naTela { terminar(p, null, "celular caiu") } }
        if (estava) Registro.anotar("celular: desligou ($motivo)")
    }

    // ------------------------------------------------------------------ música

    fun temMusica() = ligado && musica != null

    /** Música do celular no formato da tela do carro (null se não houver). */
    fun tocando(): Musica.Tocando? {
        if (!ligado) return null
        val m = musica ?: return null
        if (m.titulo.isBlank()) return null
        var pos = m.posicaoMs
        if (m.tocando) pos += SystemClock.elapsedRealtime() - m.recebidoEm
        if (m.duracaoMs > 0) pos = pos.coerceAtMost(m.duracaoMs)
        val c = if (m.capaId.isNotEmpty() && m.capaId == capaId) capa else null
        return Musica.Tocando(m.titulo, m.artista, c, pos, m.duracaoMs, m.tocando, m.app.uppercase().ifBlank { "MÚSICA" } + " · CELULAR")
    }

    fun tituloAgora(): String? {
        val m = musica ?: return null
        if (!ligado || m.titulo.isBlank()) return null
        return if (m.artista.isBlank()) m.titulo else "${m.titulo} — ${m.artista}"
    }

    /** Botões da tela do carro: anterior, alternar, proxima. Devolve false se não deu (a tela usa a tecla). */
    fun controle(acao: String): Boolean {
        if (!temMusica()) return false
        return CaboCelular.mandar(JSONObject().put("t", "controle").put("acao", acao))
    }

    // ------------------------------------------------------------------ ouvir pelo celular

    private class Pedido(val etapa: (String) -> Unit, val fim: (String?, String?) -> Unit) {
        @Volatile var ouvindo = false
        var foco: AudioFocusRequest? = null
    }

    @Volatile private var pedido: Pedido? = null
    val ouvindo get() = pedido != null

    /**
     * FALAR com o celular ligado: o celular ouve (Google, pt-BR) e manda o texto.
     * [fim](texto, null) = entendeu (já passou pelo Corretor); [fim](null, motivo) = não deu: quem chamou usa o
     * ouvido da central. Devolve false se o celular nem pôde ser chamado. [etapa] e [fim] rodam na linha principal.
     */
    fun ouvir(etapa: (String) -> Unit, fim: (String?, String?) -> Unit): Boolean {
        if (!ligado || pareando || pedido != null) return false
        val p = Pedido(etapa, fim)
        pedido = p
        if (!CaboCelular.mandar(JSONObject().put("t", "ouvir"))) { pedido = null; return false }
        // Pausa/abaixa a música da central enquanto o celular ouve (igual ao ouvido próprio): o microfone do celular
        // ouve melhor e a voz não briga com o som. Volta sozinha quando acaba (texto, erro ou desistência).
        p.foco = pegarFoco()
        etapa("CHAMANDO O CELULAR")
        // 8 s sem o celular começar a ouvir = usa o ouvido da central. Ouvindo, espera até 20 s no total.
        Rede.principal.postDelayed({ if (pedido === p && !p.ouvindo) desistir(p, "o celular não respondeu em 8 s") }, 8_000)
        Rede.principal.postDelayed({ if (pedido === p) desistir(p, "o celular demorou demais") }, 20_000)
        return true
    }

    private fun desistir(p: Pedido, motivo: String) {
        CaboCelular.mandar(JSONObject().put("t", "parar"))
        terminar(p, null, motivo)
    }

    private fun terminar(p: Pedido, texto: String?, problema: String?) {
        if (pedido !== p) return
        pedido = null
        soltarFoco(p.foco)
        try { p.fim(texto, problema) } catch (e: Exception) { Registro.anotar("ERRO celular ouvir: ${e.message}") }
    }

    private fun ouvindoAgora() {
        val p = pedido ?: return
        if (p.ouvindo) return
        p.ouvindo = true
        bipe()   // o mesmo sinal do ouvido próprio: "pode falar"
        Rede.naTela { if (pedido === p) p.etapa("OUVINDO (CELULAR)") }
    }

    private fun pegarFoco(): AudioFocusRequest? {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return try {
            val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            am.requestAudioFocus(f)
            f
        } catch (_: Exception) { null }
    }

    private fun soltarFoco(f: AudioFocusRequest?) {
        f ?: return
        try { (app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(f) } catch (_: Exception) { }
    }

    private fun bipe() {
        Thread({
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                Thread.sleep(220)
                tg.release()
            } catch (_: Exception) { }
        }, "celular-bipe").start()
    }

    private fun ouviu(ctx: Context, o: JSONObject) {
        val p = pedido ?: return
        val bruto = o.optString("texto").trim()
        if (bruto.isEmpty()) {
            val erro = o.optString("erro").take(80).ifBlank { "sem texto" }
            Registro.anotar("celular: não ouviu ($erro); uso o ouvido da central")
            Rede.naTela { terminar(p, null, erro) }
            return
        }
        // Mesmo caminho do Ouvido: Corretor e depois Comandos (quem chamou executa).
        val texto = try {
            Corretor.corrigir(bruto, Lugares.todos(ctx).keys().asSequence().toList())
        } catch (_: Throwable) { bruto }
        Registro.anotar("celular ouviu: \"$bruto\"" + if (texto != bruto) " → \"$texto\"" else "")
        Rede.naTela { terminar(p, texto, null) }
    }

    // ------------------------------------------------------------------ ligação e mensagens

    /** Mensagens que chegaram durante uma ligação (só na memória; faladas quando a ligação acaba). */
    private val guardadas = ArrayDeque<Pair<String, String>>()

    fun emLigacao() = ligado && ligacao != "fim"

    private fun mudouLigacao(e: String) {
        val antes = ligacao
        ligacao = if (e == "tocando" || e == "atendida") e else "fim"
        if (antes != "fim" && ligacao == "fim") {
            val fila = synchronized(guardadas) { guardadas.toList().also { guardadas.clear() } }
            if (fila.isNotEmpty()) Rede.principal.postDelayed({ fila.forEach { falarMensagem(it.first, it.second) } }, 2_500)
        }
    }

    private fun aviso(ctx: Context, o: JSONObject) {
        if (!lerMensagens(ctx)) return
        val quem = o.optString("quem").trim().take(80).ifBlank { o.optString("app").take(30) }
        val texto = o.optString("texto").trim().take(300)
        if (texto.isEmpty()) return
        if (emLigacao()) {
            synchronized(guardadas) {
                guardadas.addLast(quem to texto)
                while (guardadas.size > 3) guardadas.removeFirst()
            }
            return
        }
        Rede.naTela { falarMensagem(quem, texto) }
    }

    /** Fala no carro, sem aparecer na tela nem no registro (privado). Nunca durante uma ligação. */
    private fun falarMensagem(quem: String, texto: String) {
        if (emLigacao()) return
        Voz.falarPrivado("Mensagem de $quem: $texto", Voz.Tipo.IMPORTANTE)
        Registro.anotar("celular: li uma mensagem em voz alta")
    }

    // ------------------------------------------------------------------ conector e telas

    /** Para o conector do Claude: SEM texto de mensagem e sem nomes. */
    fun estadoConector(): JSONObject = JSONObject()
        .put("ligado", ligado)
        .put("transporte", if (ligado) transporte else "")
        .put("modelo", modelo)
        .put("bateria", if (ligado && bateria >= 0) bateria else JSONObject.NULL)
        .put("musica_titulo", tituloAgora() ?: "")

    /** Texto curto para a tela de ajustes da central. */
    fun resumo(ctx: Context): String = when {
        ligado -> "Ligado pela " + (if (transporte == "cabo") "cabo USB" else "rede Bluetooth") +
            " · $modelo" + (if (bateria >= 0) " · bateria $bateria%" + (if (carregando) " ⚡" else "") else "") +
            (if (operadora.isNotBlank()) " · $operadora $rede" else "")
        pareando -> "Toque em Permitir no celular (código ${CaboCelular.codigo(ctx)})"
        else -> "Desligado · " + CaboCelular.estado
    }

    /** Bateria para a tela do carro ("CEL 87%"), ou "" se desligado. */
    fun bateriaCurta(): String = if (!ligado || bateria < 0) "" else "CEL $bateria%" + if (carregando) " ⚡" else ""

    /** Linhas para o diagnóstico (sem mensagens). */
    fun diagnostico(ctx: Context): String = buildString {
        append("estado: ").append(resumo(ctx)).append('\n')
        append("conexão: ").append(CaboCelular.estado).append('\n')
        append("app do celular: c").append(versaoApp).append(" · Android ").append(android)
            .append(" · microfone ").append(if (microfone) "liberado" else "não").append('\n')
        append("ler mensagens: ").append(if (lerMensagens(ctx)) "ligado" else "desligado").append('\n')
        append("código desta central: ").append(CaboCelular.codigo(ctx)).append('\n')
    }
}
