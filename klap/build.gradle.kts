plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

// One source for the toolchain and for the floor published to consumers: a bump that moved only one of
// them would leave the metadata claiming a compatibility the bytecode does not have.
val jdkVersion = 25

group = "com.fromwau.klap"
version = libs.versions.klapVersion.get()

// Shipped as a resource rather than through Jar.metaInf so the Android AAR carries it too; AGP builds its
// classes.jar itself and never sees that hook. Named for klap rather than the conventional META-INF/LICENSE:
// a consumer merging several dependencies hits a duplicate-path packaging failure on that shared name, and
// AGP 8 removed the global exclude that used to paper over it.
val licenseResource by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE")) { rename { "LICENSE-klap.txt" } }
    into(layout.buildDirectory.dir("generated/license/META-INF"))
}

val repoUrl = "https://github.com/FromWau/klap"

kotlin {
    explicitApi()

    jvmToolchain(jdkVersion)

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "com.fromwau.klap"
    }

    jvm {
        // KMP's jvm target does not derive this from the toolchain the way java-library does, so without it
        // Gradle resolves this variant for any consumer, who then meets the floor as `class file has wrong
        // version` rather than as an unmet requirement.
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

    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain {
            // The resources ROOT is the task's parent dir, so its META-INF/ subdirectory is what lands in
            // the artifact; pointing at the task's own output would put the file at the classpath root.
            resources.srcDir(licenseResource.map { it.destinationDir.parentFile })
            dependencies {
                api(libs.kotlinx.serialization.json)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

val mavenUser = env.fetchOrNull("MAVEN_USERNAME")
val mavenToken = env.fetchOrNull("MAVEN_TOKEN")

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "klap"
            description = "A reflection-free Kotlin Multiplatform command-line framework: a builder DSL " +
                "for commands and subcommands, typed and validated arguments, generated help, " +
                "shell completion, and typed Result errors."
            url = repoUrl
            licenses {
                license {
                    // The "or later" half is the project's own choice, not the bare GPL-3.0 the SPDX list retired.
                    name = "GNU General Public License v3.0 or later"
                    url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    distribution = "repo"
                }
            }
            scm {
                url = repoUrl
                connection = "scm:git:$repoUrl.git"
                developerConnection = "scm:git:ssh://git@github.com/FromWau/klap.git"
            }
        }
    }

    repositories {
        maven {
            name = "vps"
            url = uri("https://maven.frommhund.xyz/releases")
            credentials {
                username = mavenUser.orEmpty()
                password = mavenToken.orEmpty()
            }
            authentication { create<BasicAuthentication>("basic") }
        }
    }
}

val hasMavenUser = !mavenUser.isNullOrBlank()
val hasMavenToken = !mavenToken.isNullOrBlank()

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        require(hasMavenUser) { "MAVEN_USERNAME is not set. Copy .env.example to .env and fill it in." }
        require(hasMavenToken) { "MAVEN_TOKEN is not set. Copy .env.example to .env and fill it in." }
    }
}
