package br.com.leonardo.painelasx

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Diário de viagens e "onde o carro ficou", só com o GPS da central.
 *
 * - Começa uma viagem quando o carro anda (> 8 km/h) depois de parado.
 * - Termina quando fica parado 4 min, ou quando a central desliga (o carro desligado corta a
 *   energia de repente, por isso a viagem em andamento é salva a cada 30 s e fechada na próxima vez).
 * - Guarda as últimas 300 viagens: início, fim, km, duração, velocidade média e máxima, origem e destino.
 */
object Viagens {
    private const val MAX_VIAGENS = 300
    private const val INICIA_KMH = 8.0
    private const val PARADO_KMH = 3.0
    private const val FIM_PARADO_MS = 4 * 60 * 1000L

    private var arquivo: File? = null
    private var ultimoLocal: File? = null
    private val viagens = ArrayList<JSONObject>()

    // viagem em andamento
    private var ativa: JSONObject? = null
    private var anterior: Location? = null
    private var paradoDesde = 0L
    private var ultimoSalvo = 0L

    @Synchronized
    fun iniciar(ctx: Context) {
        if (arquivo != null) return
        arquivo = File(ctx.filesDir, "viagens.json")
        ultimoLocal = File(ctx.filesDir, "onde_parou.json")
        try {
            val a = JSONArray(arquivo!!.takeIf { it.exists() }?.readText() ?: "[]")
            for (i in 0 until a.length()) viagens.add(a.getJSONObject(i))
        } catch (_: Exception) {
        }
        // Viagem que ficou aberta (a central desligou no meio): fecha com o último ponto salvo.
        val aberta = viagens.lastOrNull()
        if (aberta != null && !aberta.has("fim")) {
            aberta.put("fim", aberta.optLong("ultimo_ponto", aberta.optLong("inicio")))
            aberta.put("fechada_por", "central desligou")
            aberta.remove("km_ultimo_ponto")
            calcularResumo(aberta)
            try { Diario.somarKm(ctx, aberta.optDouble("km")) } catch (_: Exception) { }
            Registro.anotar("viagem: fechei a viagem que ficou aberta (${"%.1f".format(aberta.optDouble("km"))} km)")
            salvar()
        }
    }

    private fun salvar() {
        try {
            arquivo?.writeText(JSONArray(viagens.takeLast(MAX_VIAGENS)).toString())
        } catch (_: Exception) {
        }
    }

    private fun calcularResumo(v: JSONObject) {
        val dur = (v.optLong("fim") - v.optLong("inicio")).coerceAtLeast(1)
        v.put("duracao_min", Math.round(dur / 60000.0))
        val km = v.optDouble("km", 0.0)
        v.put("vel_media_kmh", Math.round(km / (dur / 3600000.0)))
    }

    private fun ponto(l: Location) = JSONObject()
        .put("lat", Math.round(l.latitude * 100000) / 100000.0)
        .put("lon", Math.round(l.longitude * 100000) / 100000.0)

