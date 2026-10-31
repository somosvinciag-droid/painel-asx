/*
 * Painel ASX: receptor de Android Auto (parte AGPLv3).
 * Copyright (C) 2026 os autores do Painel ASX.
 * Canais de som e foco de áudio inspirados no Headunit Revived / Open Headunit
 * (https://github.com/andreknieriem/headunit-revived: AapAudio.kt, AudioConfigs.kt),
 * Copyright (C) 2011-2015 Michael A. Reid e os contribuidores do Headunit Revived.
 *
 * Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da
 * GNU Affero General Public License, versão 3 ou (a seu critério) qualquer versão posterior,
 * publicada pela Free Software Foundation. Distribuído SEM NENHUMA GARANTIA.
 * Ver LICENSE-AGPL-3.0.txt e NOTICE.md na raiz do repositório.
 */
package br.com.leonardo.painelasx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Som do Android Auto no alto-falante do carro. Uma linha por canal (música, voz do GPS, sons do sistema),
 * com pedaços reaproveitados (nada de criar memória a cada pacote).
 *
 * Tudo toca pelo canal da MÚSICA (segue o botão de volume da central; a voz do Jarvis também usa ele).
 * Foco de áudio (para conviver com o Jarvis e o ouvido):
 *  - Música do celular: pede o foco normal. O Jarvis falando (foco "pode abaixar") -> o Android abaixa sozinho.
 *    O ouvido do Jarvis (FALAR, foco exclusivo) -> fica mudo enquanto ele ouve. Outro app tocando na central
 *    (perda definitiva) -> mudo e manda "pausa" ao celular.
 *  - Voz do GPS: pede foco "pode abaixar" enquanto fala (a música abaixa por baixo dela).
 */
class AaSom(ctx: Context, private val pausarCelular: () -> Unit) {
    private val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tocadores = HashMap<Int, Tocador>()

    @Synchronized fun inicio(canal: Int, hz: Int, canais: Int) {
        val t = tocadores.getOrPut(canal) { Tocador(canal, hz, canais) }
        t.comecar()
    }

    /** PCM 16 bits do celular (copiado aqui; o buffer de quem chama é reaproveitado). */
    fun dados(canal: Int, b: ByteArray, ini: Int, n: Int) {
        val t = synchronized(this) { tocadores[canal] } ?: return
        t.colocar(b, ini, n)
    }

    @Synchronized fun fim(canal: Int) { tocadores[canal]?.parar() }

    @Synchronized fun fecharTudo() {
        tocadores.values.forEach { it.soltar() }
        tocadores.clear()
    }

    fun estado(): String = synchronized(this) {
        tocadores.values.joinToString(", ") { "${Aa.nomeCanal(it.canal)}:${if (it.ativo) "tocando" else "parado"}${if (it.mudo) " (mudo)" else ""}" }
            .ifEmpty { "nenhum" }
    }

    private inner class Tocador(val canal: Int, hz: Int, val canais: Int) {
        private val pedaco = 8192
        private val livres = ArrayBlockingQueue<ByteArray>(48)
        private val cheios = ArrayBlockingQueue<Pair<ByteArray, Int>>(48)
        private val faixa: AudioTrack
        @Volatile var ativo = false
        @Volatile var mudo = false
        @Volatile private var vivo = true
        private var foco: AudioFocusRequest? = null
        private val linha: Thread

        init {
            val formatoCanais = if (canais == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(hz, formatoCanais, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            // ~150 ms de folga: o celular manda em rajadas pelo cabo.
            val folga = maxOf(min * 2, hz * canais * 2 * 150 / 1000)
            faixa = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (canal == Aa.CANAL_MIDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(hz).setChannelMask(formatoCanais)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(folga)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            repeat(16) { livres.offer(ByteArray(pedaco)) }
            linha = Thread({ tocar() }, "aa-som-${Aa.nomeCanal(canal)}").apply { isDaemon = true; start() }
        }

        private val ouvinteFoco = AudioManager.OnAudioFocusChangeListener { f ->
            when (f) {
                AudioManager.AUDIOFOCUS_GAIN -> { mudo = false; volume() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { mudo = true; volume() }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    mudo = true; volume()
                    if (canal == Aa.CANAL_MIDIA) {
                        Registro.anotar("aa: outro app pegou o som da central; pausei a música do celular")
                        try { pausarCelular() } catch (_: Exception) { }
                    }
                }
                // "pode abaixar": o Android abaixa sozinho (não pedimos para pausar).
            }
        }

        private fun volume() { try { faixa.setVolume(if (mudo) 0f else 1f) } catch (_: Exception) { } }

        fun comecar() {
            if (ativo) return
            ativo = true
            mudo = false
            if (canal != Aa.CANAL_SISTEMA) pedirFoco()
            volume()
            try { faixa.play() } catch (e: Exception) { Registro.anotar("ERRO aa: som ${Aa.nomeCanal(canal)} não tocou (${e.javaClass.simpleName})") }
        }

        private fun pedirFoco() {
            try {
                val tipo = if (canal == Aa.CANAL_MIDIA) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                val f = AudioFocusRequest.Builder(tipo)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (canal == Aa.CANAL_MIDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(ouvinteFoco, Rede.principal)
                    .build()
                val r = am.requestAudioFocus(f)
                foco = f
                // Sem foco agora (o ouvido do Jarvis está ouvindo, ou uma ligação): começa mudo.
                if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) mudo = true
            } catch (_: Exception) { }
        }

        private fun soltarFoco() {
            val f = foco ?: return
            foco = null
            try { am.abandonAudioFocusRequest(f) } catch (_: Exception) { }
        }

        fun colocar(b: ByteArray, ini: Int, n: Int) {
            if (!vivo || n <= 0) return
            if (!ativo) comecar()   // dados sem "início" (reconexão): começa assim mesmo
            var p = 0
            while (p < n) {
                val k = minOf(pedaco, n - p)
                // Sem pedaço livre = a central está atrasada: joga fora o mais velho (menos atraso no som).
                val dst = livres.poll() ?: cheios.poll()?.first ?: ByteArray(pedaco)
                System.arraycopy(b, ini + p, dst, 0, k)
                if (!cheios.offer(dst to k)) livres.offer(dst)
                p += k
            }
        }

        private fun tocar() {
            while (vivo) {
                try {
                    val (b, n) = cheios.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    if (ativo) faixa.write(b, 0, n)
                    livres.offer(b)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    Registro.anotar("ERRO aa: som ${Aa.nomeCanal(canal)}: ${e.javaClass.simpleName}")
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
            }
        }

        fun parar() {
            if (!ativo) return
            ativo = false
            cheios.clear()
            try { faixa.pause(); faixa.flush() } catch (_: Exception) { }
            soltarFoco()
        }

        fun soltar() {
            parar()
            vivo = false
            linha.interrupt()
            try { faixa.release() } catch (_: Exception) { }
        }
    }
}
