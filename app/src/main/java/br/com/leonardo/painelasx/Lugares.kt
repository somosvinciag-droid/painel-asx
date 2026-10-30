package br.com.leonardo.painelasx

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lugares favoritos: "vamos pra casa", "rota para a Mineirita", "salva aqui como cliente Fulano".
 * Cada lugar tem o endereço (para o Waze) e as coordenadas (para avisar a chegada).
 */
object Lugares {
    private const val PREFS = "favoritos"

    private val PADRAO = linkedMapOf(
        "casa" to "Rua Major Penha, 359, Centro, Caxambu, MG",
        "mineirita" to "Poços de Caldas, MG",
        "familia" to "Pouso Alegre, MG"
    )
    private val APELIDOS = mapOf(
        "minha casa" to "casa", "em casa" to "casa", "lar" to "casa",
        "minha familia" to "familia", "casa da familia" to "familia", "meus pais" to "familia", "casa dos meus pais" to "familia"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun todos(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString("lista", null) ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun gravar(ctx: Context, o: JSONObject) = prefs(ctx).edit().putString("lista", o.toString()).apply()

    fun iniciar(ctx: Context) {
        val o = todos(ctx)
        var mudou = false
        for ((k, v) in PADRAO) if (!o.has(k)) { o.put(k, JSONObject().put("endereco", v)); mudou = true }
        if (mudou) gravar(ctx, o)
        // Coordenadas que faltam (uma vez, com internet)
        val app = ctx.applicationContext
        Rede.emSegundoPlano("lugares") {
            val l = todos(app)
            var achou = false
            for (k in l.keys()) {
                val f = l.getJSONObject(k)
                if (f.has("lat")) continue
                val c = coordenadas(f.optString("endereco")) ?: continue
                f.put("lat", c.first).put("lon", c.second)
                achou = true
                Thread.sleep(1200)  // o serviço de mapas pede 1 pedido por segundo
            }
            if (achou) gravar(app, l)
        }
    }

    fun normal(nome: String) = Apps.normalizar(nome).replace(Regex("^(a|o|as|os|na|no|da|do) "), "").trim()

    /** O favorito com esse nome (ou apelido), ou null. */
    fun achar(ctx: Context, nome: String): Pair<String, JSONObject>? {
        val n = normal(nome)
        val chave = APELIDOS[n] ?: n
        val o = todos(ctx)
        if (o.has(chave)) return chave to o.getJSONObject(chave)
        for (k in o.keys()) if (chave == "cliente $k" || k == "cliente $chave") return k to o.getJSONObject(k)
        return null
    }

    fun salvar(ctx: Context, nome: String, endereco: String): String {
        val n = normal(nome)
        val o = todos(ctx)
        o.put(n, JSONObject().put("endereco", endereco))
        gravar(ctx, o)
        val app = ctx.applicationContext
        Rede.emSegundoPlano("lugares") {
            val c = coordenadas(endereco) ?: return@emSegundoPlano
            val l = todos(app)
            l.optJSONObject(n)?.put("lat", c.first)?.put("lon", c.second)
            gravar(app, l)
        }
        return "Guardei \"$n\": $endereco."
    }

    fun salvarAqui(ctx: Context, nome: String): String {
        val l: Location = Carro.ultimaPosicao ?: return "Ainda não tenho o GPS para saber onde estamos."
        val n = normal(nome)
        val o = todos(ctx)
        val end = "%.5f,%.5f".format(java.util.Locale.US, l.latitude, l.longitude)
        o.put(n, JSONObject().put("endereco", end).put("lat", l.latitude).put("lon", l.longitude))
        gravar(ctx, o)
        return "Guardei este lugar como \"$n\"."
    }

    fun texto(ctx: Context): String {
        val o = todos(ctx)
        val nomes = o.keys().asSequence().toList()
        return if (nomes.isEmpty()) "Nenhum favorito ainda." else "Favoritos: " + nomes.joinToString(", ") + "."
    }

    fun lista(ctx: Context): JSONArray {
        val o = todos(ctx)
        return JSONArray(o.keys().asSequence().map { k -> JSONObject(o.getJSONObject(k).toString()).put("nome", k) }.toList())
    }

    /** Endereço -> (lat, lon). Usa o OpenStreetMap; para cidade, cai no Open-Meteo. */
    fun coordenadas(endereco: String): Pair<Double, Double>? {
        Regex("^\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*$").find(endereco)?.let {
            return it.groupValues[1].toDouble() to it.groupValues[2].toDouble()
        }
        try {
            val t = Rede.get("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=br&q=" + Rede.enc(endereco))
            if (t != null) {
                val a = JSONArray(t)
                if (a.length() > 0) {
                    val o = a.getJSONObject(0)
                    return o.getString("lat").toDouble() to o.getString("lon").toDouble()
                }
            }
        } catch (_: Exception) {
        }
        try {
            val cidade = endereco.split(',').map { it.trim() }.lastOrNull { it.length > 2 && !it.matches(Regex("[A-Za-z]{2}")) } ?: endereco
            val t = Rede.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&countryCode=BR&name=" + Rede.enc(cidade))
            val r = JSONObject(t ?: return null).optJSONArray("results") ?: return null
            if (r.length() > 0) {
                val o = r.getJSONObject(0)
                return o.getDouble("latitude") to o.getDouble("longitude")
            }
        } catch (_: Exception) {
        }
        return null
    }
}
