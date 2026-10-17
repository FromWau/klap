plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("klap-publish")
}

val jdkVersion = libs.versions.jdk.get().toInt()

kotlin {
    explicitApi()

    jvmToolchain(jdkVersion)

    jvm {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jdkVersion)
    }
    linuxX64()
    mingwX64()

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
            implementation(libs.kotlin.test)
            implementation(libs.kern.result.test)
            // Test-only. commonMain must stay free of kotlinx-coroutines; `suspend` itself is stdlib.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
