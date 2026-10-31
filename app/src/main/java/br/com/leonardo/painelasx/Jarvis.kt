package br.com.leonardo.painelasx

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar

/**
 * O Jarvis do carro:
 *  - quando o carro liga: Turbo, tela do carro, "Bem-vindo, Leonardo", previsão e leads (de manhã),
 *    lembretes de manutenção e, por fim, "Iron Man" no Spotify;
 *  - conversa: o que não for comando vai para o Claude pelo servidor (se a chave da API estiver lá).
 */
object Jarvis {
    private var ultimaPartida = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
    fun musicaDeEntrada(ctx: Context) = prefs(ctx).getBoolean("musica_entrada", true)
    fun mudarMusicaDeEntrada(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("musica_entrada", v).apply()

    /** O carro ligou (a central acabou de iniciar). */
    fun partida(ctx: Context) {
        val app = ctx.applicationContext
        val agora = SystemClock.elapsedRealtime()
        if (ultimaPartida != 0L && agora - ultimaPartida < 60_000) return
        ultimaPartida = agora
        prefs(app).edit().putLong("saudou_em", System.currentTimeMillis()).commit()
        Registro.anotar("jarvis: carro ligou")
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val periodo = when { h < 12 -> "manha"; h < 18 -> "tarde"; else -> "noite" }
        val primeiraDoDia = primeiraVezHoje(app)
        // 1) Na hora: tela do carro + saudação já gravada (não espera a voz carregar).
        Rede.naTela { Painel.abrir(app) }
        val depoisDaSaudacao = {
            Rede.emSegundoPlano("partida") {
                // 2) Depois: tempo e lembretes (com a voz carregada), Turbo e a música.
                Clima.atualizar(app)
                val frase = StringBuilder()
                if (primeiraDoDia && Clima.temperatura != null) {
                    frase.append("Lá fora, ${Clima.temperatura} graus, ${Clima.descricao}.")
                    if (Clima.chanceChuva2h >= 40) frase.append(" Chance de chuva de ${Clima.chanceChuva2h} por cento.")
                }
                Diario.lembretesDoDia(app)?.let { frase.append(" ").append(it) }
                val tocarMusica = { if (musicaDeEntrada(app)) Musica.spotify(app, "Iron Man", "musica", "Black Sabbath") }
                val falar = frase.toString().trim()
                Rede.naTela { if (falar.isNotEmpty()) Voz.falar(falar, Voz.Tipo.RESPOSTA) { tocarMusica() } else tocarMusica() }
                try { Turbo.limpar(app) } catch (_: Exception) { }
            }
            Unit
        }
        if (!VozPropria.tocarSaudacao(app, periodo, depoisDaSaudacao)) {
            val s = when (periodo) { "manha" -> "Bom dia"; "tarde" -> "Boa tarde"; else -> "Boa noite" }
            Rede.naTela { Voz.falar("$s, Leonardo. Tudo bem? Sistemas online, às suas ordens.", Voz.Tipo.RESPOSTA) { depoisDaSaudacao() } }
        }
        Rede.emSegundoPlano("partida-atlas") {
            Thread.sleep(25_000) // espera o conector dizer se a Atlas está ligada
            // Leads da Atlas no primeiro bom-dia (se o Jarvis tiver a Atlas ligada)
            if (primeiraDoDia && h < 12 && Config.jarvisAtlas) {
                val r = perguntar(app, "Resumo matinal: em uma frase curta, quantos leads novos a Atlas recebeu ontem e hoje até agora.", "bom_dia")
                if (r != null) Rede.naTela { Voz.falar(r, Voz.Tipo.INFO) }
            }
        }
    }

    /** Verdadeiro se a central dormiu depois da última saudação (para saudar uma vez a cada vez que o carro liga). */
    fun devoSaudar(ctx: Context): Boolean {
        val p = prefs(ctx.applicationContext)
        val dormiu = p.getLong("dormiu_em", 0L)
        val saudou = p.getLong("saudou_em", 0L)
        if (dormiu > saudou) return true
        // A central pode fechar o app antes de avisar que vai dormir: se faz mais de 10 min da última saudação
        // e o app não acabou de ser atualizado, também é o carro ligando de novo.
        val agora = System.currentTimeMillis()
        val atualizou = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
        return agora - saudou > 10 * 60_000L && agora - atualizou > 3 * 60_000L
    }

    fun dormiu(ctx: Context) {
        prefs(ctx.applicationContext).edit().putLong("dormiu_em", System.currentTimeMillis()).commit()
    }

    private fun primeiraVezHoje(ctx: Context): Boolean {
        // Dia no fuso da central (antes era o dia UTC: às 21h de Brasília já virava o "dia seguinte"
        // e o bom-dia com o tempo da manhã seguinte não era dito se ele tivesse dirigido depois das 21h).
        val agora = System.currentTimeMillis()
        val hoje = (agora + java.util.TimeZone.getDefault().getOffset(agora)) / (24L * 3600 * 1000)
        if (prefs(ctx).getLong("ultimo_dia", -1) == hoje) return false
        prefs(ctx).edit().putLong("ultimo_dia", hoje).apply()
        return true
    }

    /** Conversa com o Claude (pelo servidor). Responde falando. */
    fun conversar(ctx: Context, frase: String) {
        val app = ctx.applicationContext
        Rede.emSegundoPlano("jarvis") {
            val r = perguntar(app, frase, "conversa")
                ?: "Não consegui falar com o Claude agora. Verifique a internet."
            Rede.naTela { Voz.falar(r, Voz.Tipo.RESPOSTA) }
        }
    }

    private fun perguntar(ctx: Context, frase: String, tipo: String): String? {
        val contexto = JSONObject()
            .put("hora", java.text.SimpleDateFormat("EEEE, dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date()))
            .put("velocidade_kmh", Carro.velocidadeKmh.toInt())
            .put("clima", Clima.curto())
            .put("km_hoje", Viagens.resumo(1).optDouble("km_total"))
            .put("musica", Musica.tocandoAgora(ctx) ?: "")
        Carro.ultimaPosicao?.let { contexto.put("lat", Math.round(it.latitude * 1000) / 1000.0).put("lon", Math.round(it.longitude * 1000) / 1000.0) }
        return try {
            val r = Conector.enviar("/app/jarvis", JSONObject().put("pergunta", frase).put("tipo", tipo).put("contexto", contexto), 60_000)
            val o = JSONObject(r.second.ifBlank { "{}" })
            when {
                r.first == 200 -> o.optString("resposta").ifBlank { null }
                o.has("erro") -> o.optString("erro")
                else -> null
            }
        } catch (e: Exception) {
            Registro.anotar("jarvis: sem resposta (${e.javaClass.simpleName})")
            null
        }
    }
}
