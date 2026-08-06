plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.fromwau"
version = libs.versions.klapVersion.get()

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
        commonMain.dependencies {
            implementation(project(":klap"))
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
