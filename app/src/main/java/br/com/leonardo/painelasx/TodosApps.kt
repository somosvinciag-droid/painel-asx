package br.com.leonardo.painelasx

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Todos os apps da central (o Painel ASX como "sistema": daqui abre tudo, inclusive a tela de fábrica). */
class TodosApps : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            setPadding(if (Barra.ligada(this@TodosApps)) dp(88) else dp(16), dp(12), dp(16), dp(12))
        }
        val topo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topo.addView(TextView(this).apply {
            text = "‹  Apps"; textSize = 24f; setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topo.addView(TextView(this).apply {
            text = "Tela de fábrica"; textSize = 15f; setTextColor(Color.parseColor("#E4002B"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setStroke(dp(1), Color.parseColor("#E4002B")); cornerRadius = dp(99).toFloat() }
            setOnClickListener { Apps.abrir(this@TodosApps, Nwd.LAUNCHER_FABRICA) }
        })
        raiz.addView(topo)

        val colunas = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / 120).toInt().coerceIn(4, 10)
        val grade = GridLayout(this).apply { columnCount = colunas }
        val apps = Apps.listar(this).filter { it.pacote != packageName }.sortedBy { it.nome.lowercase() }
        val largura = (resources.displayMetrics.widthPixels - dp(120)) / colunas
        for (a in apps) {
            val caixa = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { Apps.abrir(this@TodosApps, a.pacote) }
            }
            caixa.addView(ImageView(this).apply {
                try { setImageDrawable(packageManager.getApplicationIcon(a.pacote)) } catch (_: Exception) { }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            caixa.addView(TextView(this).apply {
                text = a.nome; textSize = 13f; setTextColor(Color.parseColor("#DDDDE2"))
                gravity = Gravity.CENTER; maxLines = 2
            })
            grade.addView(caixa, GridLayout.LayoutParams().apply { width = largura })
        }
        raiz.addView(ScrollView(this).apply { addView(grade) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        setContentView(raiz)
    }

    companion object {
        fun abrir(a: Activity) = a.startActivity(Intent(a, TodosApps::class.java))
    }
}
