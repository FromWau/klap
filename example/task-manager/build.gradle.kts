plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

// Generated so `--version` and the PKGBUILD's pkgver resolve from one catalog entry, not two literals.
val versionSource = tasks.register("versionSource") {
    description = "Generates the VERSION constant from klapVersion in the version catalog."
    group = LifecycleBasePlugin.BUILD_GROUP

    val klapVersion = libs.versions.klapVersion.get()
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    inputs.property("klapVersion", klapVersion)
    outputs.dir(outputDir)

    doLast {
        val target = outputDir.get().asFile.resolve("com/fromwau/example/Version.kt")
        target.parentFile.mkdirs()
        target.writeText(
            """
            package com.fromwau.example

            /** Generated from `klapVersion` in `gradle/libs.versions.toml`. Do not edit. */
            internal const val VERSION: String = "$klapVersion"

            """.trimIndent(),
        )
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "com.fromwau.example"
    }

    linuxX64 {
        binaries.executable {
            entryPoint = "com.fromwau.example.main"
            baseName = "klapExample"
        }
    }

    mingwX64 {
        binaries.executable {
            entryPoint = "com.fromwau.example.main"
            baseName = "klapExample"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(versionSource)
            dependencies {
                implementation(project(":klap"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.core)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
