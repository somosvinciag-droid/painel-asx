package br.com.leonardo.painelasx.canal

import android.hardware.usb.UsbDevice
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/*
 * Canal paralelo pelo cabo do Android Auto: a central usa a interface ADB do celular para
 *  1) ouvir o MICROFONE do celular (PCM 16 kHz mono, 16 bits) e mandar para o Ouvido/Whisper da central;
 *  2) ter INTERNET só para o app Painel ASX (proxy HTTP local 127.0.0.1:porta -> túnel ADB -> celular).
 *
 * No celular roda o "servidor shell" (app/canal-servidor/src/…/Servidor.java, montado pelo GitHub Actions em
 * assets/canal-servidor.jar), iniciado com app_process como usuário shell,
 * igual ao scrcpy-server. Ele escuta no soquete abstrato "painelasx-canal". Cada stream ADB
 * "localabstract:painelasx-canal" manda UMA linha de comando:
 *    PING                      -> "PONG <versão> <sdk>\n"
 *    MIC <fonte> <taxa>        -> "OK\n" + PCM s16le mono sem parar (até a central fechar)
 *    NET <host> <porta>        -> "OK\n" + túnel TCP cru (o shell tem o grupo inet: AID_INET no adbd)
 *    SAIR                      -> o servidor termina
 * Erro: "ERRO <motivo>\n" e fecha.
 *
 * Observação importante (CONFIRMADO no código do adbd, socket_spec.cpp): o adbd NÃO abre conexões para fora
 * ("Disallow arbitrary connections in adbd"): "tcp:host:porta" só vale para o próprio celular. Por isso a
 * internet passa pelo servidor shell (comando NET), e não direto pelo adbd.
 */
class CanalParalelo(val adb: AdbConexao, private val log: (String) -> Unit = {}) : Closeable {

    @Volatile private var processo: AdbStream? = null
    @Volatile var proxy: ProxyLocal? = null
        private set

    // ------------------------------------------------------------------ servidor no celular

    /** Pergunta ao servidor se está vivo. Devolve "PONG <versão> <sdk>" ou null. */
    fun ping(esperaMs: Long = 2000): String? = try {
        adb.abrir(DESTINO, esperaMs).use { s ->
            s.escrever("PING\n".toByteArray())
            lerLinha(s, esperaMs)
        }
    } catch (_: IOException) { null }

