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

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack builds arbitrary GitHub repos on demand, so only the one group that needs it may
        // resolve from it (AUD-009); everything else must come from Google or Maven Central.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.mik3y") }   // usb-serial-for-android
        }
    }
}

rootProject.name = "GBCartDumper"
include(":app")
