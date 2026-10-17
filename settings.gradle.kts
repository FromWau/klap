@file:Suppress("UnstableApiUsage")

rootProject.name = "klap"

pluginManagement {
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.frommhund.xyz/releases") {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://maven.frommhund.xyz/releases") {
            mavenContent { includeGroupAndSubgroups("com.fromwau") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

gradle.lifecycle.beforeProject {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

include(":klap")
include(":example:chmod")
include(":example:cp")
include(":example:curl")
include(":example:dd")
include(":example:find")
include(":example:git")
include(":example:head")
include(":example:ls")
include(":example:mv")
include(":example:pacman")
include(":example:parity")
include(":example:pulse")
include(":example:rm")
include(":example:ssh")
include(":example:tar")
include(":example:task-manager")
