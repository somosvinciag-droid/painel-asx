package br.com.leonardo.painelasx

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * A navegação do mapa próprio: acha o destino (favorito, busca sem internet, ou Nominatim se houver internet),
 * calcula a rota NOSSA, acompanha o GPS (o mesmo do Carro), fala as curvas com a voz do Jarvis, recalcula
 * quando ele sai do caminho e avisa a chegada. Continua falando mesmo com a tela do mapa fechada.
 * Sem rota e sem a tela do mapa aberta, solta o pacote da memória.
 */
object Navegacao {
    @Volatile private var dados: MapaDados? = null
    @Volatile var rota: Rota? = null
        private set
    @Volatile var guia: MapaGuia? = null
        private set
    @Volatile var estado = ""
        private set
    @Volatile var telaAberta = false

    private var nomes: List<String> = emptyList()
    private var pontos: List<Pair<Double, Double>> = emptyList()
    @Volatile private var ultimoRecalculo = 0L
    @Volatile private var recalculando = false
    @Volatile private var chegouEm = 0L
    private var appCtx: Context? = null
    private val ouvintes = ArrayList<() -> Unit>()

    private val linha: Handler by lazy { Handler(HandlerThread("mapa-rota").also { it.start() }.looper) }

    fun navegando() = rota != null

    /** Ouvintes da tela do mapa (rota nova, recalculou, chegou). */
    fun ouvir(f: () -> Unit) = synchronized(ouvintes) { ouvintes.add(f) }
    fun parar(f: () -> Unit) = synchronized(ouvintes) { ouvintes.remove(f) }
    private fun avisarTela() {
        val l = synchronized(ouvintes) { ArrayList(ouvintes) }
        Rede.naTela { l.forEach { try { it() } catch (_: Exception) { } } }
    }

    /** O pacote (abre na primeira vez que precisa). null = ainda não baixado. */
    fun dados(ctx: Context): MapaDados? {
        dados?.let { return it }
        if (!MapaPacote.pronto(ctx)) return null
        return try {
            synchronized(this) { dados ?: MapaDados.abrir(MapaPacote.arquivo(ctx)).also { dados = it } }
        } catch (e: Exception) {
            Registro.anotar("ERRO mapa: não abriu o pacote (${e.message})")
            null
        }
    }

    /** Abriu o mapa: prepara a busca em segundo plano (os nomes de ~60 mil ruas). */
    fun aquecer(ctx: Context) {
        val d = dados(ctx) ?: return
        linha.post { try { busca(d).aquecer() } catch (_: Throwable) { } }
    }

    /** Saiu um pacote novo: na próxima vez abre o novo (sem rota em andamento). */
    fun pacoteNovo() { if (rota == null) dados = null }

    /** Sem rota e sem tela do mapa: solta o pacote (a memória mapeada volta para o sistema). */
    fun talvezSoltar() { if (rota == null && !telaAberta) { dados = null; buscaCache = null } }

    private fun falar(t: String) {
        Registro.anotar("mapa: $t")
        Rede.naTela { Voz.falar(t, Voz.Tipo.IMPORTANTE) }
    }

    /**
     * Começa a rota no mapa do Painel. Devolve a frase da resposta, ou null se o mapa não está pronto
     * (aí quem chamou abre o Waze, como antes). O cálculo é em segundo plano; se falhar, abre o Waze.
     */
    fun iniciar(ctx: Context, pedidos: List<String>, enderecos: List<String>): String? {
        val app = ctx.applicationContext
        appCtx = app
        val d = dados(app)
        if (d == null) {
            MapaPacote.talvezBaixar(app, "pediu rota")
            return null
        }
        estado = "calculando"
        TelaMapa.abrir(app)
        avisarTela()
        linha.post { calcular(app, d, pedidos, enderecos) }
        return "Calculando a rota para ${pedidos.last()}."
    }

