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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // jitpack first: dl.google.com is intermittently unreachable in this environment,
        // and the PaddleOCR AAR lives on jitpack. Cached Google artifacts still resolve.
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
        google()
    }
}

rootProject.name = "UBox"
include(":app")
