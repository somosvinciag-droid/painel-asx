package br.com.leonardo.painelasx

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Caminho 1 (fase 1): TCP pela rede da ancoragem Bluetooth (a central é cliente; o celular é o gateway). */
class CanalRede(private val s: Socket) : Canal {
    override val tipo = "rede"
    private val ent = s.getInputStream()
    private val sai = s.getOutputStream()
    override fun ler(buf: ByteArray, n: Int): Int = try { ent.read(buf, 0, n) } catch (_: SocketTimeoutException) { 0 }
    override fun escrever(b: ByteArray) { sai.write(b); sai.flush() }
    override fun fechar() { try { s.close() } catch (_: Exception) { } }
}

/**
 * Ligação da central com o app "Painel ASX Celular".
 *  - Fase 1 (padrão): descobre o celular pelo gateway da rede Bluetooth (ancoragem) e conecta na porta 48110.
 *  - Fase 2 (interruptor "Cabo: Painel ASX", desligado por padrão): o CaboUsb entrega um canal pelo cabo (AOA),
 *    que tem prioridade sobre a rede.
 * Reconecta sozinho; ping a cada 5 s; sem resposta por 15 s = caiu. Uma linha lê, outra escreve.
 */
object CaboCelular {
    const val PORTA = 48110
    private const val SEM_BT = "sem rede Bluetooth (ancoragem do celular desligada?)"

    private val rodando = AtomicBoolean(false)
    private val caboPronto = AtomicReference<Canal?>(null)
    private val sino = Object()
    @Volatile private var conexao: Conexao? = null
    @Volatile private var pausadoAte = 0L
    @Volatile var estado = "desligado"
        private set

    fun prefs(ctx: Context) = ctx.getSharedPreferences("celular", Context.MODE_PRIVATE)

    /** Id aleatório desta central (gerado aqui; o celular guarda depois do "Permitir"). Fica fora do backup. */
    @Synchronized
    fun idCentral(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("id", null)?.let { return it }
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        p.edit().putString("id", id).apply()
        return id
    }

    /** Código curto que o celular mostra no aviso "Permitir o Painel ASX do carro?". */
    fun codigo(ctx: Context) = idCentral(ctx).take(4).uppercase()

    fun iniciar(ctx: Context) {
        val app = ctx.applicationContext
        try { CaboUsb.preparar(app) } catch (e: Throwable) { Registro.anotar("ERRO cabo USB: ${e.javaClass.simpleName}: ${e.message}") }
        if (!rodando.compareAndSet(false, true)) return
        Thread({
            try { laco(app) } catch (t: Throwable) {
                Registro.anotar("ERRO celular parou: ${t.javaClass.simpleName}: ${t.message}")
            } finally { rodando.set(false) }
        }, "celular").start()
    }

    /** Manda ao celular (só depois de ele aceitar esta central). */
    fun mandar(o: JSONObject): Boolean {
        val c = conexao ?: return false
        if (!c.viva || !Celular.ligado) return false
        return c.mandar(o)
    }

    /** O cabo (AOA) abriu um canal: ele assume no lugar da rede. */
    fun entregarCabo(canal: Canal) {
        caboPronto.getAndSet(canal)?.fechar()
        conexao?.fechar("o cabo assumiu")
        acordar()
    }

    /** O celular do cabo foi solto: fecha na hora (sem esperar o ping). */
    fun soltouCabo(nomeAparelho: String) {
        val c = conexao ?: return
        if ((c.canal as? CanalAoa)?.nomeAparelho == nomeAparelho) c.fechar("cabo solto")
    }

    /** O celular recusou esta central: para de insistir por 10 min. */
    fun pausar(ms: Long) { pausadoAte = SystemClock.elapsedRealtime() + ms }

    fun acordar() = synchronized(sino) { sino.notifyAll() }

    private fun esperar(ms: Long) = synchronized(sino) { try { sino.wait(ms) } catch (_: InterruptedException) { } }

