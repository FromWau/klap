plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "com.fromwau.klap"
version = libs.versions.klapVersion.get()

application {
    mainClass.set("com.fromwau.example.MainKt")
    applicationName = "example"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The @Serializable JSON runtime arrives transitively via klap's `api` dependency; only the plugin (above) is needed here.
    implementation(project(":klap"))
}
