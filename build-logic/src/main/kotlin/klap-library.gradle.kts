import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// A published klap module: klap's targets and JDK, explicit API, and the klap-publish coordinates.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("klap-publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val jdkVersion = libs.findVersion("jdk").get().requiredVersion.toInt()

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()

    jvmToolchain(jdkVersion)

    jvm {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jdkVersion)
    }
    linuxX64()
    mingwX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets.commonTest.dependencies {
        implementation(libs.findLibrary("kotlin-test").get())
    }
}
