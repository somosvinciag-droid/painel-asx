package br.com.leonardo.painelasx

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Barra lateral estilo Android Auto: fica por cima de qualquer app, do lado esquerdo,
 * com os apps favoritos do Leonardo. Dá para recolher (fica só uma alcinha).
 * Usa a permissão "aparecer sobre outros apps" (a mesma de abrir apps sozinho).
 */
object Barra {
    private const val PREFS = "barra"
    val PADRAO = listOf(
        "com.waze", "com.google.android.apps.maps", "com.spotify.music",
        "com.anthropic.claude", "com.google.android.youtube", "com.netflix.mediaclient",
        "com.nwd.android.phone"
    )

    private var vista: View? = null
    private var recolhida = false
    private val principal = Handler(Looper.getMainLooper())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun ligada(ctx: Context) = prefs(ctx).getBoolean("ligada", true)
    fun mudarLigada(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("ligada", v).apply()
        if (v) mostrar(ctx) else esconder(ctx)
    }

    fun apps(ctx: Context): List<String> {
        val s = prefs(ctx).getString("apps", null) ?: return PADRAO
        return s.split(',').filter { it.isNotBlank() }
    }
    fun salvarApps(ctx: Context, lista: List<String>) {
        prefs(ctx).edit().putString("apps", lista.joinToString(",")).apply()
        if (vista != null) { esconder(ctx); mostrar(ctx) }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun mostrar(ctx: Context) {
        principal.post { mostrarAgora(ctx.applicationContext) }
    }

    fun esconder(ctx: Context) {
        principal.post {
            val v = vista ?: return@post
            try {
                (ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Exception) {
            }
            vista = null
        }
    }

    private fun mostrarAgora(ctx: Context) {
        if (vista != null || !ligada(ctx) || !Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val largura = if (recolhida) dp(ctx, 18) else dp(ctx, 76)

        val coluna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }
        val fundo = GradientDrawable().apply {
            setColor(Color.argb(235, 11, 18, 32))
            cornerRadii = floatArrayOf(0f, 0f, dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(), dp(ctx, 18).toFloat(), 0f, 0f)
        }

        if (recolhida) {
            coluna.addView(TextView(ctx).apply {
                text = "›"
                setTextColor(Color.parseColor("#F2A93B")); textSize = 22f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(ctx, 120)))
            coluna.setOnClickListener { recolhida = false; esconder(ctx); mostrar(ctx) }
        } else {
            // Painel ASX (início)
            coluna.addView(icone(ctx, null, "Painel") {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            // Microfone (fala um comando)
            coluna.addView(icone(ctx, "🎤", "Falar") {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACAO_OUVIR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            for (p in apps(ctx)) {
                if (ctx.packageManager.getLaunchIntentForPackage(p) == null && p != "com.anthropic.claude") continue
                coluna.addView(icone(ctx, p, null) { abrirApp(ctx, p) })
            }
            // Recolher
            coluna.addView(TextView(ctx).apply {
                text = "‹"
                setTextColor(Color.parseColor("#8E9BB0")); textSize = 26f
                gravity = Gravity.CENTER
                setOnClickListener { recolhida = true; esconder(ctx); mostrar(ctx) }
            }, LinearLayout.LayoutParams(-1, dp(ctx, 52)))
        }

        val rolagem = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = fundo
            addView(coluna)
        }
        val lp = WindowManager.LayoutParams(
            largura,
            if (recolhida) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        try {
            wm.addView(rolagem, lp)
            vista = rolagem
        } catch (e: Exception) {
            Registro.anotar("barra: não apareceu (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun abrirApp(ctx: Context, p: String) {
        if (Apps.abrir(ctx, p)) return
        if (p == "com.anthropic.claude") {
            // Sem o app do Claude: abre o claude.ai no navegador.
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    private fun icone(ctx: Context, pacote: String?, rotulo: String?, clique: () -> Unit): View {
        val caixa = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            setOnClickListener { clique() }
        }
        var desenho: android.graphics.drawable.Drawable? = null
        var nome = rotulo
        if (pacote != null && pacote.contains('.')) {
            try {
                desenho = ctx.packageManager.getApplicationIcon(pacote)
                if (nome == null) nome = Apps.nomeDe(ctx, pacote)
            } catch (_: Exception) {
            }
        }
        if (desenho == null && pacote != null && pacote.contains('.')) {
            // app não instalado (ex.: Claude): usa o navegador
            nome = nome ?: "Claude"
        }
        if (desenho != null) {
            caixa.addView(ImageView(ctx).apply { setImageDrawable(desenho) }, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
        } else {
            caixa.addView(TextView(ctx).apply {
                text = if (pacote != null && !pacote.contains('.')) pacote else if (rotulo == "Painel") "🚗" else "✳️"
                textSize = 26f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)))
        }
        caixa.addView(TextView(ctx).apply {
            text = (nome ?: "").take(9)
            setTextColor(Color.parseColor("#C9D3E3")); textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
        })
        return caixa
    }
}
