package br.com.leonardo.painelasx.celular

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject

/**
 * "Ouvir": o carro pede, o celular ouve com o reconhecimento de voz do Google (pt-BR) e manda só o TEXTO.
 * Se não der (microfone não liberado, sem reconhecedor, erro), responde com "erro" e o carro usa o ouvido dele.
 * Tudo aqui roda na linha principal (exigência do SpeechRecognizer).
 */
object Escuta {
    private var sr: SpeechRecognizer? = null
    private var tentouNoAparelho = false
    private val GOOGLE = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    )

    private val tempoEsgotado = Runnable { terminar(JSONObject().put("t", "ouvi").put("erro", "tempo esgotado")) }

    fun ouvir(ctx: Context) {
        parar()
        tentouNoAparelho = false
        when {
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                responderErro("microfone não permitido no celular")
            !Ponte.comMicrofone -> {
                Ponte.avisarMicrofone(ctx)
                responderErro("microfone bloqueado (abra o app no celular uma vez)")
            }
            !SpeechRecognizer.isRecognitionAvailable(ctx) -> responderErro("sem reconhecimento de voz no celular")
            else -> comecar(ctx, false)
        }
    }

    private fun temGoogle(ctx: Context) = try {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .any { it.serviceInfo.packageName == GOOGLE.packageName && it.serviceInfo.name == GOOGLE.className }
    } catch (_: Exception) { false }

    private fun comecar(ctx: Context, noAparelho: Boolean) {
        val r = try {
            when {
                noAparelho && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                temGoogle(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx, GOOGLE)
                else -> SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (e: Exception) {
            responderErro("reconhecedor não abriu"); return
        }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { Ponte.enviar(JSONObject().put("t", "ouvindo")) }
            override fun onResults(res: Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                terminar(if (t.isEmpty()) JSONObject().put("t", "ouvi").put("erro", "não entendi")
                         else JSONObject().put("t", "ouvi").put("texto", t))
            }
            override fun onError(e: Int) {
                // Erro 9 (permissão) num serviço: tenta uma vez o reconhecedor do próprio aparelho, se existir.
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && !tentouNoAparelho &&
                    Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                    tentouNoAparelho = true
                    soltar()
                    comecar(ctx, true)
                    return
                }
                terminar(JSONObject().put("t", "ouvi").put("erro", "erro $e (${nomeDoErro(e)})"))
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        try {
            r.startListening(i)
            Ponte.h.removeCallbacks(tempoEsgotado)
            Ponte.h.postDelayed(tempoEsgotado, 15_000)
        } catch (e: Exception) {
            responderErro("não consegui começar a ouvir")
        }
    }

    private fun nomeDoErro(e: Int) = when (e) {
        1, 2 -> "sem internet"
        3 -> "áudio"
        4, 11 -> "servidor"
        5 -> "cliente"
        6 -> "ninguém falou"
        7 -> "não entendi"
        8 -> "ocupado"
        9 -> "sem permissão do microfone"
        10 -> "pedidos demais"
        12, 13 -> "idioma indisponível"
        else -> "desconhecido"
    }

    private fun responderErro(motivo: String) {
        Ponte.enviar(JSONObject().put("t", "ouvi").put("erro", motivo))
    }

    private fun terminar(resposta: JSONObject) {
        Ponte.h.removeCallbacks(tempoEsgotado)
        if (sr == null) return
        soltar()
        Ponte.enviar(resposta)
    }

    private fun soltar() {
        try { sr?.destroy() } catch (_: Exception) { }
        sr = null
    }

    /** O carro desistiu (ou a conexão caiu). */
    fun parar() {
        Ponte.h.removeCallbacks(tempoEsgotado)
        try { sr?.cancel() } catch (_: Exception) { }
        soltar()
    }
}
