package br.com.leonardo.painelasx.robo

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Cada comando do servidor (tipo + argumentos JSON) vira uma ação aqui. Devolve um texto para o Claude. */
object Comandos {

    fun executar(ctx: Context, tipo: String, a: JSONObject): String = when (tipo) {
        "tela" -> { acordarSePreciso(ctx); Robo.precisa().lerTela(a.optBoolean("todos")) }
        "print" -> { acordarSePreciso(ctx); Robo.precisa().print(a.optInt("largura", 540), a.optInt("qualidade", 60)).toString() }
        "tocar" -> { acordarSePreciso(ctx); tocar(a) }
        "deslizar" -> { acordarSePreciso(ctx); deslizar(a) }
        "digitar" -> digitar(a)
        "tecla" -> tecla(ctx, a.optString("tecla"))
        "abrir" -> { acordarSePreciso(ctx); abrir(ctx, a) }
        "apps" -> apps(ctx)
        "arquivo" -> Galeria.receber(ctx, a)
        "esperar" -> { Thread.sleep(a.optLong("ms", 1000).coerceIn(0, 15_000)); "esperei" }
        "lote" -> lote(ctx, a.optJSONArray("passos") ?: JSONArray())
        "estado" -> Estado.agora(ctx).toString()
        "registro" -> Registro.ultimas(a.optInt("linhas", 60)).joinToString("\n")
        else -> throw Exception("comando desconhecido: $tipo")
    }

    private fun lote(ctx: Context, passos: JSONArray): String {
        val r = StringBuilder()
        for (i in 0 until passos.length()) {
            val p = passos.optJSONObject(i) ?: continue
            val t = p.optString("tipo")
            if (t == "lote" || t == "arquivo") throw Exception("passo $i: '$t' não vale dentro de passos")
            try {
                r.append(i).append(". ").append(t).append(": ").append(executar(ctx, t, p)).append('\n')
            } catch (e: Exception) {
                r.append(i).append(". ").append(t).append(": ERRO ").append(e.message).append('\n')
                r.append("(parei no passo $i)")
                throw Exception(r.toString())
            }
            if (t != "esperar") Thread.sleep(p.optLong("depois_ms", 400).coerceIn(0, 10_000))
        }
        return r.toString()
    }

    private fun tocar(a: JSONObject): String {
        val r = Robo.precisa()
        val longo = a.optBoolean("longo")
        if (a.has("x") && a.has("y")) {
            val ok = r.tocarXY(a.getDouble("x").toFloat(), a.getDouble("y").toFloat(), longo)
            if (!ok) throw Exception("o toque não foi aceito pelo Android")
            return "toquei em [${a.getInt("x")},${a.getInt("y")}]"
        }
        val texto = a.optString("texto").ifBlank { null }
        val desc = a.optString("desc").ifBlank { null }
        val id = a.optString("id").ifBlank { null }
        if (texto == null && desc == null && id == null) throw Exception("diga texto, desc, id ou x/y")
        // Espera o elemento aparecer (até "espera_ms", padrão 3 s).
        val limite = System.currentTimeMillis() + a.optLong("espera_ms", 3000).coerceIn(0, 15_000)
        var achados: List<Robo.Elemento>
        while (true) {
            achados = r.procurar(texto, desc, id, a.optBoolean("exato"))
            if (achados.isNotEmpty() || System.currentTimeMillis() > limite) break
            Thread.sleep(300)
        }
        if (achados.isEmpty()) throw Exception("não achei \"${texto ?: desc ?: id}\" na tela (use tela_redmi para ver o que há)")
        val i = a.optInt("indice", 0)
        if (i >= achados.size) throw Exception("só achei ${achados.size} com esse texto")
        val r2 = r.tocarElemento(achados[i], longo, a.optBoolean("gesto"))
        return if (achados.size > 1) "$r2 (havia ${achados.size} parecidos; usei o nº $i)" else r2
    }

    private fun deslizar(a: JSONObject): String {
        val r = Robo.precisa()
        val (w, h) = r.tamanhoTela()
        val ms = a.optLong("ms", 400)
        val dir = Robo.semAcento(a.optString("direcao"))
        val ok = if (dir.isNotEmpty()) {
            val cx = w / 2f
            val cy = h / 2f
            when (dir) {
                "cima" -> r.deslizar(cx, h * 0.72f, cx, h * 0.30f, ms)
                "baixo" -> r.deslizar(cx, h * 0.30f, cx, h * 0.72f, ms)
                "esquerda" -> r.deslizar(w * 0.85f, cy, w * 0.15f, cy, ms)
                "direita" -> r.deslizar(w * 0.15f, cy, w * 0.85f, cy, ms)
                else -> throw Exception("direcao: cima, baixo, esquerda ou direita")
            }
        } else {
            for (k in listOf("x1", "y1", "x2", "y2")) if (!a.has(k)) throw Exception("falta $k (ou use direcao)")
            r.deslizar(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(), a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(), ms)
        }
        if (!ok) throw Exception("o gesto não foi aceito pelo Android")
        return "deslizei"
    }

