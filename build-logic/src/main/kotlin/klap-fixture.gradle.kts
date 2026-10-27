plugins {
    kotlin("jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
}

// Shows the notes ParitySuite.rejects prints where klap refuses a line for another reason than the real tool.
tasks.test {
    testLogging { events("standard_out") }
}

dependencies {
    implementation(project(":klap"))
    implementation(project(":klap-io"))
    testImplementation(kotlin("test"))
    testImplementation(project(":example:parity"))
}
