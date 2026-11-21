// "Painel Redmi": projeto SEPARADO (não entra no settings.gradle.kts da raiz),
// para que mexer nele nunca gere versão nova da central do carro.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "painel-redmi"