    /**
     * Deixa o servidor certo rodando: se já responde com a [versao], não faz nada; senão manda o .jar
     * (só se ainda não estiver lá) e inicia com app_process. Devolve true se no fim ele respondeu.
     */
    fun garantirServidor(jar: ByteArray, versao: String): Boolean {
        val p = ping()
        if (p != null && p.startsWith("PONG $versao ")) return true
        if (p != null) pedirParaSair()
        val caminho = "$PASTA/painelasx-canal-$versao.jar"
        val existe = adb.executar("ls $caminho 2>/dev/null").trim().endsWith("/painelasx-canal-$versao.jar")
        if (!existe) {
            adb.executar("rm -f $PASTA/painelasx-canal-*.jar")
            adb.enviarArquivo(caminho, jar)
            log("canal: servidor $versao enviado (${jar.size / 1024} KB)")
        }
        val proc = adb.abrir("exec:CLASSPATH=$caminho app_process / $CLASSE_SERVIDOR $versao")
        processo = proc
        // A saída do servidor (avisos e erros) vai para o Registro da central. Quando o stream fechar
        // (cabo saiu), o servidor recebe fim de arquivo na entrada e termina sozinho.
        Thread({
            try {
                val buf = ByteArrayOutputStream()
                while (true) {
                    val c = proc.entrada.read()
                    if (c < 0) break
                    if (c == '\n'.code) { log("canal: celular diz: " + buf.toString("UTF-8")); buf.reset() } else if (buf.size() < 500) buf.write(c)
                }
            } catch (_: Exception) { }
            log("canal: servidor do celular terminou")
        }, "canal-servidor").apply { isDaemon = true; start() }
        val fim = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < fim) {
            val r = ping(1500)
            if (r != null && r.startsWith("PONG $versao ")) { log("canal: servidor pronto ($r)"); return true }
            Thread.sleep(200)
        }
        log("canal: o servidor não respondeu")
        return false
    }

    private fun pedirParaSair() {
        try { adb.abrir(DESTINO, 2000).use { it.escrever("SAIR\n".toByteArray()) } } catch (_: IOException) { }
        Thread.sleep(300)
    }

    /**
     * Configurações de UMA vez, feitas pela própria central (o shell pode mudar "settings global"):
     * não deixar o Android apagar a autorização desta central depois de 7 dias sem uso
     * (= "Desativar tempo limite de autorização adb" nas Opções do desenvolvedor).
     */
    fun configurarUmaVez(): Boolean = try {
        val r = adb.executar("settings put global adb_allowed_connection_time 0 2>&1; settings get global adb_allowed_connection_time 2>&1").trim()
        val ok = r.lines().lastOrNull()?.trim() == "0"
        log(if (ok) "canal: autorização do celular sem prazo (não expira em 7 dias)" else "canal: não mudei o prazo da autorização (${r.take(120)})")
        ok
    } catch (e: IOException) { log("canal: não mudei o prazo da autorização (${e.message})"); false }

    // ------------------------------------------------------------------ microfone

    /** Abre o microfone do celular. [fonte]: voice_recognition (padrão), mic, voice_communication, unprocessed. */
    fun abrirMicrofone(fonte: String = "voice_recognition", taxa: Int = 16000, esperaMs: Long = 4000): MicrofoneRemoto {
        val s = adb.abrir(DESTINO, esperaMs)
        try {
            s.escrever("MIC $fonte $taxa\n".toByteArray())
            val r = lerLinha(s, esperaMs) ?: throw AdbErro("microfone do celular não respondeu")
            if (r != "OK") throw AdbErro("microfone do celular: $r")
            return MicrofoneRemoto(s)
        } catch (e: IOException) { s.close(); throw e }
    }

    // ------------------------------------------------------------------ internet

    /** Túnel TCP pelo celular (DNS resolvido no celular). */
    fun abrirTunel(host: String, porta: Int): ProxyLocal.Tunel {
        val s = adb.abrir(DESTINO)
        try {
            s.escrever("NET $host $porta\n".toByteArray())
            val r = lerLinha(s, 15_000) ?: throw AdbErro("túnel sem resposta")
            if (r != "OK") throw AdbErro("túnel: $r")
            return object : ProxyLocal.Tunel {
                override val entrada: InputStream = s.entrada
                override val saida: OutputStream = s.saida
                override fun close() = s.close()
            }
        } catch (e: IOException) { s.close(); throw e }
    }

    /** Liga o proxy local e aponta os pedidos HTTP(S) do próprio app para ele. */
    fun ligarInternet(): Int {
        val p = proxy ?: ProxyLocal({ h, port -> abrirTunel(h, port) }, log).also { proxy = it }
        ProxyDoApp.ligar(p.porta)
        return p.porta
    }

    override fun close() {
        // Só desliga o proxy do app se ele ainda é o DESTE canal (uma plugada nova pode já ter ligado outro).
        proxy?.let { if (ProxyDoApp.porta == it.porta) ProxyDoApp.desligar() }
        try { proxy?.close() } catch (_: Exception) { }
        proxy = null
        try { processo?.close() } catch (_: Exception) { }
        processo = null
    }

    companion object {
        const val SOQUETE = "painelasx-canal"
        const val DESTINO = "localabstract:$SOQUETE"
        const val PASTA = "/data/local/tmp"
        const val CLASSE_SERVIDOR = "br.com.leonardo.painelasx.canal.servidor.Servidor"

        /** Versão = começo do sha-256 do .jar (muda sozinha a cada mudança no servidor). */
        fun versaoDo(jar: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(jar).take(6).joinToString("") { "%02x".format(it) }

        fun lerLinha(s: AdbStream, ms: Long): String? {
            val out = ByteArrayOutputStream()
            val fim = System.currentTimeMillis() + ms
            val b = ByteArray(1)
            while (out.size() < 1024) {
                val resta = fim - System.currentTimeMillis()
                if (resta <= 0) return null
                val n = s.lerAte(b, 0, 1, resta)
                if (n < 0) return if (out.size() > 0) out.toString("UTF-8") else null
                if (n == 0) continue
                if (b[0] == '\n'.code.toByte()) return out.toString("UTF-8")
                out.write(b[0].toInt())
            }
            return out.toString("UTF-8")
        }
    }
}

