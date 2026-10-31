package br.com.leonardo.painelasx

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings

/**
 * Tarefas de fundo, de minuto em minuto, enquanto a central está ligada:
 *  - Turbo sozinho quando a memória livre cai abaixo de 20%;
 *  - tempo/chuva a cada 30 min;
 *  - modo noite (faróis, se a central contar; senão pôr do sol) -> brilho da tela;
 *  - backup na nuvem a cada 6 h;
 *  - manda o que ficou na fila (anotações, abastecimentos) quando volta a internet.
 */
object Relogio {
    private var linha: HandlerThread? = null
    private var h: Handler? = null
    private var appCtx: Context? = null
    private var ultimoTurbo = 0L
    private var noite: Boolean? = null
    private var minuto = 0

    fun iniciar(ctx: Context) {
        if (linha != null) return
        appCtx = ctx.applicationContext
        val t = HandlerThread("relogio").also { it.start() }
        linha = t
        h = Handler(t.looper).also { it.postDelayed(tarefa, 20_000) }
    }

    private val tarefa = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            minuto++
            passo("memória") { memoria(ctx) }
            passo("clima") { Clima.atualizarSePreciso(ctx) }
            passo("noite") { modoNoite(ctx) }
            // Fora da linha do relógio: com a internet fraca o envio pode demorar minutos.
            // (Só cria a linha de envio se houver algo na fila: antes criava uma por minuto à toa.)
            passo("fila") { if (Conector.online && Fila.temAlgo(ctx)) Rede.emSegundoPlano("fila") { Fila.esvaziar(ctx) } }
            passo("backup") {
                // A hora do último backup fica guardada: a central fecha o app ao dormir, e antes cada vez que o app
                // abria o backup inteiro ia de novo depois de 10 min (não de 6 em 6 h). Se nada mudou, nem manda.
                val agora = System.currentTimeMillis()
                val ultimo = Backup.ultimoEnvio(ctx)
                if (Conector.online && minuto >= 10 && (agora - ultimo > 6 * 3600_000L || ultimo > agora)) {
                    Backup.enviar(ctx, automatico = true)
                }
            }
            h?.postDelayed(this, 60_000)
        }
    }

    private inline fun passo(nome: String, bloco: () -> Unit) {
        try { bloco() } catch (e: Throwable) { Registro.anotar("ERRO relógio/$nome: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun memoria(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (mi.totalMem <= 0) return
        val pct = mi.availMem * 100 / mi.totalMem
        val agora = System.currentTimeMillis()
        if (pct < 20 && agora - ultimoTurbo > 10 * 60_000) {
            ultimoTurbo = agora
            Registro.anotar("turbo automático: memória em $pct%")
            Turbo.limpar(ctx)
        }
        // Memória muito curta: o ouvido (~250 MB) sai e volta a carregar no próximo FALAR.
        if (pct < 12 && !Ouvido.ouvindo && Ouvido.estado == "pronto") Ouvido.liberar()
    }

    // ------------------------------------------------------------------ modo noite

    fun brilhoNoite(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE).getInt("brilho_noite", 70)
    fun brilhoDia(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE).getInt("brilho_dia", 230)

    private fun modoNoite(ctx: Context) {
        val faroes = LeitorCarro.faroisAcesos()
        val agoraNoite = faroes ?: Clima.ehNoite()
        if (agoraNoite == noite) return
        noite = agoraNoite
        Registro.anotar("modo noite: ${if (agoraNoite) "ligado" else "desligado"} (${if (faroes != null) "faróis" else "pôr do sol"})")
        if (!Settings.System.canWrite(ctx)) return
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, if (agoraNoite) brilhoNoite(ctx) else brilhoDia(ctx))
        } catch (e: Exception) {
            Registro.anotar("modo noite: não mudou o brilho (${e.javaClass.simpleName})")
        }
    }
}
