package br.com.leonardo.painelasx.zlink

import android.content.Context
import android.content.pm.PackageManager
import br.com.leonardo.painelasx.Registro
import br.com.leonardo.painelasx.canal.AdbChave
import br.com.leonardo.painelasx.canal.AdbConexao
import br.com.leonardo.painelasx.canal.TransporteStream
import java.io.File

/*
 * Desliga o ZLINK5 UMA VEZ usando o adbd da PRÓPRIA central (127.0.0.1), com o cliente ADB que já existe
 * em canal/AdbUsb.kt (TransporteStream.tcp + AdbConexao). Sem root, sem notebook.
 *
 * Por que funciona (CONFIRMADO no AOSP android10-release):
 *  - "pm disable-user" roda como usuário shell, que tem CHANGE_COMPONENT_ENABLED_STATE; o PackageManagerService
 *    deixa o shell mudar um pacote INTEIRO entre ENABLED/DEFAULT e DISABLED_USER (setEnabledSetting).
 *  - Desligar o pacote inteiro mata o processo dele na hora e ele não volta no boot (estado gravado em
 *    /data/system/users/0/package-restrictions.xml). Desfazer: "pm enable com.zjinnova.zlink".
 *  - O adbd escuta em TCP quando service.adb.tcp.port ou persist.adb.tcp.port > 0 (adb/daemon/main.cpp).
 *  - Apps conectando no adbd por 127.0.0.1 é o que o LADB/Shizuku fazem no Android 10 depois de "adb tcpip".
 * SUPOSIÇÕES (só a central responde; o DiagnosticoZlink mostra antes): o adbd desta central escutar em TCP;
 * o aviso "Permitir depuração USB?" aparecer na tela da central (ou ro.adb.secure=0, sem aviso nenhum);
 * a NWD não bloquear "pm disable-user" para o ZLink.
 *
 * O app NUNCA toca em "Permitir": quem toca é o Leonardo (2 toques, uma vez: "Sempre permitir" + "OK").
 * Só roda quando o Leonardo toca no botão "Desligar o ZLink (uma vez)" do cartão "Celular" da MainActivity.
 * Nunca roda sozinho (nem no boot, nem pelo conector). As opções de fábrica NWD e o canal de propriedades
 * NÃO são usados aqui (regra: nada de escrever em fábrica sem o Leonardo pedir).
 * Chamar fora da linha principal (rede + espera do toque).
 */
object DesligarZlink {
    const val ZLINK = DiagnosticoZlink.ZLINK

    sealed class Resultado(val texto: String) {
        class Pronto(t: String) : Resultado(t)
        class JaEstava(t: String) : Resultado(t)
        class SemAdb(t: String) : Resultado(t)
        class Falhou(t: String) : Resultado(t)
    }

    fun estaDesligado(ctx: Context): Boolean = try {
        val s = ctx.packageManager.getApplicationEnabledSetting(ZLINK)
        s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { false }

    private fun chave(ctx: Context) = AdbChave.doArquivo(File(ctx.noBackupFilesDir, "adb/central-local.pk8"))

    /**
     * [aviso] mostra na MainActivity: "marque 'Sempre permitir deste computador' e toque em OK".
     * A porta do adb NÃO é mexida (o Painel não abriu a porta; fechar impediria o "Religar o ZLink").
     */
    fun desligar(ctx: Context, aviso: (String) -> Unit): Resultado {
        if (estaDesligado(ctx)) {
            Registro.anotar("zlink: já estava desligado")
            return Resultado.JaEstava("O ZLink já está desligado.")
        }
        val porta = portaAberta() ?: return Resultado.SemAdb(SEM_ADB)
        return try {
            AdbConexao.conectar(
                TransporteStream.tcp("127.0.0.1", porta), chave(ctx), "painelasx@central",
                esperaPermissaoMs = 120_000,
                aoPedirPermissao = {
                    Registro.anotar("zlink: a central mostrou \"Permitir depuração USB?\"; esperando o toque do Leonardo")
                    aviso("Na tela: marque \"Sempre permitir deste computador\" e toque em OK.")
                },
            ).use { c ->
                Registro.anotar("zlink: adb local conectado (porta $porta, ${c.banner.take(60)})")
                val r = c.executar("pm disable-user --user 0 $ZLINK 2>&1").trim()
                Registro.anotar("zlink: pm disable-user -> $r")
                val fs = c.executar("am force-stop $ZLINK 2>&1").trim()
                Registro.anotar("zlink: am force-stop -> ${fs.ifEmpty { "ok" }}")
                val conf = c.executar("pm list packages -d 2>&1").lines().any { it.trim() == "package:$ZLINK" }
                Registro.anotar("zlink: conferido na lista de desligados: ${if (conf) "sim" else "não"}")
                if (conf || estaDesligado(ctx)) {
                    Registro.anotar("zlink: DESLIGADO (desfazer: botão Religar o ZLink)")
                    Resultado.Pronto("Pronto: o ZLink está desligado. O cabo agora é só do Painel.")
                } else Resultado.Falhou("O Android não desligou o ZLink ($r). Nada mais foi mudado.")
            }
        } catch (e: Exception) {
            Registro.anotar("ERRO zlink: ${e.javaClass.simpleName}: ${e.message}")
            Resultado.Falhou("Não consegui (${e.message}). Nada foi mudado.")
        }
    }

    private const val SEM_ADB = "Não dá por aqui: a central não deixa o Painel usar o modo de depuração dela. Nada foi mudado."

    /** Sonda as portas (só CNXN, sem chave) e anota cada resultado. */
    private fun portaAberta(): Int? {
        for (p in DiagnosticoZlink.portasCandidatas()) {
            val r = DiagnosticoZlink.sondarAdb(p)
            Registro.anotar("zlink: sondagem 127.0.0.1:$p -> $r")
            if (r.startsWith("ADB ESCUTANDO")) return p
        }
        return null
    }

    /** Desfaz (religa o ZLink). Mesmo caminho; com a chave já autorizada ("Sempre permitir"), não pede toque. */
    fun religar(ctx: Context, aviso: (String) -> Unit = {}): Resultado {
        if (!estaDesligado(ctx)) {
            Registro.anotar("zlink: já estava ligado")
            return Resultado.JaEstava("O ZLink já está ligado.")
        }
        val porta = portaAberta() ?: return Resultado.SemAdb(SEM_ADB)
        return try {
            AdbConexao.conectar(TransporteStream.tcp("127.0.0.1", porta), chave(ctx), "painelasx@central", 120_000) {
                Registro.anotar("zlink: a central mostrou \"Permitir depuração USB?\"; esperando o toque do Leonardo")
                aviso("Na tela: marque \"Sempre permitir deste computador\" e toque em OK.")
            }.use { c ->
                val r = c.executar("pm enable $ZLINK 2>&1").trim()
                Registro.anotar("zlink: pm enable -> $r")
                if (!estaDesligado(ctx)) Resultado.Pronto("Pronto: o ZLink voltou a funcionar.")
                else Resultado.Falhou("O Android não religou o ZLink ($r).")
            }
        } catch (e: Exception) {
            Registro.anotar("ERRO zlink: ${e.javaClass.simpleName}: ${e.message}")
            Resultado.Falhou("Não consegui (${e.message}). Nada foi mudado.")
        }
    }
}
