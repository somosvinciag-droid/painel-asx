package br.com.leonardo.painelasx

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Caderninho do app: guarda as ultimas linhas do que aconteceu
 * (telas que a central abriu, comandos, atualizacoes). Vai no diagnostico.
 */
object Registro {
    private const val MAX = 300
    private val linhas = ArrayDeque<String>()
    private var arquivo: File? = null
    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR"))

    @Synchronized
    fun iniciar(ctx: Context) {
        if (arquivo != null) return
        val f = File(ctx.filesDir, "registro.txt")
        arquivo = f
        if (f.exists()) {
            f.readLines().takeLast(MAX).forEach { linhas.addLast(it) }
        }
    }

    /** Linhas que ainda nao foram mandadas para o conector (o Claude le de la). */
    private val paraEnviar = ArrayDeque<String>()

    @Synchronized
    fun pegarParaEnviar(): List<String> {
        val l = paraEnviar.toList()
        paraEnviar.clear()
        return l
    }

    @Synchronized
    fun devolverParaEnviar(l: List<String>) {
        l.asReversed().forEach { paraEnviar.addFirst(it) }
        while (paraEnviar.size > MAX) paraEnviar.removeFirst()
    }

    @Synchronized
    fun anotar(texto: String) {
        val linha = hora.format(Date()) + "  " + texto
        if (linhas.lastOrNull()?.endsWith(texto) == true) return
        linhas.addLast(linha)
        while (linhas.size > MAX) linhas.removeFirst()
        paraEnviar.addLast(linha)
        while (paraEnviar.size > MAX) paraEnviar.removeFirst()
        try {
            arquivo?.writeText(linhas.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun ultimas(n: Int): List<String> = linhas.toList().takeLast(n)
}
