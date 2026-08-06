plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

val jdkVersion = libs.versions.jdk.get().toInt()

group = "com.fromwau"
version = libs.versions.klapVersion.get()

val licenseResource = tasks.register<Copy>("licenseResource") {
    description = "Copies the project LICENSE into the common resources as META-INF/LICENSE-klap.txt."
    group = LifecycleBasePlugin.BUILD_GROUP
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
