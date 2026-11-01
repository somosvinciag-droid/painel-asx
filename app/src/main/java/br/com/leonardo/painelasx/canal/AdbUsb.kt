package br.com.leonardo.painelasx.canal

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/*
 * Cliente ADB em Kotlin puro (sem dependências), para a central falar com o celular pelo cabo USB.
 *
 * Protocolo: AOSP system/core/adb/protocol.txt e adb.h (android11-release). Formato da chave pública:
 * libcrypto_utils/android_pubkey.c. A central é o "computador": manda CNXN, assina o token do AUTH com a
 * chave dela e, na primeira vez, manda a chave pública (o celular mostra "Permitir depuração USB?").
 *
 * Testado na JVM contra o adb 34 de verdade (o "adb" do computador conversando com um adbd falso feito com
 * as mesmas classes) e contra vetores (cabeçalho, checksum, chave do "adb keygen", assinatura conferida em Python).
 */

/** Constantes do protocolo (adb.h). */
object Adb {
    const val SYNC = 0x434e5953
    const val CNXN = 0x4e584e43
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val AUTH = 0x48545541
    const val STLS = 0x534c5453

    const val VERSAO = 0x01000001               // A_VERSION (= A_VERSION_SKIP_CHECKSUM)
    const val VERSAO_SEM_CHECKSUM = 0x01000001
    const val MAX_DADOS = 256 * 1024            // o que a central aceita receber por mensagem
    const val MAX_DADOS_V1 = 4096               // CNXN/AUTH antes de saber o maxdata do celular

    const val AUTH_TOKEN = 1
    const val AUTH_ASSINATURA = 2
    const val AUTH_CHAVE_PUBLICA = 3

    // Interface USB do ADB (adb.h: ADB_CLASS/ADB_SUBCLASS/ADB_PROTOCOL)
    const val CLASSE = 0xFF
    const val SUBCLASSE = 0x42
    const val PROTOCOLO = 0x01

    const val CABECALHO = 24
    const val TAMANHO_CHAVE_ANDROID = 4 + 4 + 256 + 256 + 4   // 524 bytes

    fun nome(c: Int): String = String(byteArrayOf(c.toByte(), (c shr 8).toByte(), (c shr 16).toByte(), (c shr 24).toByte()), Charsets.US_ASCII)
}

class AdbErro(msg: String) : IOException(msg)

/** Uma mensagem ADB: cabeçalho de 24 bytes (little-endian) + dados. */
class AdbMensagem(val comando: Int, val arg0: Int, val arg1: Int, val dados: ByteArray = VAZIO) {

    fun cabecalho(): ByteArray = ByteBuffer.allocate(Adb.CABECALHO).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(comando).putInt(arg0).putInt(arg1)
        .putInt(dados.size).putInt(checksum(dados)).putInt(comando xor -1)
        .array()

    override fun toString() = "${Adb.nome(comando)}(${arg0}, ${arg1}, ${dados.size} B)"

    /** Cabeçalho lido do cabo (antes de ler os dados). */
    class Cabecalho(val comando: Int, val arg0: Int, val arg1: Int, val tamanho: Int, val checksum: Int)

