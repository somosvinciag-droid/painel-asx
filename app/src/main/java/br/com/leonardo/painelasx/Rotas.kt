package br.com.leonardo.painelasx

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Pedidos de rota. Padrão: o MAPA DO PAINEL (Navegacao: mapa e rotas nossas, sem internet).
 * Se o Leonardo escolher "rotas no Waze", ou o mapa da região ainda não estiver baixado, ou o destino
 * não estiver no mapa, vai como antes:
 * "rota para Pouso Alegre"                    -> Waze já navegando.
 * "rota para Caxambu, Pouso Alegre, Caxambu"  -> Google Maps com as paradas em ordem
 *                                                (o Waze não aceita paradas vindas de outro app).
 */
object Rotas {
    private const val WAZE = "com.waze"
    private const val MAPS = "com.google.android.apps.maps"

    private val PEDIDO = Regex(
        "^(?:(?:me\\s+)?(?:faz|faça|faca|traça|traca|joga|coloca|bota|cria|abre|monta)\\s+)?(?:uma\\s+|a\\s+)?" +
            "(?:rota|navegar|navega|navegação|navegacao|navegue|me\\s+leva|leva)\\s+" +
            "(?:pra|para|até|ate|ao|à|a|em)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val SEPARA = Regex("\\s*(?:,|;|\\s+e\\s+depois\\s+|\\s+depois\\s+|\\s+e\\s+|\\s+passando\\s+por\\s+|->|→)\\s*", RegexOption.IGNORE_CASE)

    /** Os destinos pedidos, em ordem, ou null se a frase não for um pedido de rota. */
    fun destinos(falado: String): List<String>? {
        val m = PEDIDO.find(falado.trim()) ?: return null
        val partes = m.groupValues[1].split(SEPARA)
            .map { it.trim().trimEnd('.', '!', '?') }
            .filter { it.isNotBlank() }
        // "Rua Major Penha 359, Caxambu" é UM endereço: junta rua/número com a cidade que vem depois.
        val juntas = mutableListOf<String>()
        var i = 0
        while (i < partes.size) {
            val p = partes[i]
            if (i + 1 < partes.size && pareceRua(p) && !pareceRua(partes[i + 1])) {
                juntas.add(p + ", " + partes[i + 1]); i += 2
            } else {
                juntas.add(p); i += 1
            }
        }
        return juntas.ifEmpty { null }
    }

    private val RUA = Regex("^(rua|r\\.|av\\.?|avenida|alameda|travessa|praça|praca|estrada|rodovia|rod\\.|largo)\\b", RegexOption.IGNORE_CASE)
    private fun pareceRua(t: String) = RUA.containsMatchIn(t.trim()) || t.any { it.isDigit() }

    private fun instalado(ctx: Context, pacote: String) =
        ctx.packageManager.getLaunchIntentForPackage(pacote) != null

    private fun abrir(ctx: Context, uri: String, pacote: String?): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pacote != null) i.setPackage(pacote)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        Registro.anotar("rota: não abriu ($uri): ${e.message}")
        false
    }

    /** [destinos]: endereços; [nomes]: o que ele falou ("mineirita", "casa"), na mesma ordem. */
    fun navegar(ctx: Context, destinos: List<String>, nomes: List<String> = destinos): String {
        if (Config.rotasNoPainel(ctx)) {
            Navegacao.iniciar(ctx, nomes, destinos)?.let { return it }
            Registro.anotar("rota: ${MapaPacote.explicacao()}; vai pelo Waze")
            return MapaPacote.explicacao() + ". " + navegarFora(ctx, destinos, nomes)
        }
        return navegarFora(ctx, destinos, nomes)
    }

    /** Pelo Waze / Google Maps (o jeito de antes). O Carro avisa a chegada. */
    fun navegarFora(ctx: Context, destinos: List<String>, nomes: List<String> = destinos): String {
        val ultimo = nomes.lastOrNull() ?: destinos.last()
        Carro.definirDestino(Lugares.achar(ctx, ultimo)?.first ?: ultimo, destinos.last())
        val enc = destinos.map { Uri.encode(it) }
        Registro.anotar("rota: ${destinos.joinToString(" → ")}")
        if (destinos.size == 1) {
            if (instalado(ctx, WAZE) && abrir(ctx, "https://waze.com/ul?q=${enc[0]}&navigate=yes", WAZE)) {
                return "Rota para ${destinos[0]} no Waze."
            }
            if (abrir(ctx, "google.navigation:q=${enc[0]}", if (instalado(ctx, MAPS)) MAPS else null)) {
                return "Rota para ${destinos[0]} no Maps."
            }
            return "Não consegui abrir o GPS para ${destinos[0]}."
        }
        // Várias paradas: Google Maps (o último é o destino, os outros são paradas no caminho).
        val destino = enc.last()
        val paradas = enc.dropLast(1).joinToString("%7C")
        val url = "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=$destino&waypoints=$paradas"
        if (abrir(ctx, url, if (instalado(ctx, MAPS)) MAPS else null)) {
            return "Rota no Maps: ${destinos.joinToString(" → ")}. Toque em Iniciar para começar."
        }
        // Sem o Maps: vai para o primeiro destino no Waze.
        if (instalado(ctx, WAZE) && abrir(ctx, "https://waze.com/ul?q=${enc[0]}&navigate=yes", WAZE)) {
            return "O Maps não abriu; mandei o Waze para a primeira parada: ${destinos[0]}."
        }
        return "Não consegui abrir o GPS."
    }
}
