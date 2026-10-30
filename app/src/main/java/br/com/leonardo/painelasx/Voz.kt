package br.com.leonardo.painelasx

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** A voz do app: responde falando, em portugues do Brasil. */
object Voz {
    private var tts: TextToSpeech? = null
    private var pronta = false
    private var pendente: String? = null

    fun iniciar(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("pt", "BR"))
                pronta = true
                pendente?.let { falar(it) }
                pendente = null
            }
        }
    }

    fun falar(texto: String) {
        if (!pronta) {
            pendente = texto
            return
        }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "painel")
    }
}
