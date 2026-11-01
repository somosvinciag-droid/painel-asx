/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Aperto de mão TLS pelo SSLEngine e tratamento dos registros de controle adaptados do Headunit Revived /
 * Open Headunit (https://github.com/andreknieriem/headunit-revived: AapSslContext.kt,
 * TlsApplicationPump.kt, SingleKeyKeyManager.kt), Copyright (C) 2011-2015 Michael A. Reid
 * e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/*
 * TLS do Android Auto (SEM Android: testado no computador com um "celular falso").
 * A central é o CLIENTE TLS e se apresenta com o certificado de referência do Google que os receptores
 * abertos usam (assets/aa/). O certificado do celular não é conferido (como no Headunit Revived).
 */
object AaTls {
    /** Contexto TLS com o certificado e a chave (PEM). Criado uma vez (guarda as sessões para reconectar mais rápido). */
    fun contexto(certPem: InputStream, chavePem: InputStream): SSLContext {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certPem) as X509Certificate
        val texto = chavePem.bufferedReader().use { it.readText() }
            .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val chave = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(texto)))
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(UmaChave(cert, chave)), arrayOf(ConfiaEmTodos()), null)
        return ctx
    }

    private class ConfiaEmTodos : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** Sempre a mesma chave (cliente e, no teste, servidor). */
    class UmaChave(cert: X509Certificate, chave: PrivateKey) : X509ExtendedKeyManager() {
        private val outro: X509KeyManager
        private val apelido: String

        init {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null)
            ks.setKeyEntry("aa", chave, charArrayOf(), arrayOf(cert))
            apelido = ks.aliases().toList().firstOrNull() ?: "aa"
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, charArrayOf())
            outro = kmf.keyManagers[0] as X509KeyManager
        }

        override fun getClientAliases(t: String?, i: Array<out Principal>?): Array<String>? = outro.getClientAliases(t, i)
        override fun getServerAliases(t: String?, i: Array<out Principal>?): Array<String>? = outro.getServerAliases(t, i)
        override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?): String = apelido
        override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?): String = apelido
        override fun chooseEngineClientAlias(t: Array<out String>?, i: Array<out Principal>?, e: SSLEngine?): String = apelido
        override fun chooseEngineServerAlias(t: String?, i: Array<out Principal>?, e: SSLEngine?): String = apelido
        override fun getCertificateChain(a: String?): Array<X509Certificate>? = outro.getCertificateChain(a)
        override fun getPrivateKey(a: String?): PrivateKey? = outro.getPrivateKey(a)
    }
}

/**
 * Uma sessão TLS (uma por conexão). Todas as operações passam pela mesma trava (leitura e escrita
 * acontecem em linhas diferentes).
 */
class AaMotorTls(ctx: SSLContext, cliente: Boolean = true, protocolos: Array<String>? = null) {
    private val motor: SSLEngine = ctx.createSSLEngine("android-auto", 5277).apply {
        useClientMode = cliente
        if (protocolos != null) enabledProtocols = protocolos
    }
    private val rede = ByteBuffer.allocate(motor.session.packetBufferSize + 64)
    private val app = ByteBuffer.allocate(maxOf(motor.session.applicationBufferSize + 64, 128 * 1024))
    private var pendente = ByteArray(0)
    private val controle = ArrayList<ByteArray>()
    private val vazio = ByteBuffer.allocate(0)

    @Synchronized fun comecar() = motor.beginHandshake()

    @Synchronized fun terminouHandshake(): Boolean {
        val s = motor.handshakeStatus
        return s == HandshakeStatus.FINISHED || s == HandshakeStatus.NOT_HANDSHAKING
    }

    @Synchronized fun precisaEnviar() = tarefas() == HandshakeStatus.NEED_WRAP

    private fun tarefas(): HandshakeStatus {
        var s = motor.handshakeStatus
        var n = 0
        while (s == HandshakeStatus.NEED_TASK) {
            if (++n > 256) throw SSLException("TLS: tarefas sem fim")
            val t = motor.delegatedTask ?: break
            t.run()
            s = motor.handshakeStatus
        }
        return s
    }

    /** Aperto de mão: bytes para mandar ao celular (podem ser vários registros), ou vazio. */
    @Synchronized fun handshakeSaida(): ByteArray {
        val out = ByteArrayOutputStream()
        var voltas = 0
        while (tarefas() == HandshakeStatus.NEED_WRAP) {
            if (++voltas > 64) throw SSLException("TLS: envio sem fim")
            rede.clear()
            val r = motor.wrap(vazio, rede)
            if (r.status != Status.OK) throw SSLException("TLS: wrap ${r.status}")
            rede.flip()
            out.write(rede.array(), 0, rede.limit())
        }
        return out.toByteArray()
    }

    /** Aperto de mão: bytes que vieram do celular (o que não formar um registro inteiro fica guardado em [sobra]). */
    @Synchronized fun handshakeEntrada(b: ByteArray, ini: Int, n: Int) {
        val junto = ByteArray(pendente.size + n)
        System.arraycopy(pendente, 0, junto, 0, pendente.size)
        System.arraycopy(b, ini, junto, pendente.size, n)
        val fonte = ByteBuffer.wrap(junto)
        var voltas = 0
        while (fonte.hasRemaining() && tarefas() == HandshakeStatus.NEED_UNWRAP) {
            if (++voltas > 64) throw SSLException("TLS: leitura sem fim")
            app.clear()
            val r = motor.unwrap(fonte, app)
            if (r.status == Status.BUFFER_UNDERFLOW) break
            if (r.status != Status.OK) throw SSLException("TLS: unwrap ${r.status}")
        }
        pendente = if (fonte.hasRemaining()) ByteArray(fonte.remaining()).also { fonte.get(it) } else ByteArray(0)
    }

