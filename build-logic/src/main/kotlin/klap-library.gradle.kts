import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// A published klap module: klap's targets and JDK, explicit API, and the klap-publish coordinates.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("klap-publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val buildJdk = libs.findVersion("jdk-build").get().requiredVersion.toInt()
val targetJdk = libs.findVersion("jdk-target").get().requiredVersion.toInt()

extensions.configure<KotlinMultiplatformExtension> {
    explicitApi()

    jvmToolchain(buildJdk)

    jvm {
        compilerOptions {
            // Set explicitly: the toolchain would make it the build JDK's, which -Xjdk-release refuses.
            jvmTarget = JvmTarget.fromTarget(targetJdk.toString())
            // Fails the build on a JDK API newer than the target, which would otherwise reach a consumer as a
            // NoSuchMethodError.
            freeCompilerArgs.add("-Xjdk-release=$targetJdk")
        }
        // The Kotlin plugin leaves this attribute unset for a multiplatform jvm() target.
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, targetJdk)
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

val jvmTestOnTarget = tasks.register<Test>("jvmTestOnTarget") {
    description = "Runs the JVM tests on JDK $targetJdk, the oldest a consumer may run."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(targetJdk) }
}
tasks.named("check") { dependsOn(jvmTestOnTarget) }