    /** Chamado a cada posição nova do GPS. */
    @Synchronized
    fun novaPosicao(l: Location) {
        if (arquivo == null) return
        if (l.hasAccuracy() && l.accuracy > 35f) return
        val agora = l.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val kmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0

        // Onde o carro está/ficou: guardado sempre (a cada 30 s no máximo).
        if (agora - ultimoSalvo > 30_000) {
            ultimoSalvo = agora
            try {
                ultimoLocal?.writeText(ponto(l).put("hora", agora).toString())
            } catch (_: Exception) {
            }
        }

        val v = ativa
        if (v == null) {
            if (kmh >= INICIA_KMH) {
                ativa = JSONObject().put("inicio", agora).put("origem", ponto(l)).put("km", 0.0).put("vel_max_kmh", Math.round(kmh))
                viagens.add(ativa!!)
                anterior = l
                paradoDesde = 0L
                Registro.anotar("viagem: começou")
                salvar()
            }
            return
        }

        // Soma a distância (ignora saltos impossíveis do GPS).
        val a = anterior
        if (a != null) {
            val m = a.distanceTo(l)
            val seg = ((agora - a.time).coerceAtLeast(1)) / 1000.0
            if (m / seg < 70) v.put("km", v.optDouble("km") + m / 1000.0)
        }
        anterior = l
        // Trajeto (para o mapa do histórico): 1 ponto a cada ~300 m, no máximo 150 por viagem.
        val traj = v.optJSONArray("trajeto") ?: JSONArray().also { v.put("trajeto", it) }
        val ultimoKm = v.optDouble("km_ultimo_ponto", -1.0)
        if (traj.length() < 150 && (ultimoKm < 0 || v.optDouble("km") - ultimoKm > 0.3)) {
            traj.put(JSONArray().put(Math.round(l.latitude * 100000) / 100000.0).put(Math.round(l.longitude * 100000) / 100000.0))
            v.put("km_ultimo_ponto", v.optDouble("km"))
        }
        if (kmh > v.optDouble("vel_max_kmh")) v.put("vel_max_kmh", Math.round(kmh))
        v.put("ultimo_ponto", agora).put("destino", ponto(l))

        if (kmh < PARADO_KMH) {
            if (paradoDesde == 0L) paradoDesde = agora
            if (agora - paradoDesde > FIM_PARADO_MS) terminar(paradoDesde, "parou")
        } else {
            paradoDesde = 0L
        }
        if (agora - v.optLong("salvo_em") > 30_000) {
            v.put("salvo_em", agora)
            salvar()
        }
    }

    @Synchronized
    private fun terminar(quando: Long, motivo: String) {
        val v = ativa ?: return
        ativa = null
        anterior = null
        paradoDesde = 0L
        v.put("fim", quando).put("fechada_por", motivo)
        v.remove("salvo_em")
        v.remove("km_ultimo_ponto")
        calcularResumo(v)
        if (v.optDouble("km") < 0.3) {
            viagens.remove(v)      // manobra/estacionar: não conta como viagem
        } else {
            Registro.anotar("viagem: terminou — ${"%.1f".format(v.optDouble("km"))} km em ${v.optLong("duracao_min")} min")
            try { Carro.fimDeViagem(v, motivo) } catch (_: Exception) { }
        }
        salvar()
    }

    /** A central vai desligar (tela apagou / desligando): fecha a viagem. */
    fun centralDesligando() = terminar(System.currentTimeMillis(), "central desligou")

    // ------------------------------------------------------------------ respostas

    @Synchronized
    fun ondeParou(): JSONObject {
        val o = try { JSONObject(ultimoLocal?.readText() ?: "{}") } catch (_: Exception) { JSONObject() }
        if (o.has("lat")) {
            o.put("mapa", "https://www.google.com/maps/search/?api=1&query=${o.optDouble("lat")},${o.optDouble("lon")}")
        } else {
            o.put("aviso", "Ainda não tenho posição guardada (precisa do GPS ligado e sinal).")
        }
        return o
    }

    @Synchronized
    fun resumo(dias: Int, comTrajeto: Boolean = false): JSONObject {
        val desde = System.currentTimeMillis() - dias * 24L * 3600 * 1000
        val lista = viagens.filter { it.optLong("inicio") >= desde && it.has("fim") }
        val km = lista.sumOf { it.optDouble("km") }
        val min = lista.sumOf { it.optLong("duracao_min") }
        return JSONObject()
            .put("periodo_dias", dias)
            .put("viagens", lista.size)
            .put("km_total", Math.round(km * 10) / 10.0)
            .put("horas_dirigindo", Math.round(min / 6.0) / 10.0)
            .put("vel_max_kmh", lista.maxOfOrNull { it.optLong("vel_max_kmh") } ?: 0)
            .put("nota_media", lista.filter { it.has("nota") }.map { it.optInt("nota") }.let { if (it.isEmpty()) JSONObject.NULL else it.average().toInt() })
            .put("em_andamento", ativa != null)
            .put("lista", JSONArray(lista.takeLast(40).map { v ->
                JSONObject(v.toString()).apply { remove("ultimo_ponto"); remove("salvo_em"); if (!comTrajeto) remove("trajeto") }
            }))
    }

    @Synchronized
    fun ultima(): JSONObject? = viagens.lastOrNull { it.has("fim") }
}
