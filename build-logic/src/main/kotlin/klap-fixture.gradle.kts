plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":klap"))
    testImplementation(kotlin("test"))
    testImplementation(project(":example:parity"))
}

tasks.test {
    useJUnitPlatform()
}