    private fun calcular(ctx: Context, d: MapaDados, pedidos: List<String>, enderecos: List<String>) {
        val t0 = SystemClock.elapsedRealtime()
        val pts = ArrayList<Pair<Double, Double>>()
        for ((i, end) in enderecos.withIndex()) {
            val p = acharPonto(ctx, d, pedidos[i], end)
            if (p == null || !d.dentro(p.first, p.second)) {
                desistir(ctx, enderecos, if (p == null) "Não achei ${pedidos[i]} no mapa." else "${pedidos[i]} fica fora do mapa da região.")
                return
            }
            pts.add(p)
        }
        val l = Carro.ultimaPosicao
        val (ox, oy) = if (l != null) d.x(l.longitude) to d.y(l.latitude) else {
            desistir(ctx, enderecos, "Ainda não tenho o GPS.")
            return
        }
        if (!d.dentro(ox, oy)) { desistir(ctx, enderecos, "Estamos fora do mapa da região."); return }
        try {
            val nomesBonitos = pedidos.map { nomeBonito(ctx, it) }
            val r = Roteador(d).rota(ox, oy, if (l.hasBearing()) l.bearing.toDouble() else null, l.speed * 3.6, pts, nomesBonitos)
            nomes = nomesBonitos; pontos = pts
            comecar(r)
            Registro.anotar("mapa: rota ${"%.1f".format(r.totalM / 1000)} km, ${r.manobras.size} manobras, ${SystemClock.elapsedRealtime() - t0} ms")
            falar(MapaFrases.inicio(r))
        } catch (e: Roteador.SemRota) {
            desistir(ctx, enderecos, "Não achei caminho: ${e.message}.")
        } catch (e: Throwable) {
            Registro.anotar("ERRO mapa: ${e.javaClass.simpleName}: ${e.message}")
            desistir(ctx, enderecos, "A rota deu um problema.")
        }
    }

    private fun nomeBonito(ctx: Context, pedido: String): String {
        val f = Lugares.achar(ctx, pedido)?.first ?: return pedido.trim().replaceFirstChar { it.uppercase() }
        return when (f) { "casa" -> "Casa"; "familia" -> "Família"; else -> f.replaceFirstChar { it.uppercase() } }
    }

    private fun comecar(r: Rota) {
        val g = MapaGuia(r) { falar(it) }
        guia = g; rota = r; estado = "navegando"; chegouEm = 0
        avisarTela()
    }

    /** Não deu no mapa próprio: fala o motivo e abre o Waze (como antes). */
    private fun desistir(ctx: Context, enderecos: List<String>, motivo: String) {
        estado = ""
        rota = null; guia = null
        avisarTela()
        Rede.naTela {
            val r = Rotas.navegarFora(ctx, enderecos)
            Voz.falar("$motivo $r", Voz.Tipo.RESPOSTA)
            Registro.anotar("mapa: $motivo → $r")
        }
    }

    @Volatile private var buscaCache: Pair<MapaDados, MapaBusca>? = null
    private fun busca(d: MapaDados): MapaBusca {
        buscaCache?.let { if (it.first === d) return it.second }
        return MapaBusca(d).also { buscaCache = d to it }
    }

    /** Destino → ponto (dm). Favorito com coordenadas; "lat,lon"; busca sem internet; Nominatim. */
    private fun acharPonto(ctx: Context, d: MapaDados, pedido: String, endereco: String): Pair<Double, Double>? {
        val fav = Lugares.achar(ctx, pedido)?.second
        if (fav != null && fav.has("lat")) return d.x(fav.optDouble("lon")) to d.y(fav.optDouble("lat"))
        Regex("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$").find(endereco)?.let {
            return d.x(it.groupValues[2].toDouble()) to d.y(it.groupValues[1].toDouble())
        }
        val l = Carro.ultimaPosicao
        val a = busca(d).buscar(endereco, l?.let { d.x(it.longitude) }, l?.let { d.y(it.latitude) })
        Registro.anotar("mapa: busca \"$endereco\" → ${a ?: "nada"}")
        if (a != null && !(a.tipo == "cidade" && a.nota < 60)) return a.x to a.y
        // não achou a rua sem internet: tenta o Nominatim (se houver internet); senão vai para a cidade
        val online = try { Lugares.coordenadas(endereco) } catch (_: Exception) { null }
        if (online != null) return d.x(online.second) to d.y(online.first)
        return a?.let { it.x to it.y }
    }

