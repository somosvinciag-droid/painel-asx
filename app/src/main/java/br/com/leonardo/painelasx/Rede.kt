package br.com.leonardo.painelasx

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Pedidos simples à internet (sempre fora da linha principal). */
object Rede {
    val principal = Handler(Looper.getMainLooper())

    fun get(url: String, ms: Int = 12_000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = ms
        c.readTimeout = ms
        c.setRequestProperty("User-Agent", "painel-asx/${BuildConfig.VERSION_CODE} (central do carro)")
        c.setRequestProperty("Accept-Language", "pt-BR")
        val code = c.responseCode
        val t = if (code in 200..299) c.inputStream.bufferedReader().readText() else null
        c.disconnect()
        t
    } catch (_: Exception) {
        null
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun emSegundoPlano(nome: String, bloco: () -> Unit) {
        Thread({
            try {
                bloco()
            } catch (e: Throwable) {
                Registro.anotar("ERRO $nome: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, nome).start()
    }

    fun naTela(bloco: () -> Unit) = principal.post {
        try { bloco() } catch (e: Throwable) { Registro.anotar("ERRO na tela: ${e.javaClass.simpleName}: ${e.message}") }
    }
}
