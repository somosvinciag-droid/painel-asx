package br.com.leonardo.painelasx

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gravar arquivos sem perder o conteúdo quando a central perde a energia de repente:
 * escreve numa cópia, força salvar no disco e só então troca pelo arquivo de verdade.
 * (Com writeText direto, um corte de energia no meio deixava o arquivo vazio ou cortado.)
 */
object Arquivo {
    /** [sufixo]: nome da cópia (duas linhas gravando o mesmo arquivo ao mesmo tempo usam sufixos diferentes). */
    fun gravarSeguro(destino: File, texto: String, sufixo: String = ".tmp") {
        val tmp = File(destino.parentFile, destino.name + sufixo)
        FileOutputStream(tmp).use { o ->
            o.write(texto.toByteArray(Charsets.UTF_8))
            o.flush()
            o.fd.sync()
        }
        if (!tmp.renameTo(destino)) {
            // Muito raro: se a troca falhar, grava direto (melhor do que não gravar).
            destino.writeText(texto)
            tmp.delete()
        }
    }

    /** Maior download que passa pela internet do celular pelo cabo (dados móveis). O APK do app (~1 MB) passa. */
    const val MAX_PELO_CELULAR = 20L * 1024 * 1024

    /**
     * Baixa continuando de onde parou (a internet do celular por Bluetooth pode cair no meio).
     * Só troca o ".parte" pelo arquivo de verdade quando o tamanho bate com o que o servidor disse.
     * Se os pedidos do app estão indo pelo celular pelo cabo (canal ADB, sem outra internet na central), arquivos
     * maiores que [MAX_PELO_CELULAR] (ouvido, voz) NÃO baixam: esperam outra internet (Wi-Fi/ancoragem).
     */
    fun baixar(url: String, destino: File, progresso: (Int) -> Unit = {}) {
        val tmp = File(destino.parentFile, destino.name + ".parte")
        var u = url
        var tentativas = 0
        var recomecou = false
        while (true) {
            val ja = if (tmp.exists()) tmp.length() else 0L
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "painel-asx")
                // Sem gzip: o tamanho que o servidor informa tem de ser o do arquivo.
                c.setRequestProperty("Accept-Encoding", "identity")
                if (ja > 0) c.setRequestProperty("Range", "bytes=$ja-")
                val code = c.responseCode
                if (code in 300..399) {
                    u = c.getHeaderField("Location") ?: throw Exception("redirecionamento sem endereço")
                    if (++tentativas > 6) throw Exception("redirecionamentos demais")
                    continue
                }
                if (code == 416) {
                    // "Já tenho tudo"? Só se o tamanho total que o servidor diz for igual ao do .parte.
                    val totalServidor = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    if (totalServidor != null && totalServidor == ja) break
                    if (recomecou) throw Exception("download deu 416 (${tmp.length()} bytes, servidor diz $totalServidor)")
                    tmp.delete(); recomecou = true; u = url
                    continue
                }
                if (code != 200 && code != 206) throw Exception("download deu $code")
                val continuar = code == 206 && ja > 0
                val resto = c.contentLengthLong
                val total = when {
                    continuar -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        ?: if (resto > 0) ja + resto else -1L
                    else -> resto
                }
                val peloCelular = br.com.leonardo.painelasx.canal.ProxyDoApp.emUso()
                if (peloCelular && total > MAX_PELO_CELULAR)
                    throw Exception("${destino.name} tem ${total / 1048576} MB: espero outra internet para não gastar os dados do celular")
                RandomAccessFile(tmp, "rw").use { raf ->
                    if (continuar) raf.seek(ja) else raf.setLength(0)
                    var feito = if (continuar) ja else 0L
                    var ultimo = -1
                    val buf = ByteArray(64 * 1024)
                    try {
                        c.inputStream.use { i ->
                            while (true) {
                                val n = i.read(buf); if (n < 0) break
                                raf.write(buf, 0, n); feito += n
                                if (peloCelular && total <= 0 && feito > MAX_PELO_CELULAR)
                                    throw Exception("${destino.name} passou de ${MAX_PELO_CELULAR / 1048576} MB pelos dados do celular; continuo com outra internet")
                                if (total > 0) {
                                    val pct = (feito * 100 / total).toInt()
                                    if (pct != ultimo) { ultimo = pct; progresso(pct) }
                                }
                            }
                        }
                    } finally {
                        try { raf.fd.sync() } catch (_: Exception) { }  // o que chegou fica salvo para continuar depois
                    }
                    if (total > 0 && feito != total) throw Exception("download incompleto ($feito de $total)")
                    if (total <= 0 && feito < 1024) throw Exception("download vazio")
                }
                break
            } finally {
                c.disconnect()
            }
        }
        if (!tmp.renameTo(destino)) throw Exception("não consegui guardar ${destino.name}")
    }
}
