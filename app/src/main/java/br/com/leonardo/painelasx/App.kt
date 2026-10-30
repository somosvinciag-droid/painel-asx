package br.com.leonardo.painelasx

import android.app.Application
import android.util.Log

/**
 * Comeca antes de tudo. Se o app quebrar em qualquer parte, o motivo fica
 * anotado no Registro (aparece em "Ver diagnóstico na tela").
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
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
    }
}
