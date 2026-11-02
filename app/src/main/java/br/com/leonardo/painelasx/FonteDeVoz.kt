package br.com.leonardo.painelasx

/**
 * De onde vem o texto do botão FALAR. O FALAR é SEMPRE do Jarvis (Comandos/Jarvis), nunca do Assistente do Google
 * (decisão do Leonardo, 31/10). Uma "fonte de voz" só OUVE e devolve o texto; quem executa é a tela (Comandos).
 *
 * Hoje:
 *  - [Celular] (app "Painel ASX Celular", ligado pela rede Bluetooth ou pelo cabo no modo "app do celular"):
 *    o celular ouve com o microfone dele e manda o texto.
 *  - Sem fonte de fora (ou com o Android Auto ligado pelo cabo): o Ouvido próprio da central (Whisper).
 *
 * Canal paralelo no MESMO cabo do Android Auto (ADB, [CanalDoCabo]): NÃO é uma fonte daqui. Ele só troca de onde vêm
 * as amostras do Ouvido próprio (microfone do celular em vez do da central); o Whisper continua na central.
 * O "Ok Google" do próprio celular (microfone do celular pelo truque "central sem microfone / moto") é outra coisa:
 * fica opcional, no celular, e NÃO passa por aqui.
 */
interface FonteDeVoz {
    /** Nome curto para o registro ("celular", "adb"...). */
    val nome: String
    /** Pode ouvir agora? */
    fun disponivel(): Boolean
    /** Está ouvindo agora (não começar outro). */
    val ouvindo: Boolean
    /**
     * Ouve. [etapa] = texto curto para a tela ("OUVINDO (CELULAR)"). [fim](texto, null) = entendeu (texto já corrigido);
     * [fim](null, motivo) = não deu: quem chamou usa o Ouvido da central. Devolve false se nem começou.
     * [etapa] e [fim] rodam na linha principal.
     */
    fun ouvir(etapa: (String) -> Unit, fim: (String?, String?) -> Unit): Boolean
}

object FontesDeVoz {
    /** O app do celular (o que já existia antes do receptor). */
    private val celular = object : FonteDeVoz {
        override val nome = "celular"
        override fun disponivel() = Celular.ligado
        override val ouvindo get() = Celular.ouvindo
        override fun ouvir(etapa: (String) -> Unit, fim: (String?, String?) -> Unit) = Celular.ouvir(etapa, fim)
    }

    /** Em ordem de preferência. Aqui entra a futura fonte pelo cabo (ADB). */
    val todas: List<FonteDeVoz> = listOf(celular)

    val algumaOuvindo get() = todas.any { it.ouvindo }

    /**
     * A fonte para este FALAR, ou null = Ouvido próprio da central.
     * Com o Android Auto ligado pelo cabo, por enquanto é sempre o Ouvido da central (decisão do Leonardo).
     */
    fun escolher(): FonteDeVoz? {
        if (AndroidAuto.ligado) return null
        return todas.firstOrNull { it.disponivel() }
    }
}