    companion object {
        val VAZIO = ByteArray(0)

        /** "data_check": soma simples dos bytes (sem sinal). Desde a versão 0x01000001 quem recebe ignora. */
        fun checksum(d: ByteArray, off: Int = 0, len: Int = d.size): Int {
            var s = 0
            for (i in off until off + len) s += d[i].toInt() and 0xFF
            return s
        }

        fun lerCabecalho(b: ByteArray): Cabecalho {
            if (b.size < Adb.CABECALHO) throw AdbErro("cabeçalho curto")
            val bb = ByteBuffer.wrap(b, 0, Adb.CABECALHO).order(ByteOrder.LITTLE_ENDIAN)
            val c = bb.int; val a0 = bb.int; val a1 = bb.int; val n = bb.int; val ck = bb.int; val magic = bb.int
            if (magic != (c xor -1)) throw AdbErro("cabeçalho inválido (magic)")
            if (n < 0 || n > 16 * 1024 * 1024) throw AdbErro("tamanho inválido ($n)")
            return Cabecalho(c, a0, a1, n, ck)
        }

        /** Lê uma mensagem inteira do transporte. [conferir] = conferir o checksum (versões antigas). */
        fun ler(t: AdbTransporte, conferir: Boolean = false): AdbMensagem {
            val cab = ByteArray(Adb.CABECALHO)
            t.ler(cab, 0, cab.size)
            val h = lerCabecalho(cab)
            val d = if (h.tamanho == 0) VAZIO else ByteArray(h.tamanho).also { t.ler(it, 0, it.size) }
            if (conferir && h.checksum != 0 && checksum(d) != h.checksum) throw AdbErro("checksum errado")
            return AdbMensagem(h.comando, h.arg0, h.arg1, d)
        }
    }
}

/** Meio físico (cabo USB na central; socket TCP nos testes). */
interface AdbTransporte : Closeable {
    /** Escreve UMA mensagem (cabeçalho e dados), sem misturar com outra linha. */
    fun escreverMensagem(m: AdbMensagem)
    /** Lê exatamente [len] bytes (ou lança IOException). */
    fun ler(b: ByteArray, off: Int, len: Int)
}

/** Transporte por streams (TCP nos testes: o mesmo protocolo que o "adb connect" usa). */
class TransporteStream(private val entrada: InputStream, private val saida: OutputStream, private val aoFechar: () -> Unit) : AdbTransporte {
    override fun escreverMensagem(m: AdbMensagem) {
        synchronized(this) {
            saida.write(m.cabecalho())
            if (m.dados.isNotEmpty()) saida.write(m.dados)
            saida.flush()
        }
    }

    override fun ler(b: ByteArray, off: Int, len: Int) {
        var p = 0
        while (p < len) {
            val n = entrada.read(b, off + p, len - p)
            if (n < 0) throw AdbErro("conexão terminou")
            p += n
        }
    }

    override fun close() = aoFechar()

    companion object {
        fun tcp(host: String, porta: Int): TransporteStream {
            val s = Socket()
            s.connect(InetSocketAddress(host, porta), 5000)
            s.tcpNoDelay = true
            return TransporteStream(s.getInputStream(), s.getOutputStream()) { try { s.close() } catch (_: Exception) { } }
        }
    }
}

/**
 * Transporte pelo cabo: interface ADB (classe 0xFF, subclasse 0x42, protocolo 0x01) com dois pontos "bulk".
 * No modo acessório com depuração (18d1:2d01) ela é a SEGUNDA interface; a primeira é do acessório (Android Auto).
 * Cada uma é "reivindicada" separadamente, então o receptor do Android Auto e este canal não brigam.
 */
