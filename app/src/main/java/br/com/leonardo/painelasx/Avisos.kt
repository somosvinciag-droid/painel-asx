package br.com.leonardo.painelasx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Avisos na tela da central que pedem UM toque do Leonardo:
 *  - "Atualizacao disponivel" (ele aprova a instalacao);
 *  - "Controle desligado" (abre direto a tela da Acessibilidade para religar).
 * Nada aqui acontece sem o toque dele.
 */
object Avisos {
    private const val CANAL = "painel_avisos"
    const val ID_ATUALIZACAO = 101
    const val ID_CONTROLE = 102
    const val ACAO_ATUALIZAR = "br.com.leonardo.painelasx.ATUALIZAR"

    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Avisos do Painel ASX", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Atualização disponível e Controle desligado."
                }
            )
        }
        return nm
    }

    private fun base(ctx: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANAL) else @Suppress("DEPRECATION") Notification.Builder(ctx)

    fun atualizacaoDisponivel(ctx: Context, versao: String) {
        val abrir = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).setAction(ACAO_ATUALIZAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = base(ctx)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Painel ASX: atualização $versao disponível")
            .setContentText("Toque para atualizar agora.")
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .build()
        nm(ctx).notify(ID_ATUALIZACAO, n)
    }

    fun controleDesligado(ctx: Context) {
        val componente = ctx.packageName + "/" + Controle::class.java.name
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Em varios Androids isto ja destaca o "Painel ASX" na lista.
        i.putExtra(":settings:fragment_args_key", componente)
        i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", componente) })
        val abrir = PendingIntent.getActivity(ctx, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = base(ctx)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("⚠️ Painel ASX: Controle desligado")
            .setContentText("Toque aqui, ligue a chave do Painel ASX e toque em Permitir.")
            .setOngoing(true)
            .setContentIntent(abrir)
            .build()
        nm(ctx).notify(ID_CONTROLE, n)
    }

    fun limpar(ctx: Context, id: Int) = nm(ctx).cancel(id)
}
