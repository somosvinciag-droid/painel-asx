package br.com.leonardo.painelasx

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * Canais internos da central NWD (K4811), sem root. Mesmos caminhos usados pelo projeto
 * aberto "GDM K4811 App suite" (Garagem do Madeira):
 *  - pedir à NWD para mudar uma propriedade (ex.: qual é a tela inicial padrão);
 *  - apertar as teclas do volante "de mentira" (voltar, início, volume) sem Acessibilidade;
 *  - ler as regras de "fechar apps" da central (só leitura).
 */
object Nwd {
    const val PROP_LAUNCHER = "persist.nwd.launcher.default"
    const val LAUNCHER_FABRICA = "com.android.launcher"

    // Teclas da MCU (quadro F0 06 01 01 00 00 00 KK CS)
    const val VOL_MAIS: Byte = 14
    const val VOL_MENOS: Byte = 15
    const val VOLTAR: Byte = 18
    const val INICIO: Byte = 20

    fun prop(nome: String): String = Props.ler(nome)

    /** Pede ao sistema da NWD para gravar a propriedade (confira depois com [prop]). */
    fun pedirProp(ctx: Context, nome: String, valor: String) {
        try {
            Settings.System.putString(ctx.contentResolver, "nwd_system_prop", "$nome=$valor")
        } catch (e: Exception) {
            Registro.anotar("nwd: Settings.System recusou (${e.javaClass.simpleName}); tento só o aviso")
        }
        try {
            ctx.sendBroadcast(Intent("com.nwd.action.ACTION_SET_SYSTEM_PROP")
                .putExtra("extra_prop_name", nome).putExtra("extra_prop_value", valor))
        } catch (e: Exception) {
            Registro.anotar("nwd: aviso de propriedade falhou (${e.message})")
        }
        Registro.anotar("nwd: pedi $nome=$valor")
    }

    fun tecla(ctx: Context, k: Byte) {
        val q = byteArrayOf(0xF0.toByte(), 6, 1, 1, 0, 0, 0, k, 0)
        var soma = 0
        for (i in 1 until q.size - 1) soma += q[i]
        q[q.size - 1] = soma.toByte()
        try {
            ctx.sendBroadcast(Intent("com.nwd.action.EMU_RECEIVER_MCU_PROTOCAL").putExtra("protocal", q))
        } catch (e: Exception) {
            Registro.anotar("nwd: tecla $k falhou (${e.message})")
        }
    }

    fun somosTelaInicial(ctx: Context) = prop(PROP_LAUNCHER) == ctx.packageName

    /** Tudo o que interessa saber sobre as regras da central (para o Claude conferir). */
    fun relatorio(ctx: Context): JSONObject {
        val o = JSONObject()
        for (p in listOf(PROP_LAUNCHER, "persist.nwd.autowakeup", "sys.wake.app.self.start.enable", "sys.whitelist.enable",
            "ro.build.type", "ro.debuggable", "ro.secure", "ro.adb.secure", "ro.nwd.config.path", "service.adb.tcp.port",
            "persist.adb.tcp.port", "sys.nwd.mcu.state", "ro.build.tags", "ro.boot.verifiedbootstate", "ro.boot.flash.locked",
            "ro.boot.vbmeta.device_state", "ro.treble.enabled", "ro.boot.dynamic_partitions", "ro.product.board", "ro.build.fingerprint",
            "persist.sys.usb.config", "ro.config.low_ram")) o.put(p, prop(p))
        o.put("anjo_da_guarda_ligado", try { Musica.podeVerMusica(ctx) } catch (_: Exception) { false })
        o.put("somos_tela_inicial", somosTelaInicial(ctx))
        for (k in listOf("appautorun_packagename", "whitelist_packagename", "nwd_factory_password")) {
            o.put("settings.$k", try { Settings.System.getString(ctx.contentResolver, k) ?: "" } catch (e: Exception) { "erro: ${e.javaClass.simpleName}" })
        }
        val regras = JSONObject()
        val pastas = listOfNotNull(prop("ro.nwd.config.path").ifBlank { null }?.let { "$it/app" }, "/data/nwdappconfig/app", "/nwdappconfig/app")
        for (pasta in pastas.distinct()) {
            val d = File(pasta)
            val lista = try { d.list()?.toList() } catch (_: Exception) { null }
            regras.put(pasta, if (lista == null) "não consegui listar" else lista.joinToString())
            for (nome in listOf("DisableWakeAppSelfStart.xml", "OneClearConfig.xml", "TaskWhitelist.xml", "AllowSleepAppSelfStart.xml")) {
                val f = File(d, nome)
                if (f.exists()) regras.put("$pasta/$nome", try {
                    val t = f.readText()
                    // só as linhas úteis (sem comentários longos)
                    t.lines().filter { l -> l.contains("package") || l.contains("Pack") || l.contains("delay") }.joinToString("\n").take(4000)
                } catch (e: Exception) { "sem permissão (${e.javaClass.simpleName})" })
            }
        }
        o.put("regras", regras)
        return o
    }
}

/**
 * Ler uma propriedade da central. Direto (SystemProperties, sem abrir outro programa) quando o Android deixa;
 * senão, pelo "getprop" como antes. Abrir o getprop cria um processo novo (copiar um app grande como este
 * custa caro no processador fraco da central); a tela do carro fazia isso a cada 30 s e o leitor do carro a cada 3 s.
 */
object Props {
    private val metodo: java.lang.reflect.Method? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    } catch (_: Throwable) { null }

    /** null = o Android não deixou ler direto. */
    fun direto(nome: String): String? = try { metodo?.invoke(null, nome) as? String } catch (_: Throwable) { null }

    fun peloGetprop(nome: String): String = try {
        val p = ProcessBuilder("getprop", nome).redirectErrorStream(true).start()
        val t = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        t
    } catch (_: Exception) { "" }

    fun ler(nome: String): String = direto(nome)?.trim() ?: peloGetprop(nome)
}
