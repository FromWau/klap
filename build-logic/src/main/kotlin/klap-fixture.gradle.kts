plugins {
    kotlin("jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
}

dependencies {
    implementation(project(":klap"))
    testImplementation(kotlin("test"))
    testImplementation(project(":example:parity"))
}