    /** O que sobrou do aperto de mão sem ser lido (precisa de mais bytes do celular). */
    @Synchronized fun sobra() = pendente.size

    /** Cifra uma mensagem (tipo + dados). Pode gerar mais de um registro TLS. */
    @Synchronized fun cifrar(b: ByteArray, ini: Int = 0, n: Int = b.size): ByteArray {
        val fonte = ByteBuffer.wrap(b, ini, n)
        val out = ByteArrayOutputStream(n + 64)
        var voltas = 0
        while (fonte.hasRemaining()) {
            if (++voltas > 256) throw SSLException("TLS: cifrar sem fim")
            if (tarefas() == HandshakeStatus.NEED_UNWRAP) throw SSLException("TLS: o celular pediu novo aperto de mão")
            rede.clear()
            val r = motor.wrap(fonte, rede)
            if (r.status != Status.OK) throw SSLException("TLS: wrap ${r.status}")
            rede.flip()
            out.write(rede.array(), 0, rede.limit())
            if (out.size() > AaQuadro.MAX_CORPO) throw SSLException("TLS: mensagem grande demais")
        }
        return out.toByteArray()
    }

    /**
     * Decifra o corpo de um quadro. Devolve quantos bytes ficaram em [saida] (que cresce se precisar; use o
     * retorno de [buffer]). Registros só de controle (ex.: TLS 1.3 KeyUpdate) podem gerar bytes para mandar:
     * ficam em [controlePendente].
     */
    @Synchronized fun decifrar(b: ByteArray, ini: Int, n: Int): Int = decifrarInterno(ByteBuffer.wrap(b, ini, n), false)

    /**
     * Bytes do aperto de mão que chegam ABERTOS depois do fim (TLS 1.3: "ticket"), às vezes partidos entre quadros:
     * um registro partido fica guardado (junto com o que sobrou do aperto de mão, [pendente]) até o resto chegar.
     */
    @Synchronized fun decifrarAberto(b: ByteArray, ini: Int, n: Int): Int {
        val junto = ByteArray(pendente.size + n)
        System.arraycopy(pendente, 0, junto, 0, pendente.size)
        System.arraycopy(b, ini, junto, pendente.size, n)
        val fonte = ByteBuffer.wrap(junto)
        val r = decifrarInterno(fonte, true)
        if (fonte.remaining() > AaQuadro.MAX_CORPO) throw SSLException("TLS: aperto de mão atrasado grande demais")
        pendente = if (fonte.hasRemaining()) ByteArray(fonte.remaining()).also { fonte.get(it) } else ByteArray(0)
        return r
    }

    private fun decifrarInterno(fonte: ByteBuffer, guardarResto: Boolean): Int {
        app.clear()
        var voltas = 0
        var s = motor.handshakeStatus
        while (fonte.hasRemaining() || s == HandshakeStatus.NEED_WRAP || s == HandshakeStatus.NEED_TASK) {
            if (++voltas > 256) throw SSLException("TLS: decifrar sem fim")
            s = tarefas()
            if (s == HandshakeStatus.NEED_WRAP) {
                rede.clear()
                val r = motor.wrap(vazio, rede)
                if (r.status != Status.OK) throw SSLException("TLS: wrap de controle ${r.status}")
                rede.flip()
                if (rede.limit() > 0) controle.add(ByteArray(rede.limit()).also { rede.get(it) })
                s = tarefas()
                continue
            }
            if (!fonte.hasRemaining()) break
            val r = motor.unwrap(fonte, app)
            if (r.status == Status.BUFFER_UNDERFLOW && guardarResto) break
            if (r.status != Status.OK) throw SSLException("TLS: unwrap ${r.status}")
            s = tarefas()
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) throw SSLException("TLS: unwrap parado")
        }
        return app.position()
    }

    /** Buffer com o texto decifrado (válido até a próxima chamada de [decifrar]). */
    fun buffer(): ByteArray = app.array()

    @Synchronized fun controlePendente(): List<ByteArray> = if (controle.isEmpty()) emptyList() else ArrayList(controle).also { controle.clear() }

    @Synchronized fun temControle() = controle.isNotEmpty()

    /**
     * Cifra uma mensagem e devolve ANTES dela os registros de controle pendentes, tudo sob a mesma trava: assim a
     * ordem de envio é a mesma da numeração do TLS (um KeyUpdate criado pela leitura nunca sai depois de uma
     * mensagem cifrada depois dele).
     */
    @Synchronized fun cifrarComControle(b: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val ctl = controlePendente()
        return ctl to cifrar(b)
    }

    /** Para o registro: versão do TLS, cifra e estado (diagnóstico de quedas). */
    @Synchronized fun descricao(): String = try {
        "${motor.session.protocol} ${motor.session.cipherSuite} · aperto=${motor.handshakeStatus}" +
            " · entrada fechada=${motor.isInboundDone} · saída fechada=${motor.isOutboundDone}"
    } catch (t: Throwable) { "?" }

    fun fechar() { try { motor.closeOutbound() } catch (_: Exception) { } }
}
