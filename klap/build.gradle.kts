plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

group = "com.fromwau.klap"
version = "0.1.0"

kotlin {
    jvmToolchain(21)

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "com.fromwau.klap"
    }

    jvm()
    linuxX64()
    mingwX64()
    // Apple targets: ARM only. The x86_64 Apple targets (macosX64, iosX64) are deprecated in Kotlin
    // now that Apple has ended Intel-Mac support. macosArm64 is kept for native macOS CLIs.
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    // A custom jvmAndroidMain intermediate (manual dependsOn) disables the auto-applied default
    // hierarchy template and would orphan the native/apple leaves. Reapply it explicitly first.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            // api: consumers return @Serializable types from action{}, so the JSON runtime is part of klap's public surface, not an internal detail.
            api(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
