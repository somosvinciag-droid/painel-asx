package br.com.leonardo.painelasx

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

/**
 * Pedidos USB assíncronos de UMA conexão (um aparelho). O "colher" de um pedido pronto (requestWait) pega QUALQUER
 * pedido da conexão, então quem lê precisa de regras comuns:
 *  - [trava]: só uma leitura pede/espera por vez;
 *  - [colhidos]: pedido de OUTRA leitura que alguém colheu (ex.: a conversa velha cancelada e a nova no mesmo cabo):
 *    fica aqui para o dono achar (os bytes já estão no buffer dele);
 *  - [orfaos]: pedidos que podem ainda estar no kernel quando o dono fechou. Precisam continuar vivos (o kernel escreve
 *    neles ao colher) até a conexão fechar; [fechouConexao] solta tudo depois do `close()` da conexão.
 */
class FilaUsb {
    val trava = ReentrantLock()
    val colhidos: MutableSet<UsbRequest> = Collections.newSetFromMap(IdentityHashMap())
    private val orfaos = ArrayList<UsbRequest>()

    fun orfao(r: UsbRequest) = synchronized(orfaos) { orfaos.add(r) }

    /** Chamar DEPOIS do close() da conexão (o kernel já soltou os pedidos dela). */
    fun fechouConexao() {
        val todos = synchronized(orfaos) { ArrayList(orfaos).also { orfaos.clear() } }
        val outros = trava.withLockOrNull(500) { ArrayList(colhidos).also { colhidos.clear() } } ?: emptyList()
        for (r in todos + outros) try { r.close() } catch (_: Throwable) { }
    }

    private inline fun <T> ReentrantLock.withLockOrNull(ms: Long, f: () -> T): T? {
        if (!tryLock(ms, TimeUnit.MILLISECONDS)) return null
        try { return f() } finally { unlock() }
    }
}

/**
 * Leitura do Android Auto pelo cabo com um pedido USB assíncrono (UsbRequest) no lugar do bulkTransfer.
 *
 * Por quê (quedas de 02/11 18:20, 18:20, 18:24 e 18:25: "quadro aberto depois do TLS ignorado (canal 136/88/19)" e logo
 * "SSLException: Unable to parse TLS packet header", sempre com "leituras com erro na hora" na mesma conversa): no Linux
 * da central (usbfs), um bulkTransfer de LEITURA que volta com erro (ou com o prazo esgotado) JOGA FORA os bytes que já
 * tinham chegado naquela transferência (proc_bulk só copia para o app quando deu tudo certo). Um pedaço perdido no meio
 * do fluxo desalinha os quadros do Android Auto e a numeração do TLS, e a conversa cai sem volta.
 * Com o pedido assíncrono: (1) o prazo da espera não cancela nada (o pedido continua na fila e o que chegar depois é
 * entregue na próxima leitura); (2) quando o pedido termina com erro, os bytes que já tinham chegado são entregues
 * (o kernel copia ao colher, com ou sem erro). Um pacote que deu erro de transmissão o celular manda de novo (o USB não
 * o confirmou).
 *
 * Sem Android de verdade aqui (testado na JVM com um cabo falso). [iniciar] false = sem pedido assíncrono: quem chama
 * usa o bulkTransfer de sempre.
 */