    /** Nova posição do GPS (linha principal, chamada pelo Carro). */
    fun posicao(l: Location) {
        val g = guia ?: return
        val d = dados ?: return
        val x = d.x(l.longitude); val y = d.y(l.latitude)
        val ev = g.posicao(x, y, if (l.hasBearing() && l.speed > 1.5f) l.bearing.toDouble() else null, l.speed * 3.6,
            if (l.hasAccuracy()) l.accuracy.toDouble() else 10.0)
        when (ev) {
            MapaGuia.Evento.FORA_DA_ROTA -> recalcular(l, x, y)
            MapaGuia.Evento.CHEGOU -> if (chegouEm == 0L) {
                chegouEm = SystemClock.elapsedRealtime()
                avisarTela()
                // mostra "chegou" um pouco e encerra a rota
                Rede.principal.postDelayed({ if (chegouEm > 0 && rota === g.rota) encerrar(falarFim = false) }, 45_000)
            }
            else -> {}
        }
    }

    private fun recalcular(l: Location, x: Double, y: Double) {
        val agora = SystemClock.elapsedRealtime()
        if (recalculando || agora - ultimoRecalculo < 8_000) return
        val d = dados ?: return
        val r = rota ?: return
        ultimoRecalculo = agora
        recalculando = true
        // só os destinos que faltam (as paradas já feitas ficam para trás)
        val feitas = r.manobras.count { it.tipo == Manobra.Tipo.PARADA && it.distM < (guia?.feitoM ?: 0.0) }
        val pts = pontos.drop(feitas); val ns = nomes.drop(feitas)
        falar("Recalculando.")
        linha.post {
            try {
                val nova = Roteador(d).rota(x, y, if (l.hasBearing()) l.bearing.toDouble() else null, l.speed * 3.6, pts, ns)
                pontos = pts; nomes = ns
                if (rota != null) comecar(nova)
                Registro.anotar("mapa: recalculada, ${"%.1f".format(nova.totalM / 1000)} km")
            } catch (e: Throwable) {
                Registro.anotar("mapa: não recalculou (${e.message})")
            } finally {
                recalculando = false
            }
        }
    }

    fun encerrar(falarFim: Boolean = true): String {
        val tinha = rota != null
        rota = null; guia = null; estado = ""; chegouEm = 0
        avisarTela()
        talvezSoltar()
        return if (tinha) "Rota encerrada." else "Não há rota em andamento."
    }

    /** "Faltam 12 quilômetros, uns 15 minutos. Chegada às 17:42." */
    fun resumoFalado(): String {
        val g = guia ?: return "Não há rota em andamento."
        val chegada = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
            .format(java.util.Date(System.currentTimeMillis() + (g.restanteS * 1000).toLong()))
        return "Faltam ${MapaFrases.distanciaFalada(g.restanteM)}, ${MapaFrases.duracaoFalada(g.restanteS)}. Chegada às $chegada."
    }

    /** Para o conector: o que o mapa está fazendo. */
    fun estadoConector(ctx: Context): org.json.JSONObject {
        val o = org.json.JSONObject().put("pacote", MapaPacote.estado).put("versao_pacote", MapaPacote.versaoInstalada(ctx))
            .put("rotas_em", if (Config.rotasNoPainel(ctx)) "painel" else "waze")
        val g = guia
        if (g != null) o.put("destino", g.rota.destino).put("faltam_km", Math.round(g.restanteM / 100) / 10.0)
            .put("fora_da_rota_m", Math.round(g.distDaRotaM))
        return o
    }
}