/** PCM do microfone do celular. Use [ler] no lugar do AudioRecord.read do Ouvido. */
class MicrofoneRemoto internal constructor(private val s: AdbStream) : Closeable {
    private val bytes = ByteArray(8192)
    private var sobra = -1   // byte ímpar que ficou da leitura anterior

    /** Lê até pedaco.size amostras (16 bits, little-endian). -1 = o microfone do celular parou. */
    fun ler(pedaco: ShortArray, esperaMs: Long = 500): Int {
        var p = 0
        val max = minOf(pedaco.size * 2, bytes.size)
        if (sobra >= 0) { bytes[0] = sobra.toByte(); p = 1; sobra = -1 }
        val n = s.lerAte(bytes, p, max - p, esperaMs)
        if (n < 0) return -1
        val total = p + n
        val amostras = total / 2
        for (i in 0 until amostras) pedaco[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        if (total % 2 == 1) sobra = bytes[total - 1].toInt() and 0xFF
        return amostras
    }

    override fun close() = s.close()
}

/**
 * Proxy HTTP só para o próprio app (sem VPN). Aceita CONNECT (HTTPS) e pedidos http:// em forma absoluta.
 * Escuta SÓ em 127.0.0.1 (outros apps da central poderiam usar, mas não há nada para fora além do túnel).
 */
class ProxyLocal(private val abrir: (String, Int) -> Tunel, private val log: (String) -> Unit = {}) : Closeable {

    interface Tunel : Closeable {
        val entrada: InputStream
        val saida: OutputStream
    }

    private val servidor = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val porta: Int = servidor.localPort
    private val vagas = Semaphore(32)
    @Volatile private var fechado = false
    @Volatile private var ultimoLog = 0L

    /** No máximo uma linha a cada 30 s no Registro (um site fora do ar não enche o registro). */
    private fun anotar(t: String) {
        val agora = System.currentTimeMillis()
        if (agora - ultimoLog < 30_000) return
        ultimoLog = agora
        log("canal: proxy: $t")
    }

    init {
        Thread({
            while (!fechado) {
                val cli = try { servidor.accept() } catch (_: IOException) { break }
                if (!vagas.tryAcquire()) { try { cli.close() } catch (_: IOException) { }; continue }
                Thread({ try { atender(cli) } finally { vagas.release() } }, "proxy-cliente").apply { isDaemon = true; start() }
            }
        }, "proxy-local").apply { isDaemon = true; start() }
    }

