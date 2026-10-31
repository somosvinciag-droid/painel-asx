package br.com.leonardo.painelasx

import android.content.Context
import java.security.SecureRandom

/** Ajustes do Leonardo (mudam por voz ou na tela). */
object Config {
    private fun p(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    // Velocidade
    fun limite(ctx: Context) = p(ctx).getInt("limite", 110)
    fun mudarLimite(ctx: Context, v: Int) = p(ctx).edit().putInt("limite", v.coerceIn(30, 200)).apply()

    // Mapa do Painel: rotas no mapa próprio (padrão) ou no Waze; vista 3D (padrão) ou 2D
    fun rotasNoPainel(ctx: Context) = p(ctx).getBoolean("rotas_painel", true)
    fun mudarRotasNoPainel(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("rotas_painel", v).apply()
    fun mapa3d(ctx: Context) = p(ctx).getBoolean("mapa_3d", true)
    fun mudarMapa3d(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("mapa_3d", v).apply()

    // Avisos no celular (ntfy): um "canal" secreto só dele
    fun topicoNtfy(ctx: Context): String {
        val atual = p(ctx).getString("ntfy", null)
        if (atual != null) return atual
        val b = ByteArray(9).also { SecureRandom().nextBytes(it) }
        val t = "painel-asx-" + b.joinToString("") { "%02x".format(it) }
        p(ctx).edit().putString("ntfy", t).apply()
        return t
    }

    // Jarvis (o servidor diz se a conversa por API está ligada)
    @Volatile var jarvisConversa = false
    @Volatile var jarvisAtlas = false

    /** Tudo o que vale guardar no backup. */
    val ARQUIVOS_PREFS = listOf("config", "rotina", "barra", "favoritos", "carro", "notas_fila", "tela", "atualizacao")
}
