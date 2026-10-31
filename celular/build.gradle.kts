plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App "Painel ASX Celular" (fica no celular do Leonardo e conversa com a central do carro).
// O numero da versao vem do GitHub (workflow celular.yml).
val numero = (System.getenv("NUMERO_VERSAO") ?: "1").toInt()

android {
    namespace = "br.com.leonardo.painelasx.celular"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.leonardo.painelasx.celular"
        minSdk = 30
        targetSdk = 35
        versionCode = numero
        versionName = "c$numero"
    }

    // MESMA chave e MESMA forma de assinar da central (app/build.gradle.kts).
    // A chave fica SO nos segredos do GitHub (CHAVE_BASE64), nunca no repositorio.
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
