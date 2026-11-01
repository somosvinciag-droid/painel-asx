package br.com.leonardo.painelasx.zlink

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/*
 * Raio-x de um APK SEM dependências (só java.util.zip): lê o AndroidManifest.xml BINÁRIO (formato AXML do
 * aapt), as strings dos classes*.dex e as strings do resources.arsc.
 *
 * Uso na central: os APKs de /system/app são legíveis por qualquer app (é como o Android instala), então
 * RaioXApk.lerManifesto(File(applicationInfo.sourceDir)) funciona para o ZLink sem root.
 *
 * Formato (AOSP frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h):
 *  - ResChunk_header: type u16, headerSize u16, size u32 (little-endian)
 *  - RES_STRING_POOL_TYPE 0x0001, RES_TABLE_TYPE 0x0002, RES_XML_TYPE 0x0003,
 *    RES_XML_START_ELEMENT 0x0102, RES_XML_END_ELEMENT 0x0103, RES_XML_RESOURCE_MAP 0x0180, RES_TABLE_PACKAGE 0x0200
 *  - DEX: string_ids_size em 0x38, string_ids_off em 0x3C; cada string_data_item = uleb128 + MUTF-8 terminada em 0.
 * Nada aqui escreve em lugar nenhum: só leitura.
 */
object RaioXApk {

    class Elemento(val nome: String, val pai: Elemento?) {
        val atributos = LinkedHashMap<String, String>()
        val filhos = ArrayList<Elemento>()
        fun attr(n: String): String? = atributos[n] ?: atributos["android:$n"]
        fun todos(n: String): List<Elemento> = filhos.filter { it.nome == n }
    }

    // ---------------------------------------------------------------- leitura do arquivo

    /** Bytes de uma entrada do APK (null se não existir). Limite de segurança: 64 MB por entrada. */
    fun entrada(apk: File, nome: String, limite: Long = 64L * 1024 * 1024): ByteArray? =
        ZipFile(apk).use { z ->
            val e = z.getEntry(nome) ?: return null
            if (e.size > limite) throw IllegalStateException("$nome grande demais (${e.size} bytes)")
            z.getInputStream(e).use { it.readBytes() }
        }

    fun lerManifesto(apk: File): Elemento =
        manifestoDeBytes(entrada(apk, "AndroidManifest.xml") ?: throw IllegalStateException("APK sem AndroidManifest.xml"))

    // ---------------------------------------------------------------- AXML

    private const val RES_STRING_POOL = 0x0001
    private const val RES_TABLE = 0x0002
    private const val RES_XML = 0x0003
    private const val XML_START = 0x0102
    private const val XML_END = 0x0103
    private const val XML_RESMAP = 0x0180
    private const val TABLE_PACKAGE = 0x0200
    private const val SEM_INDICE = -1   // 0xFFFFFFFF

