package br.com.leonardo.painelasx.celular

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** Caminho 1 (fase 1): TCP. */
class CanalRede(private val s: Socket) : Canal {
    override val tipo = "rede"
    private val ent = s.getInputStream()
    private val sai = s.getOutputStream()
    override fun ler(buf: ByteArray, n: Int): Int = try { ent.read(buf, 0, n) } catch (_: SocketTimeoutException) { 0 }
    override fun escrever(b: ByteArray) { sai.write(b); sai.flush() }
    override fun fechar() { try { s.close() } catch (_: Exception) { } }
}

/**
 * Servidor TCP só na interface da ancoragem Bluetooth (bt-pan, normalmente 192.168.44.1).
 * Aceita só clientes da mesma sub-rede (a central ligada pela ancoragem). Nada de Wi-Fi nem dados móveis.
 */
class ServidorRede(private val aoConectar: (Canal, String) -> Unit) {
    companion object {
        const val PORTA = 48110
    }

    @Volatile var estado = "ligando"
        private set
    @Volatile var ancoragemLigada = false
        private set
    @Volatile private var parar = false
    @Volatile private var ss: ServerSocket? = null

    private data class Iface(val nome: String, val ip: InetAddress, val prefixo: Int)

    fun iniciar() {
        Thread({ laco() }, "servidor-rede").apply { isDaemon = true }.start()
    }

    fun encerrar() {
        parar = true
        try { ss?.close() } catch (_: Exception) { }
    }

    /** A interface da ancoragem Bluetooth deste celular (bt-pan), se estiver ligada. */
    private fun acharBtPan(): Iface? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && it.name.startsWith("bt-pan") }
            .firstNotNullOfOrNull { ni ->
                ni.interfaceAddresses.firstOrNull { it.address is Inet4Address }
                    ?.let { Iface(ni.name, it.address, it.networkPrefixLength.toInt()) }
            }
    } catch (_: Exception) {
        null
    }

    private fun mesmaRede(a: InetAddress, b: InetAddress, prefixo: Int): Boolean {
        val x = a.address; val y = b.address
        if (x.size != 4 || y.size != 4) return false
        val p = prefixo.coerceIn(8, 32)
        val ia = ((x[0].toInt() and 0xFF) shl 24) or ((x[1].toInt() and 0xFF) shl 16) or ((x[2].toInt() and 0xFF) shl 8) or (x[3].toInt() and 0xFF)
        val ib = ((y[0].toInt() and 0xFF) shl 24) or ((y[1].toInt() and 0xFF) shl 16) or ((y[2].toInt() and 0xFF) shl 8) or (y[3].toInt() and 0xFF)
        val mascara = if (p == 32) -1 else ((-1) shl (32 - p))
        return (ia and mascara) == (ib and mascara)
    }

    private fun laco() {
        while (!parar) {
            val iface = acharBtPan()
            ancoragemLigada = iface != null
            if (iface == null) {
                estado = "ancoragem Bluetooth desligada"
                dormir(15_000)
                continue
            }
            val servidor = try {
                ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 15_000
                    bind(InetSocketAddress(iface.ip, PORTA), 2)
                }
            } catch (e: Exception) {
                estado = "não abri a porta $PORTA (${e.javaClass.simpleName})"
                dormir(15_000)
                continue
            }
            ss = servidor
            estado = "esperando o carro em ${iface.ip.hostAddress}"
            try {
                while (!parar) {
                    val s = try { servidor.accept() } catch (_: SocketTimeoutException) {
                        // De tempos em tempos: a ancoragem continua a mesma?
                        val agora = acharBtPan()
                        if (agora == null || agora.ip != iface.ip) break
                        continue
                    }
                    if (!mesmaRede(s.inetAddress, iface.ip, iface.prefixo)) {
                        try { s.close() } catch (_: Exception) { }
                        continue
                    }
                    try {
                        s.soTimeout = 1000
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        aoConectar(CanalRede(s), s.inetAddress.hostAddress ?: "")
                    } catch (_: Exception) {
                        try { s.close() } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { servidor.close() } catch (_: Exception) { }
                ss = null
            }
        }
    }

    private fun dormir(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { }
}

/** Caminho 2: o celular como acessório USB (AOA), o caminho principal desde 31/10. */
class CanalAcessorio(private val pfd: ParcelFileDescriptor) : Canal {
    override val tipo = "cabo"
    private val ent = FileInputStream(pfd.fileDescriptor)
    private val sai = FileOutputStream(pfd.fileDescriptor)
    override fun ler(buf: ByteArray, n: Int): Int = ent.read(buf, 0, minOf(n, 16384))
    override fun escrever(b: ByteArray) {
        var pos = 0
        while (pos < b.size) {
            val n = minOf(16384, b.size - pos)
            sai.write(b, pos, n)
            pos += n
        }
        sai.flush()
    }
    override fun fechar() {
        try { pfd.close() } catch (_: Exception) { }
    }
}

/**
 * Tela invisível: o Android a abre quando a central pede o modo acessório pelo cabo.
 * Aparece por cima do bloqueio e acende a tela (manifesto). A Ponte é ligada no onResume, quando a tela está de fato
 * "na frente": é a chance que o Android 14+ dá para a Ponte ligar com o microfone, sem o Leonardo abrir o app.
 * Se ela não chegar à frente em 1,2 s (tela não acendeu), liga a Ponte assim mesmo (o cabo funciona; o microfone,
 * se o Android recusar, fica para o ouvido da central — plano B dentro de Ponte.primeiroPlano).
 * Não recebe toques (o Leonardo pode estar mexendo no celular) e fecha sozinha.
 */
class AcessorioActivity : Activity() {
    private var acc: UsbAccessory? = null
    private var ligou = false
    private val reserva = Runnable { ligarPonte() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        acc = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.decorView.postDelayed(reserva, 1200)
    }

    override fun onResume() {
        super.onResume()
        ligarPonte()
    }

    private fun ligarPonte() {
        if (ligou) return
        ligou = true
        window.decorView.removeCallbacks(reserva)
        Ponte.iniciar(this, comMicrofone = true, acessorio = acc)
        // Fica na frente mais 1,5 s: o serviço liga (com o microfone) enquanto o app ainda está "na frente".
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 1500)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        finish()
    }
}
