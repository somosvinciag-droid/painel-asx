package br.com.leonardo.painelasx

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Le tudo o que a central JA deixa ler (sem quebrar nada nem pedir poderes especiais):
 *  - "mapa": nomes dos avisos/servicos que os apps de fabrica (NWD, camera, CAN...) declaram;
 *  - escuta esses avisos e guarda os ultimos valores que chegam (re, farol, freio de mao...);
 *  - GPS da central (velocidade, posicao, direcao);
 *  - re engatada (quando a tela da camera de re abre);
 *  - propriedades internas da central ligadas ao carro.
 */
object LeitorCarro {

    /** Pacotes de fabrica que interessam (mexem com o carro). */
    private val FABRICA = listOf(
        "com.nwd", "com.unisoc.reversecamera", "com.ivicar.avm", "com.android.car.setting",
        "com.bt.bc03", "com.txznet", "com.zjinnova.zlink", "com.sprd.camera"
    )
    private val TELAS_CAMERA = listOf("com.nwd.backcar", "com.unisoc.reversecamera", "com.ivicar.avm")
    /** A tela da câmera pisca sozinha quando a central liga/acorda: ignora a ré nesse começo. */
    @Volatile private var ignorarReAte = 0L
    @Volatile private var estadoMcu = ""

    @Volatile private var iniciado = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var ultimoModoCarro = 0L
    private val sinais = LinkedHashMap<String, JSONObject>()   // acao -> {hora, dados, vezes}
    private val ultimaAnotacao = HashMap<String, Long>()
    @Volatile private var acoesOuvidas = 0
    @Volatile private var gps: JSONObject? = null
    @Volatile private var re = false
    @Volatile private var reDesde = 0L

    // ------------------------------------------------------------------ inicio

