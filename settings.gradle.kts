@file:Suppress("UnstableApiUsage")

rootProject.name = "klap"

pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":klap")
include(":example:task-manager")
include(":example:parity")
include(":example:mkdir")
include(":example:cp")
include(":example:dd")
include(":example:curl")
include(":example:ssh")
include(":example:git")
include(":example:find")
include(":example:ls")
include(":example:head")
include(":example:mv")
include(":example:pacman")
include(":example:chmod")
include(":example:rm")
include(":example:tar")
include(":example:rsync")
