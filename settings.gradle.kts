pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "ASTRA-AudioText"

include(":app")
include(":engine-whisper")
include(":core:common")
include(":core:privacy")
include(":core:text")
include(":core:transcript")
include(":core:audio")
include(":core:security")
include(":core:models")