    /** Ids dos atributos android:* (public.xml do framework), para quando o nome no pool vier vazio/ofuscado. */
    private val ATRIBUTOS = mapOf(
        0x01010001 to "label", 0x01010002 to "icon", 0x01010003 to "name", 0x01010006 to "permission",
        0x01010007 to "readPermission", 0x01010008 to "writePermission", 0x01010009 to "protectionLevel",
        0x0101000b to "sharedUserId", 0x0101000d to "persistent", 0x0101000e to "enabled",
        0x01010010 to "exported", 0x01010011 to "process", 0x01010012 to "taskAffinity",
        0x01010018 to "authorities", 0x0101001b to "grantUriPermissions", 0x0101001c to "priority",
        0x0101001d to "launchMode", 0x01010024 to "value", 0x01010025 to "resource",
        0x01010026 to "mimeType", 0x01010027 to "scheme", 0x01010028 to "host", 0x01010029 to "port",
        0x0101002a to "path", 0x0101002b to "pathPrefix", 0x0101002c to "pathPattern",
        0x0101020c to "minSdkVersion", 0x01010270 to "targetSdkVersion", 0x0101021b to "versionCode",
        0x0101021c to "versionName", 0x01010505 to "directBootAware", 0x01010599 to "foregroundServiceType",
        0x01010202 to "targetActivity",
    )   // ids conferidos no core/res/res/values/public.xml (android10-release)

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /** RES_STRING_POOL: UTF-8 (flag 0x100) ou UTF-16. */
    fun pool(b: ByteArray, o: Int): Array<String> {
        val hsz = u16(b, o + 2)
        val n = u32(b, o + 8)
        val flags = u32(b, o + 16)
        val inicio = u32(b, o + 20)
        val utf8 = flags and 0x100 != 0
        val base = o + inicio
        return Array(n) { i ->
            try {
                var p = base + u32(b, o + hsz + i * 4)
                if (utf8) {
                    // tamanho em caracteres (1 ou 2 bytes) e depois em bytes (1 ou 2 bytes)
                    p += if (b[p].toInt() and 0x80 != 0) 2 else 1
                    var len = b[p].toInt() and 0xFF
                    if (len and 0x80 != 0) { len = ((len and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2 } else p += 1
                    String(b, p, len, Charsets.UTF_8)
                } else {
                    var len = u16(b, p)
                    if (len and 0x8000 != 0) { len = ((len and 0x7FFF) shl 16) or u16(b, p + 2); p += 4 } else p += 2
                    String(b, p, len * 2, Charsets.UTF_16LE)
                }
            } catch (_: Exception) { "" }
        }
    }

    private fun valor(tipo: Int, dado: Int, cru: Int, s: Array<String>): String = when {
        cru != SEM_INDICE && cru in s.indices -> s[cru]
        tipo == 0x03 && dado in s.indices -> s[dado]
        tipo == 0x12 -> if (dado != 0) "true" else "false"
        tipo == 0x10 -> dado.toString()
        tipo == 0x11 -> "0x" + Integer.toHexString(dado)
        tipo == 0x01 -> "@0x%08x".format(dado)
        tipo == 0x02 -> "?0x%08x".format(dado)
        tipo == 0x04 -> java.lang.Float.intBitsToFloat(dado).toString()
        else -> "(tipo 0x%02x) 0x%08x".format(tipo, dado)
    }

    fun manifestoDeBytes(b: ByteArray): Elemento {
        require(b.size >= 8 && u16(b, 0) == RES_XML) { "não é um XML binário do Android" }
        var strings = emptyArray<String>()
        var mapa = IntArray(0)
        val raiz = Elemento("#documento", null)
        var atual = raiz
        var o = u16(b, 2)
        val fim = minOf(b.size, u32(b, 4).takeIf { it > 0 } ?: b.size)
        while (o + 8 <= fim) {
            val tipo = u16(b, o)
            val hsz = u16(b, o + 2)
            val tam = u32(b, o + 4)
            if (tam < 8 || o + tam > fim) break
            when (tipo) {
                RES_STRING_POOL -> strings = pool(b, o)
                XML_RESMAP -> mapa = IntArray((tam - hsz) / 4) { u32(b, o + hsz + it * 4) }
                XML_START -> {
                    val e = o + hsz   // ResXMLTree_attrExt
                    val nomeIdx = u32(b, e + 4)
                    val attrInicio = u16(b, e + 8)
                    val attrTam = u16(b, e + 10).takeIf { it > 0 } ?: 20
                    val nAttr = u16(b, e + 12)
                    val el = Elemento(strings.getOrElse(nomeIdx) { "?" }, atual)
                    for (i in 0 until nAttr) {
                        val a = e + attrInicio + i * attrTam
                        val ns = u32(b, a)
                        val ni = u32(b, a + 4)
                        val cru = u32(b, a + 8)
                        val tipoV = b[a + 15].toInt() and 0xFF
                        val dado = u32(b, a + 16)
                        val porId = if (ni in mapa.indices) ATRIBUTOS[mapa[ni]] else null
                        val doPool = strings.getOrElse(ni) { "" }
                        val nome = porId ?: doPool.ifEmpty { if (ni in mapa.indices) "0x%08x".format(mapa[ni]) else "?$ni" }
                        val prefixo = if (ns != SEM_INDICE && (porId != null || (ni in mapa.indices && mapa[ni] ushr 24 == 0x01))) "android:" else ""
                        el.atributos[prefixo + nome] = valor(tipoV, dado, cru, strings)
                    }
                    atual.filhos.add(el)
                    atual = el
                }
                XML_END -> atual = atual.pai ?: raiz
            }
            o += tam
        }
        return raiz.filhos.firstOrNull { it.nome == "manifest" } ?: raiz
    }

    // ---------------------------------------------------------------- resumo legível

    private fun nivel(v: String?): String {
        val n = v?.let { if (it.startsWith("0x")) it.substring(2).toIntOrNull(16) else it.toIntOrNull() } ?: return v ?: "normal"
        val base = when (n and 0xF) { 0 -> "normal"; 1 -> "dangerous"; 2 -> "signature"; 3 -> "signature|privileged(antigo)"; else -> "base${n and 0xF}" }
        val extras = listOf(0x10 to "privileged", 0x20 to "development", 0x40 to "appop", 0x80 to "pre23", 0x100 to "installer",
            0x200 to "verifier", 0x400 to "preinstalled", 0x800 to "setup").filter { n and it.first != 0 }.map { it.second }
        return (listOf(base) + extras).joinToString("|")
    }

    private fun nomeCompleto(pacote: String, n: String?): String = when {
        n == null -> "?"
        n.startsWith(".") -> pacote + n
        !n.contains('.') -> "$pacote.$n"
        else -> n
    }

    /**
     * Texto para o diagnóstico: permissões próprias (com nível), o que o app pede, e cada componente com
     * exportado (explícito ou implícito), permissão, processo e filtros. [soExportados] = esconde os internos.
     */
    fun resumo(m: Elemento, soExportados: Boolean = false): String = buildString {
        val pacote = m.attr("package") ?: "?"
        val usesSdk = m.todos("uses-sdk").firstOrNull()
        val alvo = usesSdk?.attr("targetSdkVersion")?.toIntOrNull() ?: 0
        appendLine("pacote: $pacote · versão ${m.attr("versionName")} (${m.attr("versionCode")}) · sharedUserId=${m.attr("sharedUserId") ?: "-"}")
        appendLine("sdk: min ${usesSdk?.attr("minSdkVersion") ?: "?"} · alvo ${usesSdk?.attr("targetSdkVersion") ?: "?"}")
        val proprias = m.todos("permission").associate { (it.attr("name") ?: "?") to nivel(it.attr("protectionLevel")) }
        if (proprias.isNotEmpty()) {
            appendLine("permissões declaradas por ele:")
            proprias.forEach { (n, nv) -> appendLine("  $n = $nv") }
        }
        val pede = m.todos("uses-permission").mapNotNull { it.attr("name") }
        appendLine("pede ${pede.size} permissões: ${pede.joinToString { it.removePrefix("android.permission.") }}")
        val app = m.todos("application").firstOrNull() ?: return@buildString
        appendLine("application: " + app.atributos.filterKeys { k ->
            listOf("persistent", "process", "enabled", "directBootAware", "name", "sharedUserId").any { k.endsWith(it) }
        }.entries.joinToString { "${it.key.removePrefix("android:")}=${it.value}" })
        for (c in app.filhos) {
            if (c.nome !in setOf("activity", "activity-alias", "service", "receiver", "provider")) continue
            val filtros = c.todos("intent-filter")
            val expl = c.attr("exported")
            val exportado = when {
                expl != null -> expl == "true"
                c.nome == "provider" -> alvo in 1..16
                else -> filtros.isNotEmpty()
            }
            if (soExportados && !exportado) continue
            val perm = c.attr("permission") ?: if (c.nome == "provider") listOfNotNull(c.attr("readPermission"), c.attr("writePermission")).joinToString("/").ifEmpty { null } else null
            val permTxt = perm?.let { p -> " · permissão $p" + (proprias[p]?.let { " ($it)" } ?: "") } ?: ""
            append("${c.nome} ${nomeCompleto(pacote, c.attr("name"))}")
            if (c.nome == "activity-alias") append(" -> ${c.attr("targetActivity")}")
            append(" · exportado=" + (if (exportado) "SIM" else "não") + (if (expl == null) " (implícito)" else ""))
            append(permTxt)
            c.attr("enabled")?.let { append(" · enabled=$it") }
            c.attr("process")?.let { append(" · processo $it") }
            c.attr("authorities")?.let { append(" · authorities $it") }
            c.attr("foregroundServiceType")?.let { append(" · fgs=$it") }
            appendLine()
            for (f in filtros) {
                val acoes = f.todos("action").mapNotNull { it.attr("name") }
                val cats = f.todos("category").mapNotNull { it.attr("name") }
                val dados = f.todos("data").map { d -> d.atributos.entries.joinToString(" ") { "${it.key.removePrefix("android:")}=${it.value}" } }
                append("    filtro")
                f.attr("priority")?.let { append(" (prioridade $it)") }
                append(": ${acoes.joinToString()}")
                if (cats.isNotEmpty()) append(" | cat: ${cats.joinToString { it.removePrefix("android.intent.category.") }}")
                if (dados.isNotEmpty()) append(" | data: ${dados.joinToString(" ; ")}")
                appendLine()
            }
            for (md in c.todos("meta-data")) appendLine("    meta-data ${md.attr("name")} = ${md.attr("value") ?: md.attr("resource")}")
        }
    }

    // ---------------------------------------------------------------- DEX

    private fun uleb(b: ByteArray, p0: Int): Pair<Int, Int> {
        var p = p0; var r = 0; var s = 0
        while (true) {
            val x = b[p++].toInt() and 0xFF
            r = r or ((x and 0x7F) shl s)
            if (x and 0x80 == 0 || s >= 28) return r to p
            s += 7
        }
    }

    /** Todas as strings de um classes.dex (MUTF-8 lido como UTF-8: serve para procurar palavras). */
    fun stringsDeDex(b: ByteArray): List<String> {
        require(b.size > 0x70 && String(b, 0, 4, Charsets.US_ASCII) == "dex\n") { "não é dex" }
        val n = u32(b, 0x38)
        val off = u32(b, 0x3C)
        val r = ArrayList<String>(n)
        for (i in 0 until n) {
            try {
                val (_, p) = uleb(b, u32(b, off + i * 4))
                var f = p
                while (f < b.size && b[f] != 0.toByte()) f++
                r.add(String(b, p, f - p, Charsets.UTF_8))
            } catch (_: Exception) { }
        }
        return r
    }

    /** Procura nas strings de todos os classes*.dex. Lê um dex por vez (a central tem 2 GB). */
    fun procurarNoDex(apk: File, palavras: List<String>, max: Int = 400): List<String> {
        val nomes = ZipFile(apk).use { z -> z.entries().toList().map { it.name }.filter { it.matches(Regex("classes\\d*\\.dex")) }.sorted() }
        val achou = sortedSetOf<String>()
        for (nome in nomes) {
            val b = entrada(apk, nome) ?: continue
            for (s in stringsDeDex(b)) if (casa(s, palavras)) achou.add(s.take(200).replace('\n', ' '))
            if (achou.size > max * 3) break
        }
        return achou.take(max)
    }

    // ---------------------------------------------------------------- resources.arsc

    /** Valores (pool global) e nomes de recurso (pool de chaves) que casam com as palavras. */
    fun procurarNoArsc(apk: File, palavras: List<String>, max: Int = 300): List<String> {
        val b = entrada(apk, "resources.arsc") ?: return emptyList()
        if (u16(b, 0) != RES_TABLE) return emptyList()
        val achou = sortedSetOf<String>()
        var o = u16(b, 2)
        while (o + 8 <= b.size) {
            val tipo = u16(b, o); val tam = u32(b, o + 4)
            if (tam < 8) break
            when (tipo) {
                RES_STRING_POOL -> pool(b, o).filter { casa(it, palavras) }.forEach { achou.add("texto: " + it.take(160).replace('\n', ' ')) }
                TABLE_PACKAGE -> {
                    val chaves = u32(b, o + 8 + 4 + 256 + 8)   // keyStrings (depois de id, name[128], typeStrings, lastPublicType)
                    if (chaves > 0 && o + chaves < b.size) pool(b, o + chaves).filter { casa(it, palavras) }.forEach { achou.add("chave: $it") }
                }
            }
            o += tam
        }
        return achou.take(max)
    }

    private fun casa(s: String, palavras: List<String>): Boolean {
        val m = s.lowercase()
        return palavras.any { m.contains(it) }
    }

    /** Palavras para o ZLink: USB/acessório, conexão automática, avisos (intents), propriedades, ajustes. */
    val PALAVRAS_ZLINK = listOf(
        "usb", "accessory", "acessor", "aoa", "2d00", "2d01", "getdevicelist", "requestpermission", "controltransfer",
        "claiminterface", "autoconn", "auto_conn", "auto conn", "autostart", "auto_start", "auto start", "autolaunch",
        "auto_launch", "auto launch", "auto_run", "autorun", "wired", "cable", "zjinnova.android.intent", "zlink.action",
        "com.nwd.action", "persist.", "sys.zlink", "android_auto", "androidauto", "android auto", "carplay_enable",
        "aa_enable", "enable_aa", "aa_switch", "connect_mode", "connectmode", "link_mode", "linkmode",
    )
}
