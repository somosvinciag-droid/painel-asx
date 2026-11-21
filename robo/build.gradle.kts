plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

// App "Painel Redmi": o celular robô (Redmi Note 13 Pro+ antigo). O Claude comanda pelo conector
// (vaga /d/redmi do mesmo servidor do Painel ASX). Número da versão vem do GitHub (workflow robo.yml).
val numero = (System.getenv("NUMERO_VERSAO") ?: "1").toInt()
val servidor = (System.getenv("SERVIDOR_URL") ?: "https://painel-asx.somos-vinciag.workers.dev").trimEnd('/')

android {
    namespace = "br.com.leonardo.painelasx.robo"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.leonardo.painelasx.robo"
        minSdk = 30
        targetSdk = 35
        versionCode = numero
        versionName = "r$numero"
        buildConfigField("String", "SERVIDOR", "\"$servidor/d/redmi\"")
    }

    buildFeatures { buildConfig = true }

    // MESMA chave da central (segredo CHAVE_BASE64 do GitHub; nunca no repositório).
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
            signingConfig = if (System.getenv("CHAVE_ARQUIVO") != null) signingConfigs.getByName("leo")
            else signingConfigs.getByName("debug")
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
