package br.com.leonardo.painelasx.robo

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Tela invisível: liga a tela e pede ao Android para tirar o bloqueio (sem senha). Fecha sozinha. */
class AcordarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val kg = getSystemService(KeyguardManager::class.java)
        if (kg.isKeyguardLocked) {
            kg.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissCancelled() { finish() }
                override fun onDismissError() { finish() }
            })
            Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 3000)
        } else {
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 300)
        }
    }
}

/**
 * Tela do app: mostra se está tudo ligado e tem um botão para cada ajuste de UMA vez do HyperOS.
 * Depois de configurado, ninguém precisa abrir o app de novo.
 */
class MainActivity : Activity() {
    private lateinit var estado: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.garantir(this, "tela")
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 60)
            setBackgroundColor(Color.rgb(14, 26, 43))
        }
        raiz.addView(TextView(this).apply {
            text = "Painel Redmi"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        estado = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(220, 220, 220))
            setPadding(0, 30, 0, 30)
        }
        raiz.addView(estado)

        fun botao(t: String, acao: () -> Unit) {
            raiz.addView(Button(this).apply {
                text = t
                isAllCaps = false
                textSize = 16f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    try { acao() } catch (e: Exception) { Toast.makeText(this@MainActivity, "Não abriu: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            })
        }
        botao("1. Ligar a Acessibilidade (Painel Redmi)") {
            App.abrirTela(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        botao("   Se aparecer \"configuração restrita\": página do app → ⋮ → Permitir") {
            App.abrirTela(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        botao("2. Bateria sem restrição") {
            App.abrirTela(this, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        botao("3. Início automático (Xiaomi)") {
            val i = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            try { App.abrirTela(this, i) } catch (_: Exception) {
                App.abrirTela(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        botao("4. Outras permissões (janelas em segundo plano, tela de bloqueio)") {
            val i = Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", packageName)
            try { App.abrirTela(this, i) } catch (_: Exception) {
                App.abrirTela(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        botao("Copiar o link do conector") {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("conector", Laco.link(this)))
            Toast.makeText(this, "Link copiado", Toast.LENGTH_SHORT).show()
        }
        setContentView(ScrollView(this).apply { addView(raiz) })
    }

    override fun onResume() {
        super.onResume()
        atualizar()
    }

    override fun onPause() {
        h.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun atualizar() {
        val pm = getSystemService(PowerManager::class.java)
        fun s(b: Boolean) = if (b) "✅" else "❌"
        estado.text = buildString {
            append(s(Laco.online())).append(" Conectado ao Claude").append(if (Laco.ultimoErro.isNotEmpty()) " (${Laco.ultimoErro})" else "").append('\n')
            append(s(Robo.ativo != null)).append(" Acessibilidade\n")
            append(s(pm.isIgnoringBatteryOptimizations(packageName))).append(" Bateria sem restrição\n")
            append(s(Servico.rodando)).append(" Serviço fixo\n")
            append(s(App.podeEscreverAjustes(this@MainActivity))).append(" Religa a Acessibilidade sozinho\n")
            append("\nVersão ").append(BuildConfig.VERSION_NAME)
        }
        estado.visibility = View.VISIBLE
        h.postDelayed({ atualizar() }, 2000)
    }
}