    /** Pode ser chamado fora da linha principal (lê o arquivo de viagens). O GPS só liga depois de ler as viagens. */
    @Synchronized
    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        val app = ctx.applicationContext
        appCtx = app
        ignorarReAte = System.currentTimeMillis() + 60_000
        Carro.iniciar(app)
        medirInicio("viagens") { Viagens.iniciar(app) }
        Thread({
            try {
                val acoes = acoesDaFabricaGuardadas(app)
                ouvir(app, acoes)
                Registro.anotar("leitor: ouvindo ${acoes.size} avisos da central")
            } catch (e: Throwable) {
                Registro.anotar("ERRO leitor: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "leitor").start()
        ligarGps(app)
        Thread({ vigiarPropriedades() }, "leitor-props").apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ ajustes internos que mudam

    /** Ajustes internos da central que podem mudar com o carro (ré, faróis, chave...). */
    private val PALAVRAS_SINAL = listOf(
        "reverse", "backcar", "back.car", "quickcar", "acc", "illum", "light", "lamp", "brake",
        "gear", "mcu", "nwd.car", "park", "radar", "door", "speed", "steer", "key"
    )
    private val propsAtuais = HashMap<String, String>()
    private val mudancas = ArrayDeque<String>()

    private fun lerPropsFiltradas(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { l ->
                val i = l.indexOf("]: [")
                if (l.startsWith("[") && i > 0) {
                    val k = l.substring(1, i)
                    val kl = k.lowercase()
                    if (!kl.contains("boottime") && PALAVRAS_SINAL.any { kl.contains(it) }) {
                        m[k] = l.substring(i + 4).trimEnd(']')
                    }
                }
            }
            p.waitFor()
        } catch (_: Exception) {
        }
        return m
    }

    /**
     * Quais ajustes ler direto (sem abrir o getprop). A lista vem de uma leitura completa pelo getprop,
     * repetida a cada 60 s (acha ajustes novos); entre elas, de 3 em 3 s, só os ajustes já conhecidos, lidos direto.
     * Só lê direto depois de conferir que dá o mesmo resultado do getprop; senão faz como antes (getprop a cada 3 s).
     */
    private fun lerDireto(chaves: Collection<String>): Map<String, String>? {
        val m = HashMap<String, String>()
        for (k in chaves) {
            m[k] = (Props.direto(k) ?: return null).trimEnd(']')
        }
        return m
    }

    private fun vigiarPropriedades() {
        var primeira = true
        var chaves: Collection<String> = emptyList()
        var direto = false
        var avisouDireto = false
        var ultimaCompleta = 0L
        while (true) {
            val agora = android.os.SystemClock.elapsedRealtime()
            val rapida = if (direto && agora - ultimaCompleta < 60_000) lerDireto(chaves) else null
            val novas = rapida ?: lerPropsFiltradas().also { m ->
                ultimaCompleta = agora
                chaves = m.keys
                val conferida = if (m.isEmpty()) null else lerDireto(m.keys)
                direto = conferida != null && conferida == m
                if (direto && !avisouDireto) {
                    avisouDireto = true
                    Registro.anotar("leitor: ajustes lidos direto (getprop só 1 vez por minuto)")
                }
            }
            synchronized(this) {
                for ((k, v) in novas) {
                    val antes = propsAtuais[k]
                    if (!primeira && antes != v) {
                        val linha = "$k: ${antes ?: "(novo)"} → $v"
                        Registro.anotar("ajuste mudou: $linha")
                        mudancas.addLast(System.currentTimeMillis().toString() + " " + linha)
                        while (mudancas.size > 80) mudancas.removeFirst()
                        val kl = k.lowercase()
                        if (k == "sys.nwd.mcu.state") Rede.naTela { mudouMcu(v) }
                        if (kl.contains("reverse") || kl.contains("backcar")) {
                            Registro.anotar("carro: possível mudança de ré ($k=$v)")
                        }
                    }
                    propsAtuais[k] = v
                }
            }
            primeira = false
            try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
        }
    }

    /** Faróis acesos, se a central mostrar isso num ajuste interno ("illum"/"lamp"); null = não sei. */
    @Synchronized
    fun faroisAcesos(): Boolean? {
        val k = propsAtuais.keys.firstOrNull { val x = it.lowercase(); x.contains("illum") || x.contains("headlamp") || x.contains("backlight.car") }
            ?: return null
        val v = propsAtuais[k]?.trim()?.lowercase() ?: return null
        return when (v) { "1", "true", "on" -> true; "0", "false", "off" -> false; else -> null }
    }

    // ------------------------------------------------------------------ mapa da central

    private fun pacotesDaFabrica(ctx: Context) =
        @Suppress("DEPRECATION")
        ctx.packageManager.getInstalledApplications(0)
            .filter { a -> FABRICA.any { a.packageName.startsWith(it) } }
            .sortedBy { it.packageName }

    /** Textos guardados no "manifesto" de cada app de fabrica (nomes de avisos, servicos, permissoes...). */
    private fun textosDoManifesto(apk: String): List<String> {
        ZipFile(apk).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: return emptyList()
            val b = z.getInputStream(e).readBytes()
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val ini = 8
            if (b.size < ini + 28 || (bb.getShort(ini).toInt() and 0xffff) != 0x0001) return emptyList()
            val cab = bb.getShort(ini + 2).toInt() and 0xffff
            val n = bb.getInt(ini + 8)
            val flags = bb.getInt(ini + 16)
            val inicioTextos = bb.getInt(ini + 20)
            val utf8 = (flags and 0x100) != 0
            val lista = ArrayList<String>(n.coerceAtMost(20000))
            for (i in 0 until n.coerceAtMost(20000)) {
                try {
                    val pos = ini + inicioTextos + bb.getInt(ini + cab + i * 4)
                    lista.add(if (utf8) lerUtf8(b, pos) else lerUtf16(bb, pos))
                } catch (_: Exception) {
                }
            }
            return lista
        }
    }

    private fun lerUtf8(b: ByteArray, inicio: Int): String {
        var o = inicio
        o += if ((b[o].toInt() and 0x80) != 0) 2 else 1
        var tam = b[o].toInt() and 0xff
        if ((tam and 0x80) != 0) {
            tam = ((tam and 0x7f) shl 8) or (b[o + 1].toInt() and 0xff)
            o += 2
        } else {
            o += 1
        }
        return String(b, o, tam, Charsets.UTF_8)
    }

    private fun lerUtf16(bb: ByteBuffer, inicio: Int): String {
        var o = inicio
        var tam = bb.getShort(o).toInt() and 0xffff
        if ((tam and 0x8000) != 0) {
            tam = ((tam and 0x7fff) shl 16) or (bb.getShort(o + 2).toInt() and 0xffff)
            o += 4
        } else {
            o += 2
        }
        val sb = StringBuilder(tam)
        for (k in 0 until tam) sb.append(bb.getChar(o + k * 2))
        return sb.toString()
    }

    private val NOME = Regex("^[A-Za-z0-9_.$]{6,140}$")

    private fun ehAcao(t: String): Boolean {
        if (!NOME.matches(t) || !t.contains('.')) return false
        if (t.startsWith("android.intent.action.") || t.startsWith("android.permission.")) return false
        val l = t.lowercase()
        return l.contains(".action.") || t.contains("ACTION") || l.endsWith(".action") ||
            (t.startsWith("com.nwd") && t.substringAfterLast('.').let { it.isNotEmpty() && it == it.uppercase() && it.any { c -> c.isLetter() } })
    }

    /** Lista de nomes de avisos ("acoes") que os apps de fabrica usam. */
    fun acoesDaFabrica(ctx: Context): List<String> {
        val acoes = sortedSetOf<String>()
        for (a in pacotesDaFabrica(ctx)) {
            try {
                textosDoManifesto(a.sourceDir).filter { ehAcao(it) }.forEach { acoes.add(it) }
            } catch (_: Exception) {
            }
        }
        return acoes.toList().take(250)
    }

    /**
     * Igual a [acoesDaFabrica], mas guarda o resultado num arquivo: abrir e ler os APKs de fábrica a cada vez que o
     * app abre (muitas vezes por dia, porque a central fecha o app ao dormir) gastava ~1 s de processador bem na partida.
     * Lê de novo só se algum app de fábrica mudou (caminho, tamanho ou data do APK) ou se o Painel ASX foi atualizado.
     */
    private fun acoesDaFabricaGuardadas(ctx: Context): List<String> {
        val f = java.io.File(ctx.filesDir, "acoes_fabrica.txt")
        val marca = "v" + BuildConfig.VERSION_CODE + ";" + pacotesDaFabrica(ctx).joinToString(";") { a ->
            val apk = java.io.File(a.sourceDir)
            "${a.packageName}|${a.sourceDir}|${apk.length()}|${apk.lastModified()}"
        }.hashCode()
        try {
            if (f.exists()) {
                val linhas = f.readLines()
                if (linhas.size > 1 && linhas[0] == marca) return linhas.drop(1).filter { it.isNotBlank() }
            }
        } catch (_: Exception) { }
        val acoes = acoesDaFabrica(ctx)
        try { Arquivo.gravarSeguro(f, marca + "\n" + acoes.joinToString("\n") + "\n") } catch (_: Exception) { }
        return acoes
    }

    /** O "mapa" completo, para o Claude estudar. */
    fun mapa(ctx: Context): String {
        val sb = StringBuilder("=== Mapa da central (apps de fábrica) ===\n")
        for (a in pacotesDaFabrica(ctx)) {
            sb.append("\n# ").append(a.packageName).append("  (").append(a.sourceDir).append(")\n")
            try {
                val textos = textosDoManifesto(a.sourceDir).filter { NOME.matches(it) && it.contains('.') }.distinct()
                val acoes = textos.filter { ehAcao(it) }
                val perms = textos.filter { it.contains("permission", true) && !it.startsWith("android.permission.") }
                val outros = textos.filter { it !in acoes && it !in perms }.take(60)
                if (acoes.isNotEmpty()) sb.append("  avisos: ").append(acoes.joinToString(", ")).append('\n')
                if (perms.isNotEmpty()) sb.append("  permissões próprias: ").append(perms.joinToString(", ")).append('\n')
                if (outros.isNotEmpty()) sb.append("  outros nomes: ").append(outros.joinToString(", ")).append('\n')
            } catch (e: Exception) {
                sb.append("  (não consegui ler: ${e.javaClass.simpleName})\n")
            }
            if (sb.length > 90_000) { sb.append("\n(cortado: grande demais)\n"); break }
        }
        sb.append("\n=== Propriedades da central ligadas ao carro ===\n")
        propriedades().forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    // ------------------------------------------------------------------ escuta

    private fun ouvir(ctx: Context, acoes: List<String>) {
        if (acoes.isEmpty()) return
        val f = IntentFilter()
        acoes.forEach { f.addAction(it) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = guardarSinal(i)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(r, f)
        }
        acoesOuvidas = acoes.size
    }

    private fun valor(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is BooleanArray -> v.contentToString()
        is ByteArray -> v.take(48).joinToString(" ") { "%02x".format(it) } + if (v.size > 48) " …(${v.size} bytes)" else ""
        is Array<*> -> v.contentToString()
        is Bundle -> "{" + v.keySet().joinToString(", ") { k -> "$k=${valor(@Suppress("DEPRECATION") v.get(k))}" } + "}"
        else -> v.toString()
    }.take(600)

    @Synchronized
    private fun guardarSinal(i: Intent) {
        val acao = i.action ?: return
        val agora = System.currentTimeMillis()
        val ex = i.extras
        val dados = if (ex == null) "" else ex.keySet().joinToString(", ") { k ->
            "$k=" + try { valor(@Suppress("DEPRECATION") ex.get(k)) } catch (_: Exception) { "?" }
        }.take(1500)
        val antes = sinais[acao]
        sinais[acao] = JSONObject().put("hora", agora).put("dados", dados).put("vezes", (antes?.optInt("vezes") ?: 0) + 1)
        if (acao == "android.hardware.usb.action.USB_DEVICE_ATTACHED") modoCarro(dados)
        if (acao == "com.nwd.action.ACTION_MCU_STATE_CHANGE") {
            Regex("extra_mcu_state=(\\d+)").find(dados)?.groupValues?.get(1)?.let { mudouMcu(it) }
        }
        if (agora - (ultimaAnotacao[acao] ?: 0L) > 60_000) {
            ultimaAnotacao[acao] = agora
            Registro.anotar("sinal do carro: $acao ${dados.take(160)}".trim())
        }
    }

    /** Modo Carro: ligou o celular no USB -> libera memória e roda a rotina (no máximo 1x a cada 5 min). */
    private fun modoCarro(dados: String) {
        val ctx = appCtx ?: return
        val agora = System.currentTimeMillis()
        if (agora - ultimoModoCarro < 5 * 60_000) return
        ultimoModoCarro = agora
        val nome = Regex("mProductName=([^,]+)").find(dados)?.groupValues?.get(1) ?: "aparelho USB"
        Registro.anotar("modo carro: $nome conectado — Turbo e rotina")
        Thread {
            try {
                Thread.sleep(4000) // deixa o Android Auto/USB se acertar primeiro
                Turbo.limpar(ctx)
                Rotina.talvezRodar(ctx, "celular conectado")
            } catch (_: Exception) {
            }
        }.start()
    }

    /**
     * A central não reinicia sempre: com o carro desligado ela "dorme" (estado 3) e depois acorda.
     * Dormiu -> fecha a viagem. Acordou -> Jarvis dá as boas-vindas.
     */
    fun mudouMcu(novo: String) {
        val antes = estadoMcu
        if (novo == antes) return
        estadoMcu = novo
        val ctx = appCtx ?: return
        if (novo == "3") {
            Registro.anotar("central: dormiu (carro desligado)")
            try { Viagens.centralDesligando() } catch (_: Exception) { }
            Despertador.marcar(ctx)
        } else if (antes == "3") {
            Registro.anotar("central: acordou (carro ligado)")
            ignorarReAte = System.currentTimeMillis() + 45_000
            Rede.naTela { Jarvis.partida(ctx) }
        }
    }

    fun notarTela(pacote: String) {
        val naCamera = TELAS_CAMERA.any { pacote.startsWith(it) }
        if (naCamera && System.currentTimeMillis() < ignorarReAte) return
        val agora = naCamera
        if (agora != re) {
            re = agora
            reDesde = System.currentTimeMillis()
            Registro.anotar(if (agora) "carro: ré engatada (câmera abriu)" else "carro: saiu da ré")
            try { Carro.re(agora) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ GPS

    private val ouvinteGps = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            gps = JSONObject()
                .put("velocidade_kmh", if (l.hasSpeed()) Math.round(l.speed * 3.6 * 10) / 10.0 else JSONObject.NULL)
                .put("direcao_graus", if (l.hasBearing()) Math.round(l.bearing) else JSONObject.NULL)
                .put("lat", Math.round(l.latitude * 100000) / 100000.0)
                .put("lon", Math.round(l.longitude * 100000) / 100000.0)
                .put("altitude_m", if (l.hasAltitude()) Math.round(l.altitude) else JSONObject.NULL)
                .put("precisao_m", if (l.hasAccuracy()) Math.round(l.accuracy) else JSONObject.NULL)
                .put("hora", l.time)
            try { Viagens.novaPosicao(l) } catch (_: Exception) { }
            try { Carro.posicao(l) } catch (e: Exception) { Registro.anotar("ERRO carro: ${e.javaClass.simpleName}: ${e.message}") }
        }
        @Deprecated("Android antigo ainda chama isto")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun ligarGps(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ouvinteGps, Looper.getMainLooper())
            Registro.anotar("leitor: GPS ligado")
        } catch (e: Exception) {
            Registro.anotar("leitor: GPS não ligou (${e.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------------ propriedades

    fun propriedades(): List<String> {
        val chaves = listOf("nwd", "can", "car", "acc", "reverse", "vehicle", "mcu", "illum", "brake", "ro.product.model", "ro.build.display", "persist.sys.")
        return try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val linhas = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            linhas.filter { l -> val x = l.lowercase(); chaves.any { x.contains(it) } }.take(120)
        } catch (e: Exception) {
            listOf("(não consegui ler: ${e.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------------ resumo

    @Synchronized
    fun dados(ctx: Context): JSONObject {
        val sinaisJson = JSONObject()
        sinais.entries.sortedByDescending { it.value.optLong("hora") }.take(60).forEach { sinaisJson.put(it.key, it.value) }
        return JSONObject()
            .put("nwd", try { Nwd.relatorio(ctx) } catch (e: Exception) { JSONObject().put("erro", e.message) })
            .put("re_engatada", re)
            .put("re_mudou_em", if (reDesde > 0) reDesde else JSONObject.NULL)
            .put("tela_atual", Controle.ativo?.telaAtual ?: "")
            .put("gps", gps ?: (if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) "sem sinal ainda" else "sem permissão de localização"))
            .put("avisos_ouvidos", acoesOuvidas)
            .put("ajustes_vigiados", JSONObject(propsAtuais as Map<*, *>))
            .put("ajustes_que_mudaram", JSONArray(mudancas.toList()))
            .put("sinais_recebidos", sinaisJson)
            .put("propriedades", JSONArray(propriedades()))
    }
}