class TransporteUsb private constructor(
    private val con: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val entra: UsbEndpoint,
    private val sai: UsbEndpoint,
    private val fecharConexao: Boolean,
) : AdbTransporte {

    private val tmp = ByteArray(BLOCO)

    override fun escreverMensagem(m: AdbMensagem) {
        synchronized(this) {
            val cab = m.cabecalho()
            enviar(cab, 0, cab.size)                 // cabeçalho numa transferência (como o adb do PC)
            if (m.dados.isNotEmpty()) enviar(m.dados, 0, m.dados.size)
        }
    }

    private fun enviar(b: ByteArray, off: Int, len: Int) {
        // Android 9+ aceita transferências maiores que 16 KB; mandar o bloco inteiro de uma vez evita
        // pacotes de tamanho zero no meio dos dados.
        var p = 0
        while (p < len) {
            val n = con.bulkTransfer(sai, b, off + p, len - p, TEMPO_ESCRITA_MS)
            if (n < 0) throw AdbErro("cabo: escrita falhou")
            p += n
        }
        // Pacote de tamanho zero quando o tamanho é múltiplo do pacote USB (o adb do PC faz o mesmo:
        // usb_libusb.cpp should_perform_zero_transfer). Sem isso o adbd fica esperando mais dados.
        val mps = sai.maxPacketSize
        if (len > 0 && mps > 0 && len % mps == 0) con.bulkTransfer(sai, b, off, 0, TEMPO_ESCRITA_MS)
    }

    override fun ler(b: ByteArray, off: Int, len: Int) {
        var p = 0
        while (p < len) {
            val pedir = minOf(BLOCO, len - p)
            val n = con.bulkTransfer(entra, tmp, 0, pedir, 0)    // 0 = espera; solta com erro se o cabo sair
            if (n < 0) throw AdbErro("cabo: leitura falhou (desplugado?)")
            if (n == 0) continue                                // pacote de tamanho zero: ignora
            System.arraycopy(tmp, 0, b, off + p, n)
            p += n
        }
    }

    override fun close() {
        try { con.releaseInterface(intf) } catch (_: Exception) { }
        if (fecharConexao) try { con.close() } catch (_: Exception) { }
    }

    companion object {
        private const val BLOCO = 16384
        private const val TEMPO_ESCRITA_MS = 5000

        fun acharInterface(d: UsbDevice): UsbInterface? = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.interfaceClass == Adb.CLASSE && it.interfaceSubclass == Adb.SUBCLASSE && it.interfaceProtocol == Adb.PROTOCOLO }

        /**
         * Abre a interface ADB. [conexao]: a mesma UsbDeviceConnection do receptor do Android Auto (recomendado:
         * um dono só do aparelho); se null, abre uma conexão própria (o Linux também deixa: cada arquivo do
         * usbfs reivindica interfaces diferentes).
         */
        fun abrir(um: UsbManager, d: UsbDevice, conexao: UsbDeviceConnection? = null): TransporteUsb? {
            val intf = acharInterface(d) ?: return null
            var ent: UsbEndpoint? = null
            var sai: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val e = intf.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN && ent == null) ent = e
                if (e.direction == UsbConstants.USB_DIR_OUT && sai == null) sai = e
            }
            if (ent == null || sai == null) return null
            val c = conexao ?: um.openDevice(d) ?: return null
            if (!c.claimInterface(intf, true)) { if (conexao == null) c.close(); return null }
            return TransporteUsb(c, intf, ent, sai, fecharConexao = conexao == null)
        }
    }
}

/**
 * Chave RSA 2048 da central. Gerada UMA vez e guardada no armazenamento privado do app (FORA do backup:
 * se ela mudar, o celular pergunta "Permitir depuração USB?" de novo).
 */
class AdbChave private constructor(private val privada: PrivateKey, val publica: RSAPublicKey) {

    fun pkcs8(): ByteArray = privada.encoded

    /**
     * Assina o token de 20 bytes do AUTH. O adbd confere com RSA_verify(NID_sha1, token, ...) (daemon/auth.cpp):
     * ou seja, PKCS#1 v1.5 com o DigestInfo do SHA-1 em volta do token (o token NÃO é passado pelo SHA-1).
     */
    fun assinar(token: ByteArray): ByteArray {
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(privada)
        s.update(PREFIXO_SHA1)
        s.update(token)
        return s.sign()
    }

    /** Estrutura RSAPublicKey do Android (524 bytes). */
    fun chavePublicaAndroid(): ByteArray = codificarAndroid(publica.modulus, publica.publicExponent)

    /** O que vai no AUTH(RSAPUBLICKEY): base64 + " nome" + '\0' (igual ao adbkey.pub). */
    fun textoChavePublica(nome: String): ByteArray =
        (Base64.getEncoder().encodeToString(chavePublicaAndroid()) + " " + nome + "\u0000").toByteArray(Charsets.US_ASCII)

