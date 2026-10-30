plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// O numero da versao vem do GitHub (cada montagem sobe 1).
// O app compara este numero com o da versao mais nova publicada para saber se atualiza.
val numero = (System.getenv("NUMERO_VERSAO") ?: "1").toInt()

android {
    namespace = "br.com.leonardo.painelasx"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.com.leonardo.painelasx"
        minSdk = 26
        targetSdk = 34
        versionCode = numero
        versionName = "v$numero"
        buildConfigField("String", "REPO", "\"somosvinciag-droid/painel-asx\"")
        // Endereco do conector na Cloudflare (o GitHub preenche ao montar).
        buildConfigField("String", "SERVIDOR", "\"" + (System.getenv("SERVIDOR_URL") ?: "").trimEnd('/') + "\"")
    }

    buildFeatures { buildConfig = true }

    // A chave de assinatura tem de ser SEMPRE a mesma, senao o Android recusa a atualizacao.
    // A chave fica SO nos segredos do GitHub (CHAVE_BASE64), nunca no repositorio.
    // O workflow a escreve num arquivo temporario e passa o caminho em CHAVE_ARQUIVO.
    signingConfigs {
        create("leo") {
            storeFile = file(System.getenv("CHAVE_ARQUIVO") ?: "chave-inexistente.p12")
            storeType = "pkcs12"
            storePassword = "painel-asx"
            keyAlias = "painel"
            keyPassword = "painel-asx"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("leo")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
