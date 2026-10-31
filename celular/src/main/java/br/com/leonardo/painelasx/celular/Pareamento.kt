package br.com.leonardo.painelasx.celular

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Quem pode conversar com este celular. O repositório é público, então NÃO há senha no código:
 * cada central gera um id aleatório dela; na primeira vez o Leonardo toca em "Permitir" aqui no celular,
 * e só as centrais permitidas recebem dados. As outras são recusadas.
 */
object Pareamento {
    enum class Situacao { PERMITIDA, RECUSADA, NOVA }

    private const val CANAL = "pareamento"
    const val ID_AVISO = 21
    const val PERMITIR = "br.com.leonardo.painelasx.celular.PERMITIR"
    const val RECUSAR = "br.com.leonardo.painelasx.celular.RECUSAR"

    /** Pedido esperando o toque do Leonardo (só na memória). */
    @Volatile var pendente: String? = null
        private set

    private fun p(ctx: Context) = ctx.getSharedPreferences("pareamento", Context.MODE_PRIVATE)

    fun valido(id: String) = id.length in 16..64 && id.all { it.isLetterOrDigit() }

    /** Código curto para conferir com o que a central mostra ("Código desta central"). */
    fun codigo(id: String) = id.take(4).uppercase()

    fun permitidas(ctx: Context): Set<String> = p(ctx).getStringSet("permitidas", emptySet()) ?: emptySet()
    private fun recusadas(ctx: Context): Set<String> = p(ctx).getStringSet("recusadas", emptySet()) ?: emptySet()

    fun situacao(ctx: Context, id: String) = when (id) {
        in permitidas(ctx) -> Situacao.PERMITIDA
        in recusadas(ctx) -> Situacao.RECUSADA
        else -> Situacao.NOVA
    }

    fun permitir(ctx: Context, id: String) {
        if (!valido(id)) return
        p(ctx).edit()
            .putStringSet("permitidas", permitidas(ctx) + id)
            .putStringSet("recusadas", recusadas(ctx) - id)
            .apply()
        if (pendente == id) pendente = null
        nm(ctx).cancel(ID_AVISO)
        Ponte.pareamentoMudou(ctx, id, true)
    }

    fun recusar(ctx: Context, id: String) {
        if (!valido(id)) return
        p(ctx).edit()
            .putStringSet("recusadas", (recusadas(ctx) + id).toList().takeLast(20).toSet())
            .putStringSet("permitidas", permitidas(ctx) - id)
            .apply()
        if (pendente == id) pendente = null
        nm(ctx).cancel(ID_AVISO)
        Ponte.pareamentoMudou(ctx, id, false)
    }

    /** "Esquecer" todas as centrais (na tela do app). A próxima conexão pede permissão de novo. */
    fun esquecerTudo(ctx: Context) {
        p(ctx).edit().clear().apply()
        pendente = null
        Ponte.pareamentoMudou(ctx, "", false)
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    /** Uma central nova quer se ligar: aviso com "Permitir" e "Recusar". */
    fun pedir(ctx: Context, id: String) {
        val nm = nm(ctx)
        // Mesmo pedido e o aviso ainda está na barra: não repete. Se o aviso foi fechado (deslizado ou tocado)
        // e a central voltou a pedir, mostra de novo.
        val avisoNaBarra = try { nm.activeNotifications.any { it.id == ID_AVISO } } catch (_: Exception) { false }
        if (pendente == id && avisoNaBarra) return
        pendente = id
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, "Permitir o carro", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pergunta se uma central de carro pode se ligar a este celular."
            })
        }
        fun acao(acao: String, cod: Int) = PendingIntent.getBroadcast(
            ctx, cod, Intent(ctx, RespostaPareamento::class.java).setAction(acao).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val abrir = PendingIntent.getActivity(
            ctx, 30, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Permitir o Painel ASX do carro?")
            .setContentText("Central código ${codigo(id)} quer se ligar a este celular.")
            .setStyle(Notification.BigTextStyle().bigText(
                "Central código ${codigo(id)} quer se ligar a este celular (música, bateria, avisos). " +
                    "Confira o código na tela de ajustes do Painel ASX no carro."))
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Permitir", acao(PERMITIR, 31)).build())
            .addAction(Notification.Action.Builder(null, "Recusar", acao(RECUSAR, 32)).build())
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID_AVISO, n) } catch (_: Exception) { }
    }
}

/** Toque em "Permitir" ou "Recusar" no aviso. */
class RespostaPareamento : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        when (i.action) {
            Pareamento.PERMITIR -> Pareamento.permitir(c.applicationContext, id)
            Pareamento.RECUSAR -> Pareamento.recusar(c.applicationContext, id)
        }
    }
}