    private fun atender(cli: Socket) {
        var tunel: Tunel? = null
        try {
            cli.soTimeout = 15_000
            val ent = cli.getInputStream()
            val (cab, resto) = lerCabecalho(ent) ?: return
            cli.soTimeout = 0
            val linhas = cab.split("\r\n")
            val partes = linhas[0].split(' ')
            if (partes.size < 3) { responder(cli, 400, "pedido inválido"); return }
            val metodo = partes[0]; val alvo = partes[1]; val versao = partes[2]
            if (metodo.equals("CONNECT", true)) {
                val (h, p) = hostPorta(alvo, 443) ?: run { responder(cli, 400, "alvo inválido"); return }
                val tn = try { abrir(h, p) } catch (e: IOException) { anotar("$h: ${e.message}"); responder(cli, 502, e.message ?: "túnel falhou"); return }
                tunel = tn
                cli.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                if (resto.isNotEmpty()) tn.saida.write(resto)
            } else if (alvo.startsWith("http://", true)) {
                val u = URI(alvo)
                val h = u.host ?: run { responder(cli, 400, "sem host"); return }
                val p = if (u.port > 0) u.port else 80
                val caminho = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
                val tn = try { abrir(h, p) } catch (e: IOException) { responder(cli, 502, e.message ?: "túnel falhou"); return }
                tunel = tn
                val sb = StringBuilder("$metodo $caminho $versao\r\n")
                for (l in linhas.drop(1)) {
                    if (l.isEmpty()) continue
                    val nome = l.substringBefore(':').trim().lowercase()
                    if (nome == "proxy-connection" || nome == "connection" || nome == "keep-alive" || nome == "proxy-authorization") continue
                    sb.append(l).append("\r\n")
                }
                // Uma conexão por pedido: o cliente não reaproveita esta conexão para outro host.
                sb.append("Connection: close\r\n\r\n")
                tn.saida.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (resto.isNotEmpty()) tn.saida.write(resto)
            } else {
                responder(cli, 400, "só CONNECT e http://"); return
            }
            bombear(cli, tunel ?: return)
        } catch (e: Exception) {
            anotar("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { tunel?.close() } catch (_: Exception) { }
            try { cli.close() } catch (_: Exception) { }
        }
    }

    /** Cliente -> túnel numa linha; túnel -> cliente nesta. O ADB não tem "meio fechamento": só fecha no fim da resposta. */
    private fun bombear(cli: Socket, t: Tunel) {
        val ida = Thread({
            val b = ByteArray(16384)
            try {
                val e = cli.getInputStream()
                while (true) { val n = e.read(b); if (n < 0) break; if (n > 0) t.saida.write(b, 0, n) }
            } catch (_: Exception) { }
        }, "proxy-ida").apply { isDaemon = true; start() }
        val b = ByteArray(16384)
        try {
            val s = cli.getOutputStream()
            while (true) { val n = t.entrada.read(b, 0, b.size); if (n < 0) break; if (n > 0) { s.write(b, 0, n); s.flush() } }
        } catch (_: Exception) { }
        try { cli.shutdownOutput() } catch (_: Exception) { }
        ida.interrupt()
    }

    private fun responder(cli: Socket, codigo: Int, texto: String) {
        try {
            val corpo = texto.toByteArray()
            cli.getOutputStream().write(("HTTP/1.1 $codigo $texto\r\nContent-Length: ${corpo.size}\r\nConnection: close\r\n\r\n").toByteArray() + corpo)
        } catch (_: IOException) { }
    }

    override fun close() {
        fechado = true
        try { servidor.close() } catch (_: IOException) { }
    }

    companion object {
        /** Lê até "\r\n\r\n" (máx. 32 KB). Devolve (cabeçalho, bytes que já vieram depois). */
        fun lerCabecalho(e: InputStream): Pair<String, ByteArray>? {
            val buf = ByteArrayOutputStream()
            val b = ByteArray(4096)
            while (buf.size() < 32 * 1024) {
                val n = e.read(b)
                if (n < 0) return null
                buf.write(b, 0, n)
                val tudo = buf.toByteArray()
                val i = indice(tudo)
                if (i >= 0) return String(tudo, 0, i, Charsets.ISO_8859_1) to tudo.copyOfRange(i + 4, tudo.size)
            }
            return null
        }

        private fun indice(a: ByteArray): Int {
            for (i in 0..a.size - 4) if (a[i] == '\r'.code.toByte() && a[i + 1] == '\n'.code.toByte() && a[i + 2] == '\r'.code.toByte() && a[i + 3] == '\n'.code.toByte()) return i
            return -1
        }

        fun hostPorta(alvo: String, padrao: Int): Pair<String, Int>? {
            if (alvo.startsWith("[")) {   // IPv6: [::1]:443
                val f = alvo.indexOf(']'); if (f < 0) return null
                val h = alvo.substring(1, f)
                val p = alvo.substring(f + 1).removePrefix(":").toIntOrNull() ?: padrao
                return h to p
            }
            val i = alvo.lastIndexOf(':')
            if (i < 0) return alvo to padrao
            val p = alvo.substring(i + 1).toIntOrNull() ?: return null
            return alvo.substring(0, i) to p
        }
    }
}

/**
 * Faz o HttpURLConnection do app (Conector, Jarvis, Clima, Atualizador, Arquivo) passar pelo proxy local
 * enquanto o canal está ligado E a central não tem outra internet (Wi-Fi/ancoragem do celular).
 * Não é VPN: Spotify, rádio (MediaPlayer) e outros apps NÃO passam por aqui.
 */
object ProxyDoApp {
    @Volatile var porta = 0
        private set
    @Volatile private var anterior: ProxySelector? = null
    /** A central tem outra internet agora? (no app: rede ativa com internet conferida; nos testes: falso) */
    @Volatile var outraInternet: () -> Boolean = { false }

