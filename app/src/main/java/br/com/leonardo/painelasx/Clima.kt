package br.com.leonardo.painelasx

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Tempo e chuva (Open-Meteo: grátis, sem chave), nascer e pôr do sol (para o modo noite).
 * Atualiza a cada 30 min com internet. Usa o GPS; sem GPS, a casa (Caxambu).
 */
object Clima {
    @Volatile var temperatura: Int? = null
        private set
    @Volatile var descricao = ""
        private set
    @Volatile var chanceChuva2h = 0
        private set
    @Volatile var minMax = ""
        private set
    @Volatile private var nascer = -1   // minutos do dia
    @Volatile private var por = -1
    @Volatile private var atualizadoEm = 0L
    private var avisouChuvaEm = 0L

    fun texto(): String {
        val t = temperatura ?: return "Ainda não tenho a previsão (precisa de internet)."
        return "$t graus, $descricao. Chance de chuva nas próximas 2 horas: $chanceChuva2h por cento." +
            (if (minMax.isNotBlank()) " Hoje: $minMax." else "")
    }

    fun curto(): String = temperatura?.let { "$it° · $descricao" + if (chanceChuva2h >= 40) " · ☔ $chanceChuva2h%" else "" } ?: "—"

    /** Pela hora do pôr/nascer do sol; sem previsão, 18h30 às 6h. */
    fun ehNoite(): Boolean {
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val n = if (nascer > 0) nascer else 6 * 60
        val p = if (por > 0) por else 18 * 60 + 30
        return m < n || m >= p
    }

    fun atualizarSePreciso(ctx: Context) {
        if (System.currentTimeMillis() - atualizadoEm < 30 * 60_000) return
        atualizar(ctx)
    }

    fun atualizar(ctx: Context) {
        val l = Carro.ultimaPosicao
        val casa = Lugares.achar(ctx, "casa")?.second
        val lat = l?.latitude ?: casa?.optDouble("lat", -21.977) ?: -21.977
        val lon = l?.longitude ?: casa?.optDouble("lon", -44.933) ?: -44.933
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code&hourly=precipitation_probability&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min" +
            "&timezone=America%2FSao_Paulo&forecast_days=2"
        val t = Rede.get(url) ?: return
        try {
            val o = JSONObject(t)
            val cur = o.getJSONObject("current")
            temperatura = Math.round(cur.getDouble("temperature_2m")).toInt()
            descricao = descrever(cur.optInt("weather_code"))
            val horas = o.getJSONObject("hourly").getJSONArray("time")
            val prob = o.getJSONObject("hourly").getJSONArray("precipitation_probability")
            val agora = cur.getString("time").take(13)   // "2026-10-30T14"
            var idx = 0
            for (i in 0 until horas.length()) if (horas.getString(i).take(13) == agora) { idx = i; break }
            var maxP = 0
            for (i in idx..minOf(idx + 2, prob.length() - 1)) maxP = maxOf(maxP, prob.optInt(i))
            chanceChuva2h = maxP
            val d = o.getJSONObject("daily")
            nascer = minutos(d.getJSONArray("sunrise").getString(0))
            por = minutos(d.getJSONArray("sunset").getString(0))
            minMax = "mínima ${Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))}, máxima ${Math.round(d.getJSONArray("temperature_2m_max").getDouble(0))}"
            atualizadoEm = System.currentTimeMillis()
            // Alerta de chuva (no máximo a cada 3 h)
            if (maxP >= 60 && System.currentTimeMillis() - avisouChuvaEm > 3 * 3600_000L) {
                avisouChuvaEm = System.currentTimeMillis()
                Rede.naTela { Voz.falar("Previsão de chuva nas próximas duas horas: $maxP por cento.", Voz.Tipo.INFO) }
            }
        } catch (e: Exception) {
            Registro.anotar("clima: resposta estranha (${e.message})")
        }
    }

    private fun minutos(iso: String): Int = try {
        val h = iso.substringAfter('T')
        h.substring(0, 2).toInt() * 60 + h.substring(3, 5).toInt()
    } catch (_: Exception) { -1 }

    private fun descrever(c: Int) = when (c) {
        0 -> "céu limpo"
        1 -> "quase limpo"
        2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "neblina"
        in 51..57 -> "garoa"
        in 61..67 -> "chuva"
        in 80..82 -> "pancadas de chuva"
        in 95..99 -> "trovoadas"
        in 71..77, 85, 86 -> "frio com neve"
        else -> "tempo instável"
    }
}
