plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // MAIN source, not test source: every fixture module consumes this DSL from its own test source set,
    // and Gradle cannot put another project's test output on a compile classpath.
    //
    // `api` on :klap because `Cli` and `ValueScope` appear in ParitySuite's own signatures, so a consumer
    // cannot call it without them. kotlin.test is `fail()` inside the bodies only, and every fixture is
    // given it directly by the `klap-fixture` convention plugin, so exposing it here bought nothing.
    api(project(":klap"))
    implementation(kotlin("test"))
}
