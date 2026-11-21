package br.com.leonardo.painelasx.robo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Recebe imagem/vídeo (de uma URL ou em pedaços pelo servidor) e põe na galeria (MediaStore, sem permissão). */
object Galeria {

    private fun tipoDe(nome: String): String {
        val ext = nome.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    fun receber(ctx: Context, a: JSONObject): String {
        val nome = a.optString("nome").ifBlank { throw Exception("falta o nome") }
        val mime = tipoDe(nome)
        val video = mime.startsWith("video/")
        val imagem = mime.startsWith("image/")
        val audio = mime.startsWith("audio/")
        val pastaPadrao = when {
            video -> "Movies/Painel"
            imagem -> "Pictures/Painel"
            audio -> "Music/Painel"
            else -> "Download/Painel"
        }
        val pasta = a.optString("pasta").trim().trim('/').ifBlank { pastaPadrao }
        val colecao: Uri = when {
            video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            imagem -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            audio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val agora = System.currentTimeMillis()
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, pasta)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, agora / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, agora / 1000)
            if (imagem || video) put(MediaStore.MediaColumns.DATE_TAKEN, agora)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(colecao, v) ?: throw Exception("a galeria recusou o arquivo")
        Estado.ultimoArquivo = "$nome: recebendo (${App.hora()})"
        var bytes = 0L
        try {
            cr.openOutputStream(uri)?.use { saida ->
                bytes = if (a.optString("url").isNotBlank()) baixarUrl(a.getString("url"), saida)
                else baixarPedacos(ctx, a.optString("id"), a.optInt("total"), saida)
            } ?: throw Exception("não consegui abrir o arquivo na galeria")
            val fim = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            cr.update(uri, fim, null, null)
        } catch (e: Exception) {
            // Arquivo pela metade: tira da galeria (é só a entrada que este app acabou de criar).
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            Estado.ultimoArquivo = "$nome: FALHOU (${e.message})"
            throw e
        }
        val kb = bytes / 1024
        Estado.ultimoArquivo = "$nome: salvo em $pasta ($kb KB, ${App.hora()})"
        return "salvei $nome na galeria ($pasta, $kb KB)"
    }

    private fun baixarUrl(url: String, saida: OutputStream): Long {
        var endereco = url
        repeat(5) {
            val c = URL(endereco).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            try {
                val cod = c.responseCode
                if (cod in 300..399) {
                    endereco = c.getHeaderField("Location") ?: throw Exception("redirecionamento sem destino")
                    return@repeat
                }
                if (cod != 200) throw Exception("o endereço respondeu $cod")
                return c.inputStream.use { it.copyTo(saida, 64 * 1024) }
            } finally {
                c.disconnect()
            }
        }
        throw Exception("redirecionamentos demais")
    }

    private fun baixarPedacos(ctx: Context, id: String, total: Int, saida: OutputStream): Long {
        if (id.isBlank() || total < 1) throw Exception("faltam id/total dos pedaços")
        var bytes = 0L
        for (n in 0 until total) {
            var ultimoErro: Exception? = null
            var feito = false
            for (tentativa in 1..4) {
                try {
                    val r = Laco.pedir(ctx, "GET", "/app/arquivo?id=$id&n=$n", null, 60_000)
                    if (r.first != 200) throw Exception("pedaço $n deu ${r.first}")
                    val b = Base64.decode(JSONObject(r.second).getString("base64"), Base64.DEFAULT)
                    saida.write(b)
                    bytes += b.size
                    feito = true
                    break
                } catch (e: Exception) {
                    ultimoErro = e
                    Thread.sleep(1500L * tentativa)
                }
            }
            if (!feito) throw ultimoErro ?: Exception("pedaço $n falhou")
            Estado.ultimoArquivo = "recebendo: pedaço ${n + 1} de $total"
        }
        return bytes
    }
}