    private fun mudarEstado(e: String) {
        if (e == estado) return
        estado = e
        Registro.anotar("celular: $e")
    }

    private fun laco(ctx: Context) {
        var espera = 5_000L
        while (true) {
            var canal: Canal? = caboPronto.getAndSet(null)
            if (canal == null && SystemClock.elapsedRealtime() >= pausadoAte) canal = conectarRede(ctx)
            if (canal == null) {
                esperar(if (estado == SEM_BT || SystemClock.elapsedRealtime() < pausadoAte) 10_000 else espera)
                espera = (espera * 2).coerceAtMost(30_000)
                continue
            }
            val inicio = SystemClock.elapsedRealtime()
            val c = Conexao(canal, 5_000, 15_000, { cx, o -> Celular.receber(ctx, cx, o) }, { cx, m -> caiu(cx, m) })
            conexao = c
            mudarEstado("conectado pela ${canal.tipo}, esperando o olá do celular")
            c.mandar(JSONObject().put("t", "ola").put("proto", 1).put("app", BuildConfig.VERSION_CODE)
                .put("id", idCentral(ctx)).put("nome", "Painel ASX do ASX").put("mensagens", Celular.lerMensagens(ctx)))
            c.rodar("celular")
            conexao = null
            if (caboPronto.get() != null) continue
            // Conexão que cai logo (algo aceita e fecha na hora): espera cada vez mais (até 60 s),
            // para não ficar reconectando de 2 em 2 s e enchendo o registro.
            if (SystemClock.elapsedRealtime() - inicio < 20_000) {
                esperar(espera)
                espera = (espera * 2).coerceAtMost(60_000)
            } else {
                espera = 5_000L
                esperar(2_000)
            }
        }
    }

    private fun caiu(c: Conexao, motivo: String) {
        if (conexao !== c && conexao != null) return
        Celular.caiu(motivo)
        mudarEstado("caiu ($motivo)")
    }

    /** Gateway (o celular) da rede Bluetooth da central. */
    private fun acharCelularNaRede(ctx: Context): Pair<Network, InetAddress>? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val lp = cm.getLinkProperties(n) ?: continue
            val nome = lp.interfaceName ?: ""
            val bt = caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) || nome.startsWith("bt-pan") || nome.startsWith("bnep")
            if (!bt) continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }?.gateway
                ?: lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { primeiroDaRede(it) }
            if (gw != null) return n to gw
        }
        return null
    }

    /** x.y.z.1 da rede (reserva, se a rota não disser o gateway). */
    private fun primeiroDaRede(la: LinkAddress): InetAddress? = try {
        val a = la.address.address
        val p = la.prefixLength.coerceIn(8, 30)
        val ip = ((a[0].toInt() and 0xFF) shl 24) or ((a[1].toInt() and 0xFF) shl 16) or ((a[2].toInt() and 0xFF) shl 8) or (a[3].toInt() and 0xFF)
        val rede = ip and ((-1) shl (32 - p))
        val g = rede + 1
        InetAddress.getByAddress(byteArrayOf((g ushr 24).toByte(), (g ushr 16).toByte(), (g ushr 8).toByte(), g.toByte()))
    } catch (_: Exception) { null }

    private fun conectarRede(ctx: Context): Canal? {
        val alvo = try { acharCelularNaRede(ctx) } catch (_: Exception) { null }
        if (alvo == null) { mudarEstado(SEM_BT); return null }
        val (rede, gw) = alvo
        val s = try { rede.socketFactory.createSocket() } catch (_: Exception) { return null }
        return try {
            s.connect(InetSocketAddress(gw, PORTA), 4000)
            s.soTimeout = 1000
            s.tcpNoDelay = true
            s.keepAlive = true
            CanalRede(s)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) { }
            mudarEstado("o celular (${gw.hostAddress}) não respondeu: app do celular fechado ou sem permissão de bateria?")
            null
        }
    }
}
