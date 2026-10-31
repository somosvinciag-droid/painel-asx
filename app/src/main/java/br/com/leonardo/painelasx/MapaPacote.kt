package br.com.leonardo.painelasx

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * O pacote do mapa da região (release FIXA "mapa-1", pré-lançamento): baixado UMA vez, sozinho, quando há internet.
 * Continua de onde parou (Arquivo.baixar), confere tamanho e sha-256 e só então troca pelo arquivo de verdade.
 * Uma vez por dia olha o mapa.json (pequeno) para ver se saiu versão nova do pacote.
 */
object MapaPacote {
    private const val PACOTE = "mapa-1"
    private const val DIA = 24 * 3600_000L

    @Volatile var estado = "ainda não baixado"
        private set

    private var linha: Handler? = null
    @Volatile private var ultimaTentativa = 0L

    private fun base() = "https://github.com/${BuildConfig.REPO}/releases/download/$PACOTE/"
    fun dir(ctx: Context) = File(ctx.filesDir, "mapa")
    fun arquivo(ctx: Context) = File(dir(ctx), "regiao.bin")
    private fun ok(ctx: Context) = File(dir(ctx), "ok")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mapa", Context.MODE_PRIVATE)

    /** Instalado e inteiro (o "ok" guarda a versão e o tamanho). */
    fun pronto(ctx: Context): Boolean {
        val f = arquivo(ctx); val o = ok(ctx)
        if (!f.exists() || !o.exists()) return false
        return try {
            val j = JSONObject(o.readText())
            j.optLong("tamanho") == f.length()
        } catch (_: Exception) { false }
    }

    fun versaoInstalada(ctx: Context): Int = try { JSONObject(ok(ctx).readText()).optInt("versao") } catch (_: Exception) { 0 }

    /** Frase para o Leonardo quando o mapa ainda não está pronto. */
    fun explicacao(): String = when {
        estado.startsWith("baixando") -> "O mapa da região ainda está baixando (${estado.substringAfter("baixando ").trim()})"
        estado.startsWith("esperando") -> "O mapa da região espera a internet para baixar"
        estado.startsWith("instalando") -> "O mapa da região está sendo instalado"
        estado.startsWith("erro") -> "O mapa da região deu um problema ao baixar"
        else -> "O mapa da região ainda não foi baixado"
    }

    /**
     * Pede para baixar (ou conferir se há versão nova). Chamado no início do app (com atraso), quando a internet
     * volta e quando o Leonardo pede uma rota. Uma tentativa de cada vez, no máximo 1 a cada 15 s.
     */
    fun talvezBaixar(ctx: Context, motivo: String) {
        val app = ctx.applicationContext
        if (pronto(app) && System.currentTimeMillis() - prefs(app).getLong("conferido_em", 0) < DIA) {
            estado = "pronto"
            return
        }
        if (SystemClock.elapsedRealtime() - ultimaTentativa < 15_000) return
        ultimaTentativa = SystemClock.elapsedRealtime()
        val h = synchronized(this) {
            linha ?: Handler(HandlerThread("mapa-pacote").also { it.start() }.looper).also { linha = it }
        }
        h.post { baixar(app, motivo) }
    }

    private fun baixar(ctx: Context, motivo: String) {
        if (pronto(ctx) && System.currentTimeMillis() - prefs(ctx).getLong("conferido_em", 0) < DIA) { estado = "pronto"; return }
        try {
            val txt = Rede.get(base() + "mapa.json", 20_000)
            if (txt == null) {
                if (!pronto(ctx)) estado = "esperando a internet"
                return
            }
            val info = JSONObject(txt)
            val versao = info.getInt("versao")
            val tamanho = info.getLong("tamanho")
            val sha = info.getString("sha256")
            val gzTam = info.optLong("gz_tamanho", -1)
            if (pronto(ctx) && versaoInstalada(ctx) == versao) {
                prefs(ctx).edit().putLong("conferido_em", System.currentTimeMillis()).apply()
                estado = "pronto"
                return
            }
            val d = dir(ctx); d.mkdirs()
            val gz = File(d, "regiao.bin.gz")
            val precisa = tamanho + (if (gz.exists()) 0 else maxOf(gzTam, tamanho / 2)) + 30L * 1024 * 1024
            if (d.usableSpace < precisa) {
                estado = "erro: pouco espaço"
                Registro.anotar("ERRO mapa: pouco espaço (${d.usableSpace / 1048576} MB livres, precisa ${precisa / 1048576} MB)")
                return
            }
            Registro.anotar("mapa: baixando o pacote da região v$versao (${gzTam / 1048576} MB, uma vez só; $motivo)")
            if (!(gz.exists() && gzTam > 0 && gz.length() == gzTam)) {
                gz.delete()
                Arquivo.baixar(base() + "regiao.bin.gz", gz) { pct -> estado = "baixando $pct%" }
            }
            estado = "instalando"
            val novo = File(d, "regiao.bin.novo")
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            GZIPInputStream(gz.inputStream().buffered(256 * 1024)).use { i ->
                FileOutputStream(novo).use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); total += n
                    }
                    o.flush(); o.fd.sync()
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (total != tamanho || !hex.equals(sha, ignoreCase = true)) {
                novo.delete(); gz.delete()
                estado = "erro: pacote conferido e diferente (baixo de novo)"
                Registro.anotar("ERRO mapa: pacote diferente do esperado ($total bytes, sha ${hex.take(12)}); apago e baixo de novo depois")
                return
            }
            if (!novo.renameTo(arquivo(ctx))) throw Exception("não consegui trocar o arquivo do mapa")
            Arquivo.gravarSeguro(ok(ctx), JSONObject().put("versao", versao).put("tamanho", tamanho).put("sha256", sha).toString())
            gz.delete()
            prefs(ctx).edit().putLong("conferido_em", System.currentTimeMillis()).apply()
            estado = "pronto"
            Registro.anotar("mapa: pacote da região v$versao instalado (${tamanho / 1048576} MB)")
            Navegacao.pacoteNovo()
        } catch (e: Exception) {
            estado = if (pronto(ctx)) "pronto" else "esperando a internet"
            Registro.anotar("mapa: download parou (${e.javaClass.simpleName}: ${e.message}); continua depois de onde parou")
        }
    }
}
