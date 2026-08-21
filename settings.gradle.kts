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
        // Vosk offline speech recognition
        maven { url = uri("https://alphacephei.com/maven/") }
        // JitPack is used by several llama.cpp Android wrapper projects
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "JarvisAI"
include(":app")
