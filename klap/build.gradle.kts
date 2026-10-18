plugins {
    id("klap-library")
    alias(libs.plugins.kotlinSerialization)
}

description = "A reflection-free Kotlin Multiplatform command-line framework: a builder DSL for commands and " +
    "subcommands, typed and validated arguments, generated help, shell completion, and typed Result errors."

kotlin {
    listOf(
        macosArm64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "klap"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.kern.result)
                api(libs.kern.terminal)
                api(libs.kern.fuzzy)
                api(libs.kotlinx.serialization.json)
            }
        }

        commonTest.dependencies {
            implementation(libs.kern.result.test)
            // Test-only. commonMain must stay free of kotlinx-coroutines; `suspend` itself is stdlib.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
