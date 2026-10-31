// Copiado de k2-fsa/sherpa-onnx v1.13.8 (Apache-2.0), sherpa-onnx/kotlin-api/HomophoneReplacerConfig.
// Mudança: sem System.loadLibrary (o Painel ASX carrega as bibliotecas baixadas).
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
