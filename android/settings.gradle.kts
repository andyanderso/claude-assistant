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

rootProject.name = "ClaudeCopilot"

// :app      — the Copilot assistant itself
// :wakeword — on-device "Hey Claude" detection (vendored openWakeWord Android library, Apache-2.0)
include(":app", ":wakeword")
