package br.com.leonardo.painelasx

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Diário do carro: odômetro, abastecimentos (consumo km/l), manutenção, calibragem,
 * anotações e pedidos de clientes por voz (vão para o servidor, onde o Claude lê).
 */
object Diario {
    private const val PREFS = "carro"
    private const val DIA = 24L * 3600 * 1000
    private val BR = Locale("pt", "BR")

    /** Serviços com intervalo padrão (km, meses). Dá para mudar depois. */
    val SERVICOS = linkedMapOf(
        "oleo" to Triple("óleo e filtro", 10_000, 12),
        "cvt" to Triple("fluido do câmbio CVT", 40_000, 48),
        "pneus" to Triple("rodízio, alinhamento e balanceamento", 10_000, 12)
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ odômetro

    /** km somados pelo GPS (todas as viagens). */
    fun kmGps(ctx: Context) = p(ctx).getFloat("km_gps", 0f).toDouble()

    @Synchronized
    fun somarKm(ctx: Context, km: Double) {
        if (km <= 0 || km > 2000) return
        p(ctx).edit().putFloat("km_gps", (kmGps(ctx) + km).toFloat()).apply()
    }

    /** Odômetro estimado: o que você informou + o que o GPS somou depois. null = nunca informado. */
    fun odometro(ctx: Context): Int? {
        val base = p(ctx).getInt("odo_base", -1)
        if (base < 0) return null
        return (base + kmGps(ctx) - p(ctx).getFloat("odo_gps_base", 0f)).toInt()
    }

    /** km "de referência" para manutenção: odômetro se souber, senão os km do GPS. */
    private fun kmRef(ctx: Context) = odometro(ctx)?.toDouble() ?: kmGps(ctx)

    fun mudarOdometro(ctx: Context, km: Int): String {
        val antes = kmRef(ctx)
        val e = p(ctx).edit().putInt("odo_base", km).putFloat("odo_gps_base", kmGps(ctx).toFloat())
        // Serviços marcados em "km do GPS" passam a contar no odômetro.
        if (p(ctx).getInt("odo_base", -1) < 0) {
            for (k in SERVICOS.keys) {
                val feito = p(ctx).getFloat("srv_${k}_km", -1f)
                if (feito >= 0) e.putFloat("srv_${k}_km", (km - (antes - feito)).toFloat())
            }
        }
        e.apply()
        return "Odômetro anotado: ${"%,d".format(BR, km)} km."
    }

    // ------------------------------------------------------------------ manutenção

    private fun garantirInicio(ctx: Context) {
        val e = p(ctx).edit()
        var mudou = false
        for (k in SERVICOS.keys) if (!p(ctx).contains("srv_${k}_data")) {
            e.putLong("srv_${k}_data", System.currentTimeMillis()).putFloat("srv_${k}_km", kmRef(ctx).toFloat())
            mudou = true
        }
        if (!p(ctx).contains("calibrou")) { e.putLong("calibrou", System.currentTimeMillis()); mudou = true }
        if (mudou) e.apply()
    }

    fun servicoFeito(ctx: Context, chave: String, km: Int? = null): String {
        val s = SERVICOS[chave] ?: return "Serviço desconhecido."
        val onde = km?.toDouble() ?: kmRef(ctx)
        p(ctx).edit().putLong("srv_${chave}_data", System.currentTimeMillis()).putFloat("srv_${chave}_km", onde.toFloat()).apply()
        if (chave == "pneus") p(ctx).edit().putLong("calibrou", System.currentTimeMillis()).apply()
        return "Anotado: ${s.first} feito agora. Próximo em ${"%,d".format(BR, s.second)} km ou ${s.third} meses."
    }

    fun calibrou(ctx: Context): String {
        p(ctx).edit().putLong("calibrou", System.currentTimeMillis()).apply()
        return "Calibragem anotada. Lembro de novo em 15 dias."
    }

    /** Situação de cada serviço: (texto, vencido/perto). */
    fun manutencao(ctx: Context): List<Pair<String, Boolean>> {
        garantirInicio(ctx)
        val agora = System.currentTimeMillis()
        val ref = kmRef(ctx)
        val r = ArrayList<Pair<String, Boolean>>()
        for ((k, s) in SERVICOS) {
            val faltaKm = s.second - (ref - p(ctx).getFloat("srv_${k}_km", ref.toFloat()))
            val faltaDias = s.third * 30 - (agora - p(ctx).getLong("srv_${k}_data", agora)) / DIA
            val perto = faltaKm < 500 || faltaDias < 15
            val txt = if (faltaKm <= 0 || faltaDias <= 0) "${s.first}: VENCIDO"
            else "${s.first}: faltam ${"%,d".format(BR, faltaKm.toInt())} km ou $faltaDias dias"
            r.add(txt to perto)
        }
        val dias = (agora - p(ctx).getLong("calibrou", agora)) / DIA
        r.add((if (dias >= 15) "calibragem dos pneus: já faz $dias dias" else "calibragem dos pneus: em ${15 - dias} dias") to (dias >= 15))
        return r
    }

    fun manutencaoTexto(ctx: Context): String {
        val odo = odometro(ctx)
        return (if (odo != null) "Odômetro estimado: ${"%,d".format(BR, odo)} km.\n" else "Odômetro: ainda não informado (fale \"odômetro 123456\").\n") +
            manutencao(ctx).joinToString("\n") { it.first }
    }

    /** Lembretes para falar quando o carro liga (1 vez por dia). */
    fun lembretesDoDia(ctx: Context): String? {
        val hoje = System.currentTimeMillis() / DIA
        if (p(ctx).getLong("lembrou_dia", -1) == hoje) return null
        val itens = manutencao(ctx).filter { it.second }.map { it.first }
        if (itens.isEmpty()) return null
        p(ctx).edit().putLong("lembrou_dia", hoje).apply()
        return "Lembrete de manutenção: " + itens.joinToString("; ") + "."
    }

    // ------------------------------------------------------------------ abastecimento

    private fun numero(t: String) = t.replace(".", "").replace(",", ".").toDoubleOrNull()

    fun abastecer(ctx: Context, falado: String): String {
        val t = falado.lowercase()   // sem normalizar: precisa das vírgulas dos números
        val litros = Regex("([\\d.,]+)\\s*(litros|litro|l\\b)").find(t)?.groupValues?.get(1)?.let { numero(it) }
        val reais = (Regex("([\\d.,]+)\\s*(reais|real|conto|pila)").find(t)?.groupValues?.get(1)
            ?: Regex("r\\$\\s*([\\d.,]+)").find(falado.lowercase())?.groupValues?.get(1))?.let { numero(it) }
        if (litros == null && reais == null) return "Fala assim: \"abasteci 40 litros, 230 reais\"."
        val km = kmRef(ctx)
        val lista = abastecimentos(ctx)
        val anterior = lista.optJSONObject(lista.length() - 1)
        val o = JSONObject().put("hora", System.currentTimeMillis()).put("km", Math.round(km))
        if (litros != null) o.put("litros", litros)
        if (reais != null) o.put("reais", reais)
        if (litros != null && reais != null && litros > 0) o.put("preco_litro", Math.round(reais / litros * 100) / 100.0)
        var consumo = ""
        if (anterior != null && litros != null && litros > 3) {
            val rodou = km - anterior.optDouble("km")
            if (rodou in 20.0..1500.0) {
                val kml = Math.round(rodou / litros * 10) / 10.0
                o.put("km_por_litro", kml).put("km_rodados", Math.round(rodou))
                consumo = " Consumo desde o último: ${"%.1f".format(BR, kml)} km por litro."
            }
        }
        lista.put(o)
        val ultimos = JSONArray()
        for (i in maxOf(0, lista.length() - 100) until lista.length()) ultimos.put(lista.get(i))
        p(ctx).edit().putString("abastecimentos", ultimos.toString()).apply()
        Fila.enviar(ctx, "/app/abastecimento", o)
        val partes = mutableListOf<String>()
        if (litros != null) partes.add("${"%.1f".format(BR, litros)} litros")
        if (reais != null) partes.add("R$ ${"%.2f".format(BR, reais)}")
        if (o.has("preco_litro")) partes.add("R$ ${"%.2f".format(BR, o.optDouble("preco_litro"))} o litro")
        return "Abastecimento anotado: ${partes.joinToString(", ")}.$consumo"
    }

    fun abastecimentos(ctx: Context): JSONArray = try {
        JSONArray(p(ctx).getString("abastecimentos", null) ?: "[]")
    } catch (_: Exception) { JSONArray() }

    // ------------------------------------------------------------------ anotações e pedidos

    fun anotar(ctx: Context, tipo: String, texto: String): String {
        val o = JSONObject().put("tipo", tipo).put("texto", texto.trim()).put("hora", System.currentTimeMillis())
        Carro.ultimaPosicao?.let { o.put("lat", Math.round(it.latitude * 10000) / 10000.0).put("lon", Math.round(it.longitude * 10000) / 10000.0) }
        Fila.enviar(ctx, "/app/nota", o)
        return if (tipo == "pedido") "Pedido anotado. O Claude vai ver." else "Anotado. Vai para o seu Claude."
    }
}

/**
 * Fila do que vai para o servidor: se estiver sem internet, guarda e manda depois.
 */
object Fila {
    private const val PREFS = "notas_fila"

    @Synchronized
    fun enviar(ctx: Context, caminho: String, corpo: JSONObject) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("fila", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("caminho", caminho).put("corpo", corpo))
        p.edit().putString("fila", a.toString()).apply()
        val app = ctx.applicationContext
        Rede.emSegundoPlano("fila") { esvaziar(app) }
    }

    @Synchronized
    fun esvaziar(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("fila", "[]")) } catch (_: Exception) { JSONArray() }
        if (a.length() == 0) return
        val sobra = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val ok = try {
                Conector.enviar(o.getString("caminho"), o.getJSONObject("corpo")).first == 200
            } catch (_: Exception) { false }
            if (!ok) sobra.put(o)
        }
        p.edit().putString("fila", sobra.toString()).apply()
    }
}
