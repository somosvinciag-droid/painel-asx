package br.com.leonardo.painelasx

import android.content.Context
import android.os.Build

/**
 * Relatorio do que a central tem e do que ela faz, para o Leonardo mandar ao Claude.
 * Nao inclui contas, senhas, contatos nem mensagens.
 */
object Diagnostico {
    private val SUSPEITOS = listOf(
        "can", "car", "avm", "dvr", "camera", "reverse", "radio", "mcu", "vehicle",
        "syu", "fyt", "microntek", "hct", "zlink", "carbit", "tlink", "autokit",
        "bt", "bluetooth", "steer", "canbus", "obd", "tpms", "launcher"
    )

    /** [completo] = também as strings do dex do ZLink (só quando o Claude pede o diagnóstico pelo conector). */
    fun texto(ctx: Context, completo: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("=== Painel ASX — diagnóstico ===\n")
        sb.append("app: v").append(BuildConfig.VERSION_CODE).append('\n')
        sb.append("central: ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL)
            .append(" / ").append(Build.PRODUCT).append(" / ").append(Build.HARDWARE).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("firmware: ").append(Build.DISPLAY).append('\n')
        sb.append("acessibilidade: ").append(if (Controle.ligado(ctx)) "ligada" else "DESLIGADA").append('\n')
        sb.append("abrir apps sozinho (sobrepor): ").append(if (android.provider.Settings.canDrawOverlays(ctx)) "permitido" else "NÃO permitido").append('\n')
        sb.append("instalar atualizações: ")
            .append(if (ctx.packageManager.canRequestPackageInstalls()) "permitido" else "NÃO permitido").append('\n')
        sb.append("rotina: ").append(if (Rotina.ligada(ctx)) "ligada" else "desligada")
            .append(" | música=").append(Apps.nomeDe(ctx, Rotina.musica(ctx)))
            .append(" | navegação=").append(Apps.nomeDe(ctx, Rotina.navegacao(ctx)))
            .append(" | volume=").append(Rotina.volume(ctx)).append("%\n")

        sb.append("\n--- apps na tela (nome = pacote) ---\n")
        Apps.listar(ctx).forEach { sb.append(it.nome).append(" = ").append(it.pacote).append('\n') }

        sb.append("\n--- pacotes do sistema que parecem do carro ---\n")
        @Suppress("DEPRECATION")
        val todos = ctx.packageManager.getInstalledPackages(0).map { it.packageName }.sorted()
        todos.filter { p ->
            val partes = p.lowercase().split('.', '_')
            SUSPEITOS.any { s -> partes.any { it == s || (s.length > 3 && it.contains(s)) } }
        }.forEach { sb.append(it).append('\n') }

        sb.append("\n--- celular ---\n")
        sb.append(try { Celular.diagnostico(ctx) } catch (e: Exception) { "(não consegui ler: ${e.javaClass.simpleName})\n" })

        sb.append("\n--- android auto ---\n")
        sb.append(try { AndroidAuto.diagnostico() } catch (e: Exception) { "(não consegui ler: ${e.javaClass.simpleName})\n" })

        sb.append("\n--- cabo USB ---\n")
        sb.append(try { CaboUsb.diagnostico(ctx) } catch (e: Exception) { "(não consegui ler: ${e.javaClass.simpleName})\n" })

        sb.append("\n--- zlink ---\n")
        sb.append(try { br.com.leonardo.painelasx.zlink.DiagnosticoZlink.paraDiagnostico(ctx, completo) } catch (e: Throwable) { "(não consegui ler: ${e.javaClass.simpleName})\n" })

        sb.append("\n--- abriu na partida ---\n")
        sb.append(try { Partida.texto(ctx) } catch (e: Exception) { "(não consegui ler: ${e.javaClass.simpleName})\n" })

        sb.append("\n--- o que aconteceu (últimas 120 linhas) ---\n")
        Registro.ultimas(120).forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
