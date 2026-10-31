package br.com.leonardo.painelasx

import java.io.File
import java.io.FileOutputStream

/**
 * Gravar arquivos sem perder o conteúdo quando a central perde a energia de repente:
 * escreve numa cópia, força salvar no disco e só então troca pelo arquivo de verdade.
 * (Com writeText direto, um corte de energia no meio deixava o arquivo vazio ou cortado.)
 */
object Arquivo {
    /** [sufixo]: nome da cópia (duas linhas gravando o mesmo arquivo ao mesmo tempo usam sufixos diferentes). */
    fun gravarSeguro(destino: File, texto: String, sufixo: String = ".tmp") {
        val tmp = File(destino.parentFile, destino.name + sufixo)
        FileOutputStream(tmp).use { o ->
            o.write(texto.toByteArray(Charsets.UTF_8))
            o.flush()
            o.fd.sync()
        }
        if (!tmp.renameTo(destino)) {
            // Muito raro: se a troca falhar, grava direto (melhor do que não gravar).
            destino.writeText(texto)
            tmp.delete()
        }
    }
}