    fun ligar(p: Int) {
        synchronized(this) {
            val atual = ProxySelector.getDefault()
            if (atual !== Seletor) { anterior = atual; ProxySelector.setDefault(Seletor) }
            porta = p
        }
    }

    fun desligar() { porta = 0 }

    /** Os pedidos do app estão indo pelo celular agora (cuidado com downloads grandes: dados móveis). */
    fun emUso(): Boolean = porta != 0 && !temOutra()

    private fun temOutra(): Boolean = try { outraInternet() } catch (_: Throwable) { true }

    private object Seletor : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val p = porta
            val h = uri.host ?: ""
            val local = h == "localhost" || h.startsWith("127.") || h == "::1"
            if (p == 0 || local || (uri.scheme != "http" && uri.scheme != "https") || temOutra())
                return anterior?.select(uri) ?: listOf(Proxy.NO_PROXY)
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", p)))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) { anterior?.connectFailed(uri, sa, ioe) }
    }
}

/**
 * Quem cuida do canal na central. O CaboUsb (pelo `CanalDoCabo`) chama [plugou] quando o celular aparece como
 * 18d1:2d01 (acessório + ADB). Sem Context (fácil de testar na JVM): recebe como abrir o transporte (a interface ADB
 * na MESMA conexão USB do Android Auto), a pasta privada (fora do backup), o .jar do servidor e onde anotar.
 *
 * Nunca fica pedindo sem parar: no máximo [MAX_TENTATIVAS] tentativas seguidas por plugada; se ninguém tocar em
 * "Permitir" no celular em 2 min, para até o cabo ser solto (o "Permitir" só aparece uma vez por plugada).
 */
object CanalCabo {
    const val PEDINDO = "toque em PERMITIR no celular (marque \"Sempre permitir\")"
    const val SEM_PERMISSAO = "sem permissão do celular (ninguém tocou em Permitir); tire e ponha o cabo"
    const val SEM_ADB = "sem ADB (ligue a Depuração USB no celular)"
    const val MAX_TENTATIVAS = 5
    private const val PING_MS = 20_000L

    @Volatile var estado = "desligado"
        private set
    @Volatile var canal: CanalParalelo? = null
        private set
    @Volatile var modelo = ""
        private set
    @Volatile var ultimoErro = ""
        private set
    @Volatile var ligadoEm = 0L
        private set
    @Volatile private var linha: Thread? = null
    @Volatile private var aparelhoAtual: String? = null
    @Volatile private var transporte: AdbTransporte? = null
    @Volatile private var log: (String) -> Unit = {}
    /** Aparelhos largados nesta plugada (sem "Permitir" ou tentativas esgotadas): nada de perguntar de novo até soltar. */
    private val largados = HashSet<String>()

    /** Pronto para usar (ADB autenticado, servidor do celular respondendo). */
    val ligado: Boolean get() = estado == "ligado" && canal?.adb?.aberta == true

    fun temAdb(d: UsbDevice) = TransporteUsb.acharInterface(d) != null

