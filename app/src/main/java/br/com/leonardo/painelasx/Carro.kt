package br.com.leonardo.painelasx

import android.content.Context
import android.location.Location
import android.media.AudioManager
import android.view.KeyEvent
import org.json.JSONObject

/**
 * O "cérebro" de quando o carro anda (só com o GPS da central):
 *  - aviso de velocidade acima do limite;
 *  - parado com o carro ligado há 5 minutos;
 *  - freada forte ("está tudo bem?");
 *  - nota de direção de cada viagem;
 *  - aviso de chegada ao destino da rota e "chegando em casa" (500 m: pausa a música e mostra o resumo);
 *  - ré: abaixa o volume e avisa.
 */
object Carro {
    @Volatile var ultimaPosicao: Location? = null
        private set
    @Volatile var velocidadeKmh = 0.0
        private set
    @Volatile var ultimoAviso = ""
        private set

    @Volatile private var appCtx: Context? = null

    // velocidade
    private var acimaDesde = 0L
    private var avisouVelocidade = false
    private var abaixoDesde = 0L

    // parado ligado
    private var paradoDesde = 0L
    private var avisouParado = false

    // freada
    private var anterior: Location? = null
    private var ultimaFreada = 0L

    // nota da viagem
    private var freadas = 0
    private var msAcima = 0L   // em milissegundos (somar dt/1000 a cada leitura perdia os 999 ms)
    private var ultimoTempo = 0L

    // destino
    private var destinoNome: String? = null
    private var destinoLat = 0.0
    private var destinoLon = 0.0
    // Começa "perto": só avisa "chegando em casa" depois de ter ido a mais de 2 km.
    // (Antes, ao SAIR de casa com o app recém-aberto, dizia "Chegando em casa" e pausava a música.)
    private var casaPerto = true

    // ré
    private var volumeAntesRe = -1

    fun iniciar(ctx: Context) { appCtx = ctx.applicationContext }

    private fun avisar(texto: String, tipo: Voz.Tipo = Voz.Tipo.IMPORTANTE) {
        ultimoAviso = texto
        Registro.anotar("jarvis: $texto")
        Rede.naTela { Voz.falar(texto, tipo) }
    }

    @Synchronized
    fun posicao(l: Location) {
        val ctx = appCtx ?: return
        ultimaPosicao = l
        val agora = System.currentTimeMillis()
        val kmh = if (l.hasSpeed()) l.speed * 3.6 else 0.0
        velocidadeKmh = kmh
        val dt = if (ultimoTempo > 0) (agora - ultimoTempo).coerceIn(0, 5000) else 0
        ultimoTempo = agora

        // ---- velocidade
        val limite = Config.limite(ctx)
        if (kmh > limite + 2) {
            msAcima += dt
            abaixoDesde = 0
            if (acimaDesde == 0L) acimaDesde = agora
            if (!avisouVelocidade && agora - acimaDesde > 5000) {
                avisouVelocidade = true
                avisar("Atenção, senhor: ${kmh.toInt()} quilômetros por hora. O limite é $limite.")
            }
        } else {
            acimaDesde = 0
            if (kmh < limite - 5) {
                if (abaixoDesde == 0L) abaixoDesde = agora
                if (agora - abaixoDesde > 30_000) avisouVelocidade = false
            }
        }

        // ---- parado com o carro ligado
        if (kmh < 2) {
            if (paradoDesde == 0L) paradoDesde = agora
            if (!avisouParado && agora - paradoDesde > 5 * 60_000) {
                avisouParado = true
                avisar("O carro está parado e ligado há 5 minutos.", Voz.Tipo.INFO)
            }
        } else {
            paradoDesde = 0
            avisouParado = false
        }

        // ---- freada forte (perde mais de 14 km/h em 1 segundo, vindo acima de 30)
        val a = anterior
        if (a != null && a.hasSpeed() && l.hasSpeed()) {
            val seg = ((l.time - a.time) / 1000.0)
            if (seg in 0.5..2.5) {
                val antesKmh = a.speed * 3.6
                val queda = (antesKmh - kmh) / seg
                if (antesKmh > 30 && queda > 14 && agora - ultimaFreada > 60_000) {
                    ultimaFreada = agora
                    freadas++
                    avisar("Freada forte. Está tudo bem, Leonardo?")
                }
            }
        }
        anterior = l

        // ---- chegada ao destino da rota
        if (destinoNome != null) {
            val d = distancia(l, destinoLat, destinoLon)
            if (d < 250) {
                val n = destinoNome
                destinoNome = null
                if (n != "casa") avisar("Chegamos: $n.", Voz.Tipo.INFO)
            }
        }

        // ---- chegando em casa (500 m)
        val casa = Lugares.achar(ctx, "casa")?.second
        if (casa != null && casa.has("lat")) {
            val d = distancia(l, casa.optDouble("lat"), casa.optDouble("lon"))
            if (!casaPerto && d < 500 && kmh > 3) {
                casaPerto = true
                chegandoEmCasa(ctx)
            } else if (d > 2000) {
                casaPerto = false
            }
        }
    }