    private fun digitar(a: JSONObject): String {
        val r = Robo.precisa()
        val texto = a.optString("texto")
        val campoBusca = a.optString("campo").ifBlank { null }
        var campo: AccessibilityNodeInfo? = null
        if (campoBusca != null) {
            val e = r.procurar(campoBusca, null, null, false).firstOrNull { it.no.isEditable }
                ?: r.procurar(null, null, campoBusca, false).firstOrNull { it.no.isEditable }
                ?: throw Exception("não achei o campo \"$campoBusca\"")
            e.no.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            e.no.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
            campo = e.no
        }
        if (campo == null) campo = r.campoComFoco()
        if (campo == null) throw Exception("nenhum campo de texto com foco (toque no campo antes)")
        if (campo.isPassword) throw Exception("este campo é de SENHA: não escrevo senha (regra do Leonardo)")
        campo.refresh()
        val antes = if (campo.isShowingHintText) "" else (campo.text?.toString() ?: "")
        val novo = if (a.optBoolean("limpar")) texto else antes + texto
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, novo)
        if (!campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) throw Exception("o campo não aceitou o texto")
        if (a.optBoolean("enter")) {
            Thread.sleep(200)
            campo.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return "escrevi ${texto.length} letras"
    }

    private fun tecla(ctx: Context, t: String): String {
        val k = Robo.semAcento(t)
        if (k == "acordar") { acordar(ctx); return "tela ligada" }
        val r = Robo.precisa()
        val acao = when (k) {
            "voltar", "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "inicio", "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recentes" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notificacoes" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "ajustes_rapidos" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "print_sistema" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "fechar_avisos" -> AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            "enter" -> {
                val c = r.campoComFoco() ?: throw Exception("nenhum campo com foco para o enter")
                if (!c.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) throw Exception("o campo não aceitou o enter")
                return "enter"
            }
            else -> throw Exception("tecla desconhecida: $t (voltar, inicio, recentes, notificacoes, enter, acordar)")
        }
        acordarSePreciso(ctx)
        if (!r.global(acao)) throw Exception("o Android não aceitou a tecla $t")
        return "apertei $t"
    }

    private fun abrir(ctx: Context, a: JSONObject): String {
        val base: Context = Robo.ativo ?: ctx
        val pm = ctx.packageManager
        val url = a.optString("url").ifBlank { null }
        var pacote = a.optString("pacote").ifBlank { null }
        val nome = a.optString("nome").ifBlank { null }
        if (url != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (pacote != null) i.setPackage(pacote)
            else if (url.contains("instagram.com") && instalado(pm, "com.instagram.android")) i.setPackage("com.instagram.android")
            App.abrirTela(base, i)
            return "abri $url"
        }
        if (pacote == null && nome != null) {
            val alvo = Robo.semAcento(nome)
            val lista = lancaveis(pm)
            pacote = (lista.firstOrNull { Robo.semAcento(it.first) == alvo } ?: lista.firstOrNull { Robo.semAcento(it.first).contains(alvo) })?.second
                ?: throw Exception("não achei app com o nome \"$nome\"")
        }
        if (pacote == null) throw Exception("diga pacote, nome ou url")
        val i = pm.getLaunchIntentForPackage(pacote) ?: throw Exception("o app $pacote não está instalado (ou não abre)")
        i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        App.abrirTela(base, i)
        return "abri $pacote"
    }

    private fun instalado(pm: PackageManager, p: String) = try { pm.getPackageInfo(p, 0); true } catch (_: Exception) { false }

    private fun lancaveis(pm: PackageManager): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun apps(ctx: Context): String =
        lancaveis(ctx.packageManager).joinToString("\n") { "${it.first} = ${it.second}" }

    // ------------------------------------------------------------------ tela ligada

    fun acordarSePreciso(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val kg = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (pm.isInteractive && !kg.isKeyguardLocked) return
        acordar(ctx)
    }

    @Suppress("DEPRECATION")
    fun acordar(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        try {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "painelredmi:acordar"
            )
            wl.acquire(5000)
        } catch (_: Exception) { }
        try {
            App.abrirTela(Robo.ativo ?: ctx, Intent(ctx, AcordarActivity::class.java))
        } catch (e: Exception) {
            Registro.anotar("ERRO acordar: ${e.message}")
        }
        // Espera a tela ligar e o bloqueio (sem senha) sair.
        val kg = ctx.getSystemService(android.app.KeyguardManager::class.java)
        val limite = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < limite && (!pm.isInteractive || kg.isKeyguardLocked)) Thread.sleep(150)
        if (kg.isKeyguardLocked && !kg.isDeviceSecure) {
            // Plano B: arrasta para cima na tela de bloqueio.
            Robo.ativo?.let { r ->
                val (w, h) = r.tamanhoTela()
                r.deslizar(w / 2f, h * 0.85f, w / 2f, h * 0.25f, 300)
                Thread.sleep(600)
            }
        }
        if (kg.isKeyguardLocked && kg.isDeviceSecure) {
            throw Exception("o Redmi está bloqueado com senha/PIN: tire a senha da tela de bloqueio (não digito senha)")
        }
    }
}