    /** O celular está no cabo sem a interface ADB (2d00): o canal fica desligado e o Android Auto segue normal. */
    fun semAdb(nome: String) {
        if (aparelhoAtual == null || aparelhoAtual == nome) estado = SEM_ADB
    }

    /**
     * Celular com ADB no cabo. [abrir] abre a interface ADB (null = não deu). [atrasoMs]: espera antes de começar
     * (deixa o Android Auto passar primeiro). Chamar de novo para o mesmo aparelho não faz nada.
     */
    fun plugou(
        nome: String,
        abrir: () -> AdbTransporte?,
        pasta: File,
        jar: () -> ByteArray,
        log: (String) -> Unit,
        avisar: (String) -> Unit = {},
        atrasoMs: Long = 0,
        esperaPermissaoMs: Long = 120_000,
    ) {
        val t: Thread
        synchronized(this) {
            if (linha?.isAlive == true && aparelhoAtual == nome) return
            if (nome in largados) return      // o Android Auto reabriu o mesmo aparelho: não pergunta de novo
            aparelhoAtual = nome
            this.log = log
            t = Thread({ laco(nome, abrir, pasta, jar, log, avisar, atrasoMs, esperaPermissaoMs) }, "canal-cabo").apply { isDaemon = true }
            linha = t
        }
        t.start()
    }

    private fun laco(nome: String, abrir: () -> AdbTransporte?, pasta: File, jar: () -> ByteArray, log: (String) -> Unit,
                     avisar: (String) -> Unit, atrasoMs: Long, esperaPermissaoMs: Long) {
        try { if (atrasoMs > 0) Thread.sleep(atrasoMs) } catch (_: InterruptedException) { return }
        var tentativas = 0
        while (aparelhoAtual == nome && tentativas < MAX_TENTATIVAS) {
            tentativas++
            var meu: CanalParalelo? = null
            try {
                rodar(nome, abrir, pasta, jar, log, avisar, esperaPermissaoMs, { c -> meu = c }) { tentativas = 0 }
            } catch (e: AdbSemPermissao) {
                if (aparelhoAtual == nome) {
                    synchronized(this) { largados.add(nome) }
                    estado = SEM_PERMISSAO; ultimoErro = e.message ?: ""
                    log("canal: ninguém tocou em Permitir no celular; fico parado até o cabo ser solto")
                }
                limpar(meu)
                return
            } catch (e: Exception) {
                if (aparelhoAtual == nome) {
                    ultimoErro = "${e.javaClass.simpleName}: ${e.message}".take(160)
                    log("canal: caiu ($ultimoErro)")
                }
            }
            limpar(meu)
            if (aparelhoAtual != nome) break
            try { Thread.sleep(3000L * maxOf(1, tentativas)) } catch (_: InterruptedException) { break }
        }
        if (aparelhoAtual == nome && tentativas >= MAX_TENTATIVAS) {
            synchronized(this) { largados.add(nome) }
            estado = "desligado (desisti depois de $MAX_TENTATIVAS tentativas; tire e ponha o cabo)"
            log("canal: desisti nesta plugada ($MAX_TENTATIVAS tentativas); o Jarvis usa o microfone da central")
        }
    }

