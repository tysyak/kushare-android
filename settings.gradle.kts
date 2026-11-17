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
        // TEMPORAL (aviso FreeDroidWarn, ver CHANGELOG/notas): JitPack solo
        // para esa biblioteca. Quitar junto con la dependencia.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.woheller69") }
        }
    }
}

rootProject.name = "kushare-android"
include(":app")