    private fun chegandoEmCasa(ctx: Context) {
        val r = Viagens.resumo(1)
        Rede.naTela {
            Rotina.teclaMidia(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Painel.abrir(ctx)
        }
        avisar("Chegando em casa. Hoje foram ${"%.0f".format(r.optDouble("km_total"))} quilômetros em ${r.optInt("viagens")} viagens.", Voz.Tipo.RESPOSTA)
    }

    private fun distancia(l: Location, lat: Double, lon: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(l.latitude, l.longitude, lat, lon, r)
        return r[0]
    }

    /** Rota pedida por voz: guarda o destino para avisar quando chegar. */
    fun definirDestino(nome: String, endereco: String) {
        val ctx = appCtx ?: return
        val fav = Lugares.achar(ctx, nome)?.second
        if (fav != null && fav.has("lat")) {
            synchronized(this) { destinoNome = nome; destinoLat = fav.optDouble("lat"); destinoLon = fav.optDouble("lon") }
            return
        }
        Rede.emSegundoPlano("destino") {
            val c = Lugares.coordenadas(endereco) ?: return@emSegundoPlano
            synchronized(this) { destinoNome = nome; destinoLat = c.first; destinoLon = c.second }
        }
    }

    /** Fim de uma viagem: põe a nota e fala o resumo (só quando parou de verdade, não ao religar). */
    @Synchronized
    fun fimDeViagem(v: JSONObject, motivo: String) {
        val segAcima = msAcima / 1000
        val nota = (100 - freadas * 8 - (segAcima / 10).toInt()).coerceIn(0, 100)
        v.put("nota", nota).put("freadas_fortes", freadas).put("seg_acima_limite", segAcima)
        freadas = 0
        msAcima = 0
        if (motivo == "parou") {
            val ctx = appCtx ?: return
            val km = v.optDouble("km")
            val hoje = Viagens.resumo(1).optDouble("km_total")
            avisar("Viagem encerrada: ${"%.1f".format(km)} quilômetros, nota $nota. Hoje: ${"%.0f".format(hoje)} quilômetros.", Voz.Tipo.INFO)
            Diario.somarKm(ctx, km)
        } else {
            appCtx?.let { Diario.somarKm(it, v.optDouble("km")) }
        }
    }

    /** A câmera de ré abriu/fechou (visto pelo Controle). */
    fun re(engatada: Boolean) {
        val ctx = appCtx ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (engatada) {
            val atual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val baixo = (max * 0.3).toInt()
            if (atual > baixo) {
                volumeAntesRe = atual
                am.setStreamVolume(AudioManager.STREAM_MUSIC, baixo, 0)
            }
            avisar("Ré engatada.")
        } else if (volumeAntesRe >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, volumeAntesRe, 0)
            volumeAntesRe = -1
        }
    }
}