    companion object {
        /** DigestInfo do SHA-1 (RFC 8017, 9.2 nota 1). */
        val PREFIXO_SHA1 = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        fun gerar(): AdbChave {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val kp = g.generateKeyPair()
            return AdbChave(kp.private, kp.public as RSAPublicKey)
        }

        fun dePkcs8(der: ByteArray): AdbChave {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(der))
            val crt = priv as? RSAPrivateCrtKey ?: throw AdbErro("chave sem CRT")
            val pub = kf.generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent)) as RSAPublicKey
            return AdbChave(priv, pub)
        }

        /** "-----BEGIN PRIVATE KEY-----" (formato do arquivo adbkey do PC). */
        fun dePem(pem: String): AdbChave {
            val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("").trim()
            return dePkcs8(Base64.getMimeDecoder().decode(b64))
        }

        /** Lê a chave do arquivo ou cria uma nova (gravação atômica: a central perde energia de repente). */
        fun doArquivo(f: File): AdbChave {
            if (f.exists() && f.length() > 0) try { return dePkcs8(f.readBytes()) } catch (_: Exception) { }
            val k = gerar()
            f.parentFile?.mkdirs()
            val t = File(f.path + ".tmp")
            java.io.FileOutputStream(t).use { it.write(k.pkcs8()); it.fd.sync() }
            if (!t.renameTo(f)) throw AdbErro("não gravou a chave")
            return k
        }

        /** android_pubkey_encode: n0inv = -1/n[0] mod 2^32; módulo e R² mod n em little-endian; expoente. */
        fun codificarAndroid(n: BigInteger, e: BigInteger): ByteArray {
            if (n.bitLength() > 2048 || n.bitLength() < 2041) throw AdbErro("a chave precisa ser RSA 2048")
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
            val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
            val bb = ByteBuffer.allocate(Adb.TAMANHO_CHAVE_ANDROID).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(64)
            bb.putInt(n0inv)
            bb.put(littleEndian(n, 256))
            bb.put(littleEndian(rr, 256))
            bb.putInt(e.toInt())
            return bb.array()
        }

        /** android_pubkey_decode (usado pelo adbd falso dos testes). */
        fun decodificarAndroid(b: ByteArray): RSAPublicKey {
            if (b.size < Adb.TAMANHO_CHAVE_ANDROID) throw AdbErro("chave curta")
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.int != 64) throw AdbErro("tamanho do módulo errado")
            bb.int // n0inv (o adbd recalcula)
            val mod = ByteArray(256).also { bb.get(it) }
            bb.position(bb.position() + 256) // rr
            val e = bb.int.toLong() and 0xFFFFFFFFL
            val n = BigInteger(1, mod.reversedArray())
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger.valueOf(e))) as RSAPublicKey
        }

        private fun littleEndian(x: BigInteger, tam: Int): ByteArray {
            val be = x.toByteArray()   // big-endian, pode ter um 0 a mais na frente
            val out = ByteArray(tam)
            var i = 0
            var j = be.size - 1
            while (i < tam && j >= 0) { out[i++] = be[j--] }
            return out
        }
    }
}

/**
 * Uma conexão ADB autenticada. Depois do aperto de mão, uma linha ("adb-leitor") lê as mensagens e
 * entrega para os streams; cada stream respeita o controle de fluxo (um WRTE por vez, espera o OKAY).
 */
class AdbConexao private constructor(private val t: AdbTransporte, val versao: Int, val maxDados: Int, val banner: String) : Closeable {

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val proximoId = AtomicInteger(1)
    @Volatile var aberta = true
        private set
    @Volatile var motivoFim: String? = null
        private set
    private val leitor = Thread({ laco() }, "adb-leitor").apply { isDaemon = true }

