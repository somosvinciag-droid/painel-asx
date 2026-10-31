package br.com.leonardo.painelasx.celular

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Acesso às notificações do celular. Serve para três coisas:
 *  1. liberar a leitura da música (sessão de mídia);
 *  2. avisos de mensagens (WhatsApp, Mensagens, Telegram) — só são mandados se o carro tiver ligado
 *     "Ler mensagens", e NUNCA são guardados no celular nem mandados para a internet;
 *  3. o nome de quem está ligando (pelo aviso de chamada).
 * O Android religa este serviço sozinho; quando ele volta, religa a Ponte.
 */
class Ouvinte : NotificationListenerService() {
    companion object {
        @Volatile var conectado = false
            private set

        private val APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.google.android.apps.messaging" to "Mensagens",
            "com.android.mms" to "Mensagens",
            "org.telegram.messenger" to "Telegram"
        )

        /** Últimas mensagens já mandadas (só o "resumo" numérico, para não repetir). */
        private val vistos = LinkedHashSet<Int>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        conectado = true
        Ponte.iniciar(applicationContext, comMicrofone = false)
        Ponte.h.post { MusicaCel.contexto(applicationContext); MusicaCel.iniciar(applicationContext) }
    }

    override fun onListenerDisconnected() {
        conectado = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification ?: return
            if (n.category == Notification.CATEGORY_CALL) {
                val quem = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                Ponte.h.post { Ponte.chamada(quem) }
                return
            }
            val app = APPS[sbn.packageName] ?: return
            if (!Ponte.querMensagens || !Ponte.ligadoAoCarro()) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (sbn.isOngoing) return
            if (System.currentTimeMillis() - sbn.postTime > 60_000) return
            val ex = n.extras ?: return
            val (remetente, texto) = ultimaMensagem(ex)
            val conversa = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            val titulo = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val quem = when {
                conversa != null && remetente != null -> "$remetente, no grupo $conversa"
                remetente != null -> remetente
                else -> titulo ?: return
            }.trim().take(80)
            val t = (texto ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return).trim()
            if (t.isEmpty()) return
            val resumo = (sbn.packageName + quem + t).hashCode()
            synchronized(vistos) {
                if (!vistos.add(resumo)) return
                while (vistos.size > 100) vistos.remove(vistos.first())
            }
            Ponte.enviar(JSONObject().put("t", "aviso").put("id", Integer.toHexString(sbn.key.hashCode()))
                .put("app", app).put("quem", quem).put("texto", t.take(500)).put("quando", sbn.postTime))
        } catch (_: Exception) {
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            if (sbn.notification?.category == Notification.CATEGORY_CALL) Ponte.h.post { Ponte.chamadaSumiu() }
        } catch (_: Exception) {
        }
    }

    /** Remetente e texto da última mensagem de uma conversa (estilo "MessagingStyle"), se houver. */
    private fun ultimaMensagem(ex: Bundle): Pair<String?, String?> {
        @Suppress("DEPRECATION")
        val msgs: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val ultima = msgs?.lastOrNull() as? Bundle ?: return null to null
        val texto = ultima.getCharSequence("text")?.toString()
        val remetente = ultima.getCharSequence("sender")?.toString()
            ?: (try {
                @Suppress("DEPRECATION")
                (ultima.getParcelable("sender_person") as? android.app.Person)?.name?.toString()
            } catch (_: Exception) { null })
        return remetente to texto
    }
}
