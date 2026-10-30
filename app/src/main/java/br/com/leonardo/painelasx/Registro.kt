package br.com.leonardo.painelasx

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caderninho do app: guarda as ultimas linhas do que aconteceu
 * (telas que a central abriu, comandos, atualizacoes, erros). Vai para o diagnostico e para o Claude.
 *
 * Versao leve: gravar no arquivo acontece fora da tela principal e no maximo
 * a cada 5 segundos (antes gravava o arquivo inteiro a cada linha).
 */
object Registro {
    private const val MAX = 300
    private val linhas = ArrayDeque<String>()
    private var arquivo: File? = null
    private val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale("pt", "BR"))
    private val gravador = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "registro").apply { isDaemon = true } }
    private val gravacaoMarcada = AtomicBoolean(false)

    /** Linhas que ainda nao foram mandadas para o conector (o Claude le de la). */
    private val paraEnviar = ArrayDeque<String>()

    @Synchronized
    fun iniciar(ctx: Context) {
        if (arquivo != null) return
        val f = File(ctx.filesDir, "registro.txt")
        arquivo = f
        try {
            if (f.exists()) f.readLines().takeLast(MAX).forEach { linhas.addLast(it) }
        } catch (_: Exception) {
        }
    }

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
        if (linhas.lastOrNull()?.endsWith(texto) == true) return
        val linha = hora.format(Date()) + "  " + texto
        linhas.addLast(linha)
        while (linhas.size > MAX) linhas.removeFirst()
        paraEnviar.addLast(linha)
        while (paraEnviar.size > MAX) paraEnviar.removeFirst()
        marcarGravacao()
    }

    private fun marcarGravacao() {
        if (!gravacaoMarcada.compareAndSet(false, true)) return
        gravador.schedule({
            gravacaoMarcada.set(false)
            val copia = synchronized(this) { linhas.joinToString("\n") }
            try {
                arquivo?.writeText(copia)
            } catch (_: Exception) {
            }
        }, 5, TimeUnit.SECONDS)
    }

    @Synchronized
    fun ultimas(n: Int): List<String> = linhas.toList().takeLast(n)

    /** Grava ja (usado quando o app vai quebrar: senao a ultima linha, a do motivo, se perdia). */
    @Synchronized
    fun gravarAgora() {
        try {
            arquivo?.writeText(linhas.joinToString("\n"))
        } catch (_: Exception) {
        }
    }
}