    /** Propriedades do celular vindas no CNXN ("device::ro.product.model=...;ro.build.version.sdk=..."). */
    val propriedades: Map<String, String> by lazy {
        banner.substringAfter("::", "").split(';').mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1).trimEnd('\u0000')
        }.toMap()
    }

    internal fun mandar(m: AdbMensagem) {
        if (!aberta) throw AdbErro("conexão ADB fechada")
        try { t.escreverMensagem(m) } catch (e: IOException) { terminar("escrita: ${e.message}"); throw e }
    }

    private fun laco() {
        try {
            val conferir = versao < Adb.VERSAO_SEM_CHECKSUM
            while (aberta) {
                val m = AdbMensagem.ler(t, conferir)
                when (m.comando) {
                    Adb.OKAY -> streams[m.arg1]?.recebeuOkay(m.arg0)
                    Adb.WRTE -> {
                        val s = streams[m.arg1]
                        if (s == null) mandar(AdbMensagem(Adb.CLSE, 0, m.arg0))   // stream que não existe mais
                        else s.recebeuDados(m.dados)
                    }
                    Adb.CLSE -> streams[m.arg1]?.fechouDoOutroLado()
                    Adb.CNXN -> { terminar("o celular reiniciou o ADB"); return }
                    else -> { /* AUTH/SYNC depois de conectado: ignora */ }
                }
            }
        } catch (e: Exception) {
            terminar(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun terminar(motivo: String) {
        if (!aberta) return
        aberta = false
        motivoFim = motivo
        for (s in streams.values) s.fechouDoOutroLado()
        streams.clear()
        try { t.close() } catch (_: Exception) { }
    }

    internal fun esquecer(id: Int) { streams.remove(id) }

    /** Abre um serviço do celular: "exec:...", "shell:...", "sync:", "localabstract:nome", "tcp:porta". */
    fun abrir(destino: String, esperaMs: Long = 10_000): AdbStream {
        val id = proximoId.getAndIncrement()
        val s = AdbStream(this, id, destino)
        streams[id] = s
        mandar(AdbMensagem(Adb.OPEN, id, 0, (destino + "\u0000").toByteArray(Charsets.UTF_8)))
        if (!s.esperarAbrir(esperaMs)) {
            streams.remove(id)
            throw AdbErro("o celular recusou \"$destino\"")
        }
        return s
    }

    /** Roda um comando ("exec:", sem terminal: os bytes passam sem mudança) e devolve a saída. */
    fun executar(comando: String, esperaMs: Long = 15_000): String {
        abrir("exec:$comando").use { s ->
            val out = ByteArrayOutputStream()
            val fim = System.currentTimeMillis() + esperaMs
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < fim) {
                val n = s.lerAte(buf, 0, buf.size, fim - System.currentTimeMillis())
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    /** "adb push" (protocolo SYNC: SEND, DATA de até 64 KB, DONE; resposta OKAY ou FAIL). */
    fun enviarArquivo(caminho: String, dados: ByteArray, modo: Int = 0x81a4 /* 0100644 */, mtime: Int = (System.currentTimeMillis() / 1000).toInt()) {
        abrir("sync:").use { s ->
            val nome = "$caminho,$modo".toByteArray(Charsets.UTF_8)
            s.escrever(sync("SEND", nome.size) + nome)
            var p = 0
            while (p < dados.size) {
                val n = minOf(SYNC_MAX, dados.size - p)
                s.escrever(sync("DATA", n) + dados.copyOfRange(p, p + n))
                p += n
            }
            s.escrever(sync("DONE", mtime))
            val r = s.lerExato(8, 30_000)
            val id = String(r, 0, 4, Charsets.US_ASCII)
            val n = ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "FAIL") throw AdbErro("push falhou: " + String(s.lerExato(n, 5000), Charsets.UTF_8))
            if (id != "OKAY") throw AdbErro("push: resposta $id")
            try { s.escrever(sync("QUIT", 0)) } catch (_: Exception) { }
        }
    }

    override fun close() {
        for (s in streams.values.toList()) try { s.close() } catch (_: Exception) { }
        terminar("fechada pela central")
    }

    companion object {
        private const val SYNC_MAX = 64 * 1024

        private fun sync(id: String, n: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(n).array()

        /**
         * Aperto de mão: CNXN -> AUTH(TOKEN) -> assinatura; se o celular não conhece a chave, manda a chave
         * pública e espera o "Permitir" (até [esperaPermissaoMs]). [aoPedirPermissao] avisa a tela da central
         * ("Toque em Permitir no celular").
         */
        fun conectar(
            t: AdbTransporte,
            chave: AdbChave,
            nome: String = "painelasx@central",
            esperaPermissaoMs: Long = 120_000,
            aoPedirPermissao: () -> Unit = {},
        ): AdbConexao {
            val banner = "host::\u0000".toByteArray(Charsets.US_ASCII)
            t.escreverMensagem(AdbMensagem(Adb.CNXN, Adb.VERSAO, Adb.MAX_DADOS, banner))
            var assinou = false
            var mandouChave = false
            val leitura = LeitorComPrazo(t)
            while (true) {
                val prazo = if (mandouChave) esperaPermissaoMs else 15_000L
                val m = leitura.ler(prazo) ?: run { t.close(); throw AdbErro(if (mandouChave) "ninguém tocou em Permitir no celular" else "o celular não respondeu ao ADB") }
                when (m.comando) {
                    Adb.CNXN -> {
                        val ban = String(m.dados, Charsets.UTF_8).trimEnd('\u0000')
                        val c = AdbConexao(t, m.arg0, minOf(m.arg1, Adb.MAX_DADOS).coerceAtLeast(Adb.MAX_DADOS_V1), ban)
                        c.leitor.start()
                        return c
                    }
                    Adb.AUTH -> {
                        if (m.arg0 != Adb.AUTH_TOKEN) continue
                        if (!assinou) {
                            assinou = true
                            t.escreverMensagem(AdbMensagem(Adb.AUTH, Adb.AUTH_ASSINATURA, 0, chave.assinar(m.dados)))
                        } else if (!mandouChave) {
                            // O celular não conhece esta central: manda a chave e ele mostra "Permitir depuração USB?".
                            mandouChave = true
                            t.escreverMensagem(AdbMensagem(Adb.AUTH, Adb.AUTH_CHAVE_PUBLICA, 0, chave.textoChavePublica(nome)))
                            aoPedirPermissao()
                        }
                    }
                    Adb.STLS -> { t.close(); throw AdbErro("o celular pediu TLS (só acontece na depuração sem fio)") }
                    else -> { /* ignora */ }
                }
            }
        }
    }

    /** Lê mensagens numa linha separada para o aperto de mão ter prazo (bulkTransfer com 0 espera para sempre). */
    private class LeitorComPrazo(private val t: AdbTransporte) {
        private val fila = LinkedBlockingQueue<Any>()
        private val linha = Thread({
            try { while (true) { val m = AdbMensagem.ler(t, false); fila.put(m); if (m.comando == Adb.CNXN) break } }
            catch (e: Exception) { fila.put(e) }
        }, "adb-aperto").apply { isDaemon = true; start() }

        fun ler(ms: Long): AdbMensagem? {
            val x = fila.poll(ms, TimeUnit.MILLISECONDS) ?: return null
            if (x is Exception) throw AdbErro("aperto de mão: ${x.message}")
            return x as AdbMensagem
        }
    }
}

/** Um stream ADB: entrada (o que o celular escreve) e saída (o que a central escreve). */
class AdbStream internal constructor(private val c: AdbConexao, val localId: Int, val destino: String) : Closeable {
    @Volatile private var remotoId = 0
    private val abriu = CountDownLatch(1)
    private val podeEscrever = Semaphore(0)
    @Volatile private var fechado = false
    @Volatile private var fechouRemoto = false

    // Recebidos ainda não lidos. O OKAY de cada WRTE só vai quando há espaço (controle de fluxo do protocolo).
    private val fila = LinkedBlockingQueue<ByteArray>()
    private var pendentes = 0
    private var okayAtrasado = false
    private var atual: ByteArray? = null
    private var pos = 0

    internal fun esperarAbrir(ms: Long): Boolean = abriu.await(ms, TimeUnit.MILLISECONDS) && remotoId != 0 && !fechouRemoto

    internal fun recebeuOkay(remoto: Int) {
        if (remotoId == 0) {
            remotoId = remoto
            podeEscrever.release()
            abriu.countDown()
        } else {
            podeEscrever.release()
        }
    }

    internal fun recebeuDados(d: ByteArray) {
        var mandarOkay: Boolean
        synchronized(this) {
            fila.put(d)
            pendentes += d.size
            mandarOkay = pendentes <= LIMITE
            if (!mandarOkay) okayAtrasado = true
        }
        if (mandarOkay) okay()
    }

    private fun okay() { try { c.mandar(AdbMensagem(Adb.OKAY, localId, remotoId)) } catch (_: IOException) { } }

    internal fun fechouDoOutroLado() {
        val primeiro = !fechouRemoto
        fechouRemoto = true
        abriu.countDown()
        podeEscrever.release(1000)      // destrava quem está esperando para escrever
        fila.put(FIM)
        if (primeiro && remotoId != 0 && !fechado) {
            try { c.mandar(AdbMensagem(Adb.CLSE, localId, remotoId)) } catch (_: IOException) { }
        }
        c.esquecer(localId)
    }

    private fun consumiu(n: Int) {
        var mandarOkay = false
        synchronized(this) {
            pendentes -= n
            if (okayAtrasado && pendentes <= LIMITE / 2) { okayAtrasado = false; mandarOkay = true }
        }
        if (mandarOkay) okay()
    }

    /** Lê até [len] bytes, esperando no máximo [ms]. -1 = fim (ou prazo esgotado sem nada). */
    fun lerAte(b: ByteArray, off: Int, len: Int, ms: Long): Int {
        var a = atual
        if (a == null || pos >= a.size) {
            val x = (if (ms == Long.MAX_VALUE) fila.take() else fila.poll(maxOf(ms, 0), TimeUnit.MILLISECONDS)) ?: return 0
            if (x === FIM) { fila.put(FIM); return -1 }
            consumiu(x.size)
            atual = x; pos = 0; a = x
        }
        val n = minOf(len, a.size - pos)
        System.arraycopy(a, pos, b, off, n)
        pos += n
        return n
    }

    fun lerExato(n: Int, ms: Long): ByteArray {
        val r = ByteArray(n)
        var p = 0
        val fim = System.currentTimeMillis() + ms
        while (p < n) {
            val resta = fim - System.currentTimeMillis()
            if (resta <= 0) throw AdbErro("tempo esgotado lendo $destino")
            val k = lerAte(r, p, n - p, resta)
            if (k < 0) throw AdbErro("$destino fechou")
            p += k
        }
        return r
    }

    /** Escreve tudo, em pedaços de até maxDados, um WRTE por vez (espera o OKAY do celular). */
    fun escrever(b: ByteArray, off: Int = 0, len: Int = b.size) {
        var p = 0
        while (p < len) {
            if (!podeEscrever.tryAcquire(30, TimeUnit.SECONDS)) throw AdbErro("o celular não confirmou ($destino)")
            if (fechado || fechouRemoto) throw AdbErro("$destino fechado")
            val n = minOf(c.maxDados, len - p)
            c.mandar(AdbMensagem(Adb.WRTE, localId, remotoId, b.copyOfRange(off + p, off + p + n)))
            p += n
        }
    }

    val entrada: InputStream = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); val n = read(b, 0, 1); return if (n < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val n = lerAte(b, off, len, Long.MAX_VALUE)
                if (n != 0) return n
            }
        }
        override fun close() = this@AdbStream.close()
    }

    val saida: OutputStream = object : OutputStream() {
        override fun write(b: Int) = escrever(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) = escrever(b, off, len)
        override fun close() = this@AdbStream.close()
    }

    val aberto: Boolean get() = !fechado && !fechouRemoto && c.aberta

    override fun close() {
        if (fechado) return
        fechado = true
        if (!fechouRemoto && remotoId != 0) try { c.mandar(AdbMensagem(Adb.CLSE, localId, remotoId)) } catch (_: IOException) { }
        fila.put(FIM)
        podeEscrever.release(1000)
        c.esquecer(localId)
    }

    companion object {
        private const val LIMITE = 1024 * 1024
        private val FIM = ByteArray(0)
    }
}