    private fun rodar(nome: String, abrir: () -> AdbTransporte?, pasta: File, jar: () -> ByteArray, log: (String) -> Unit,
                      avisar: (String) -> Unit, esperaPermissaoMs: Long, criou: (CanalParalelo) -> Unit, pronto: () -> Unit) {
        estado = "conectando"
        val j = try { jar() } catch (e: Exception) { throw AdbErro("falta o servidor do celular (canal-servidor.jar): ${e.message}") }
        val t = abrir() ?: throw AdbErro("não abri a interface ADB do cabo")
        transporte = t
        val adb = try {
            val chave = AdbChave.doArquivo(File(pasta, "adb/chave_central.pk8"))
            AdbConexao.conectar(t, chave, "painelasx@central", esperaPermissaoMs) {
                estado = PEDINDO
                log("canal: $PEDINDO (o celular ainda não conhece esta central)")
                avisar("Toque em Permitir no celular e marque Sempre permitir.")
            }
        } catch (e: Exception) { try { t.close() } catch (_: Exception) { }; throw e }
        if (aparelhoAtual != nome) { adb.close(); throw AdbErro("o cabo foi solto") }
        modelo = adb.propriedades["ro.product.model"] ?: "?"
        log("canal: ADB ligado (celular $modelo)")
        val c = CanalParalelo(adb, log)
        canal = c
        criou(c)
        // Uma vez por celular: não deixar o Android apagar a autorização depois de 7 dias sem dirigir.
        val feito = File(pasta, "adb/configurado")
        val chaveCel = modelo + "|" + (adb.propriedades["ro.product.device"] ?: "")
        if ((try { feito.readText() } catch (_: Exception) { "" }) != chaveCel && c.configurarUmaVez()) {
            try { feito.parentFile?.mkdirs(); feito.writeText(chaveCel) } catch (_: Exception) { }
        }
        val versao = CanalParalelo.versaoDo(j)
        if (!c.garantirServidor(j, versao)) throw AdbErro("o servidor do celular não subiu")
        val porta = c.ligarInternet()
        ligadoEm = System.currentTimeMillis()
        estado = "ligado"
        ultimoErro = ""
        pronto()   // funcionou: uma queda depois começa a contagem de novo
        log("canal: pronto: microfone do celular e internet do app (proxy 127.0.0.1:$porta)")
        // Fica aqui enquanto a conexão viver; um PING a cada 20 s confere o servidor.
        while (adb.aberta && aparelhoAtual == nome) {
            try { Thread.sleep(PING_MS) } catch (_: InterruptedException) { break }
            if (adb.aberta && aparelhoAtual == nome && c.ping(4000) == null) {
                log("canal: servidor do celular sem resposta; reiniciando")
                if (!c.garantirServidor(j, versao)) throw AdbErro("o servidor do celular não voltou")
            }
        }
        throw AdbErro("ADB caiu: ${adb.motivoFim ?: "cabo solto"}")
    }

    /** Fecha o que esta linha abriu (sem mexer no canal de outra plugada). */
    private fun limpar(meu: CanalParalelo?) {
        val c = meu ?: return
        synchronized(this) { if (canal === c) canal = null }
        try { c.close() } catch (_: Exception) { }
        try { c.adb.close() } catch (_: Exception) { }
        if (estado == "ligado" || estado == "conectando") estado = "desligado"
    }

    /** O cabo saiu (USB_DEVICE_DETACHED). */
    fun soltou(nome: String) {
        val c: CanalParalelo?
        val t: AdbTransporte?
        synchronized(this) {
            largados.remove(nome)
            if (aparelhoAtual != nome) return
            aparelhoAtual = null
            c = canal; canal = null
            t = transporte; transporte = null
        }
        if (c != null) { try { c.close() } catch (_: Exception) { }; try { c.adb.close() } catch (_: Exception) { } }
        try { t?.close() } catch (_: Exception) { }
        estado = "desligado"
        if (c != null) log("canal: cabo solto; microfone e internet voltam para a central")
    }

    /** O modo do cabo mudou (ex.: ZLink): desliga tudo. */
    fun parar() { aparelhoAtual?.let { soltou(it) } }

    /** Para o Ouvido: o microfone do celular, ou null (usa o da central). */
    fun microfone(esperaMs: Long = 2500): MicrofoneRemoto? {
        val c = canal ?: return null
        if (!ligado) return null
        return try { c.abrirMicrofone(esperaMs = esperaMs) } catch (e: IOException) {
            ultimoErro = "microfone: ${e.message}".take(160)
            log("canal: o microfone do celular não abriu (${e.message}); uso o da central")
            null
        }
    }

    fun estadoConector(): Map<String, Any> = mapOf(
        "estado" to estado,
        "modelo" to modelo,
        "internet_pelo_celular" to ProxyDoApp.emUso(),
        "proxy_porta" to ProxyDoApp.porta,
        "ultimo_erro" to ultimoErro,
    )
}