class LeitorUsb(
    private val con: UsbDeviceConnection,
    private val entrada: UsbEndpoint,
    private val fila: FilaUsb,
    tamanho: Int = 16384,
    /** Avisos para o registro da conversa ("erro", "vazio"...): só contagem e hora, nada de conteúdo. */
    private val aviso: (String) -> Unit = {}
) {
    private val tam: Int = run {
        val mps = entrada.maxPacketSize.let { if (it in 8..tamanho) it else 512 }
        maxOf(mps, tamanho - tamanho % mps)   // múltiplo do pacote USB (pedido que não é múltiplo pode "transbordar")
    }
    private val bb: ByteBuffer = ByteBuffer.allocateDirect(tam)
    private var pedido: UsbRequest? = null
    /** O pedido está no kernel (só mexer com a [FilaUsb.trava]). */
    private var naFila = false
    private val resto = ByteArray(tam)
    private var restoIni = 0
    private var restoFim = 0
    @Volatile private var fechado = false
    private var vaziosSeguidos = 0

    @Volatile var errosNaHora = 0
        private set
    @Volatile var vazios = 0
        private set
    @Volatile var deOutros = 0
        private set

    fun iniciar(): Boolean = try {
        val r = UsbRequest()
        if (r.initialize(con, entrada)) { pedido = r; true } else { try { r.close() } catch (_: Throwable) { }; false }
    } catch (_: Throwable) { false }

    /** Até [n] bytes em [buf]. 0 = nada no prazo (ou erro do USB: um respiro já foi dado); -1 = fechado. */
    fun ler(buf: ByteArray, n: Int, esperaMs: Int): Int {
        if (fechado) return -1
        if (n <= 0) return 0
        if (restoIni < restoFim) return tirarResto(buf, n)
        val p = pedido ?: return -1
        val t0 = System.nanoTime()
        val espera = esperaMs.coerceAtLeast(1).toLong()
        if (!fila.trava.tryLock(espera, TimeUnit.MILLISECONDS)) return 0
        try {
            if (fechado) return -1
            if (!naFila) {
                bb.clear()
                val ok = try { p.queue(bb) } catch (_: Throwable) { false }
                if (!ok) { erro("pedido recusado"); return if (fechado) -1 else 0 }
                naFila = true
            }
            while (true) {
                if (!fila.colhidos.remove(p)) {
                    val resta = espera - (System.nanoTime() - t0) / 1_000_000
                    if (resta <= 0) return 0
                    val r = try { con.requestWait(resta) } catch (_: TimeoutException) { return 0 } catch (_: Throwable) { null }
                    if (r == null) {
                        // Voltou sem pedido antes do prazo: cabo solto (o Android avisa erro na espera) ou interrupção.
                        if ((System.nanoTime() - t0) / 1_000_000 < espera / 2) erro("espera falhou")
                        return if (fechado) -1 else 0
                    }
                    if (r !== p) { deOutros++; fila.colhidos.add(r); continue }   // de outra leitura: o dono pega depois
                }
                naFila = false
                if (fechado) return -1
                val k = bb.position()
                if (k <= 0) {
                    // Pacote vazio (fim de uma escrita do celular do tamanho exato) ou erro sem nenhum byte.
                    vazios++
                    if (++vaziosSeguidos >= 3 && (System.nanoTime() - t0) / 1_000_000 < espera / 2) {
                        if (vaziosSeguidos == 3) aviso("vazios seguidos")
                        try { Thread.sleep(20) } catch (_: InterruptedException) { }
                    }
                    return 0
                }
                vaziosSeguidos = 0
                bb.flip()
                val m = minOf(k, n)
                bb.get(buf, 0, m)
                if (k > m) { restoIni = 0; restoFim = k - m; bb.get(resto, 0, restoFim) }
                return m
            }
        } finally {
            fila.trava.unlock()
        }
    }

    private fun tirarResto(buf: ByteArray, n: Int): Int {
        val m = minOf(n, restoFim - restoIni)
        System.arraycopy(resto, restoIni, buf, 0, m)
        restoIni += m
        if (restoIni >= restoFim) { restoIni = 0; restoFim = 0 }
        return m
    }

    private fun erro(o: String) {
        errosNaHora++
        aviso(o)
        try { Thread.sleep(50) } catch (_: InterruptedException) { }
    }

    /**
     * Cancela o pedido e tenta colhê-lo (se a leitura não estiver esperando agora; senão ela mesma colhe). Pedido que
     * pode ainda estar no kernel vira órfão da [FilaUsb] (fica vivo até a conexão fechar): soltar antes seria perigoso.
     */
    fun fechar() {
        if (fechado) return
        fechado = true
        val p = pedido ?: return
        try { p.cancel() } catch (_: Throwable) { }
        if (!fila.trava.tryLock(1_500, TimeUnit.MILLISECONDS)) { fila.orfao(p); return }
        try {
            if (naFila) {
                val fim = System.nanoTime() + 500_000_000L
                while (naFila && System.nanoTime() < fim) {
                    if (fila.colhidos.remove(p)) { naFila = false; break }
                    val r = try { con.requestWait(100) } catch (_: TimeoutException) { null } catch (_: Throwable) { null }
                    if (r === p) naFila = false
                    else if (r != null) fila.colhidos.add(r)
                    else try { Thread.sleep(10) } catch (_: InterruptedException) { break }   // cabo solto: não roda em falso
                }
            }
            if (naFila) fila.orfao(p) else try { p.close() } catch (_: Throwable) { }
        } finally {
            fila.trava.unlock()
        }
    }
}
