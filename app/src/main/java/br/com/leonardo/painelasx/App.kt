package br.com.leonardo.painelasx

import android.app.Application
import android.util.Log

/**
 * Comeca antes de tudo. Se o app quebrar em qualquer parte, o motivo fica
 * anotado no Registro (aparece em "Ver diagnóstico na tela").
 */
class App : Application() {
    companion object {
        /** Quando este processo começou (para saber se a central acabou de ligar/acordar e nos abriu). */
        @Volatile var inicio = 0L
    }

    override fun onCreate() {
        super.onCreate()
        inicio = android.os.SystemClock.elapsedRealtime()
        Registro.iniciar(this)
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, erro ->
            try {
                val onde = erro.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
                Registro.anotar("QUEBROU (${th.name}): ${erro.javaClass.simpleName}: ${erro.message} @ $onde")
                Registro.gravarAgora()
            } catch (_: Throwable) {
                Log.e("PainelASX", "falha ao anotar erro", erro)
            }
            anterior?.uncaughtException(th, erro)
        }
        // A saudação é a PRIMEIRA coisa (antes de serviço, tela, voz do Android, GPS...): ela toca o áudio
        // já gravado numa linha própria e não depende de nada disso.
        try { Jarvis.talvezPartidaNoInicio(this) } catch (e: Throwable) { Registro.anotar("ERRO partida no início: ${e.message}") }
        // Mede quanto tempo a linha principal fica ocupada com a inicialização (para conferir na central).
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Registro.anotar("inicio: linha principal livre ${android.os.SystemClock.elapsedRealtime() - inicio} ms")
        }
    }
}

/** Roda [bloco] e anota quanto tempo levou ("inicio: <etapa> X ms"). Um erro fica anotado e não derruba o resto. */
fun medirInicio(etapa: String, bloco: () -> Unit) {
    val t = android.os.SystemClock.elapsedRealtime()
    try {
        bloco()
    } catch (e: Throwable) {
        Registro.anotar("ERRO inicio: $etapa: ${e.javaClass.simpleName}: ${e.message}")
    } finally {
        Registro.anotar("inicio: $etapa ${android.os.SystemClock.elapsedRealtime() - t} ms")
    }
}
