plugins {
    alias(libs.plugins.kotlinSerialization)
    id("klap-fixture")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
