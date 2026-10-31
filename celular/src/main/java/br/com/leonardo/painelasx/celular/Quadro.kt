package br.com.leonardo.painelasx.celular

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * Protocolo celular <-> central (IGUAL ao do app da central, em app/.../Quadro.kt):
 * cada mensagem = 4 bytes (tamanho, big-endian) + JSON em UTF-8, no máximo 256 KB.
 * O mesmo protocolo vale para os dois caminhos: rede (TCP) e cabo (AOA).
 */

/** Um caminho de bytes até a central: rede (TCP pela ancoragem Bluetooth) ou cabo (AOA). */
interface Canal {
    val tipo: String
    /** Lê até [n] bytes em [buf]. -1 = fechou; 0 = só passou o tempo, nada chegou. */
    fun ler(buf: ByteArray, n: Int): Int
    /** Escreve tudo (lança IOException se caiu). */
    fun escrever(b: ByteArray)
    fun fechar()
}

object Quadro {
    const val MAX = 256 * 1024

    fun montar(json: String): ByteArray {
        val d = json.toByteArray(Charsets.UTF_8)
        if (d.isEmpty() || d.size > MAX) throw IOException("mensagem grande demais (${d.size} bytes)")
        val b = ByteArray(4 + d.size)
        b[0] = (d.size ushr 24).toByte()
        b[1] = (d.size ushr 16).toByte()
        b[2] = (d.size ushr 8).toByte()
        b[3] = d.size.toByte()
        System.arraycopy(d, 0, b, 4, d.size)
        return b
    }

    /** Junta os pedaços que chegam e entrega cada mensagem inteira. */
    class Juntador {
        private var buf = ByteArray(16 * 1024)
        private var tam = 0

        fun alimentar(dados: ByteArray, n: Int, cada: (String) -> Unit) {
            if (tam + n > buf.size) buf = buf.copyOf(maxOf(tam + n, buf.size * 2))
            System.arraycopy(dados, 0, buf, tam, n)
            tam += n
            var pos = 0
            while (tam - pos >= 4) {
                val len = ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
                if (len <= 0 || len > MAX) throw IOException("tamanho inválido ($len)")
                if (tam - pos - 4 < len) break
                val s = String(buf, pos + 4, len, Charsets.UTF_8)
                pos += 4 + len
                cada(s)
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, tam - pos)
                tam -= pos
            }
            if (buf.size > 64 * 1024 && tam < 16 * 1024) buf = buf.copyOf(16 * 1024)
        }
    }
}

/**
 * Uma conversa aberta: a linha que chama [rodar] fica LENDO; uma linha própria ESCREVE (fila).
 * [pingCadaMs] > 0 manda {"t":"ping"} nesse intervalo. Sem receber nada por [quedaMs] = caiu.
 */
class Conexao(
    val canal: Canal,
    private val pingCadaMs: Long,
    private val quedaMs: Long,
    private val aoChegar: (Conexao, JSONObject) -> Unit,
    private val aoCair: (Conexao, String) -> Unit
) {
    private val fila = LinkedBlockingQueue<ByteArray>(64)
    private val fechada = AtomicBoolean(false)
    /** Id da central (lado do celular) ou outra etiqueta. */
    @Volatile var id = ""
    @Volatile var ultimoRecebido = SystemClock.elapsedRealtime()
        private set
    val viva get() = !fechada.get()

    /** Coloca na fila de envio. Devolve false se a conexão caiu. */
    fun mandar(o: JSONObject): Boolean {
        if (fechada.get()) return false
        val q = try { Quadro.montar(o.toString()) } catch (_: Exception) { return false }
        if (!fila.offer(q)) { fechar("fila de envio cheia"); return false }
        return true
    }

    fun fechar(motivo: String) {
        if (!fechada.compareAndSet(false, true)) return
        try { canal.fechar() } catch (_: Exception) { }
        fila.clear()
        fila.offer(ByteArray(0))   // acorda a linha de escrita
        try { aoCair(this, motivo) } catch (_: Exception) { }
    }

    /** Lê nesta linha até cair; a escrita roda numa linha própria. */
    fun rodar(nome: String) {
        Thread({ escrever() }, "$nome-escrever").apply { isDaemon = true }.start()
        val buf = ByteArray(16384)
        val j = Quadro.Juntador()
        try {
            while (!fechada.get()) {
                val n = canal.ler(buf, buf.size)
                if (n < 0) { fechar("o outro lado fechou"); break }
                if (n == 0) continue
                ultimoRecebido = SystemClock.elapsedRealtime()
                j.alimentar(buf, n) { s ->
                    val o = JSONObject(s)   // texto que não é JSON = fecha (protege de lixo no canal)
                    try { aoChegar(this, o) } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            fechar("leitura: ${e.javaClass.simpleName}")
        }
    }

    private fun escrever() {
        var ultimoPing = SystemClock.elapsedRealtime()
        try {
            while (!fechada.get()) {
                val b = fila.poll(1, TimeUnit.SECONDS)
                if (fechada.get()) break
                if (b != null && b.isNotEmpty()) canal.escrever(b)
                val agora = SystemClock.elapsedRealtime()
                if (pingCadaMs > 0 && agora - ultimoPing >= pingCadaMs) {
                    ultimoPing = agora
                    canal.escrever(Quadro.montar("{\"t\":\"ping\"}"))
                }
                if (agora - ultimoRecebido > quedaMs) { fechar("sem resposta há ${quedaMs / 1000} s"); break }
            }
        } catch (e: Exception) {
            fechar("escrita: ${e.javaClass.simpleName}")
        }
    }
}
