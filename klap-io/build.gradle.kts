plugins {
    id("klap-library")
}

description = "kotlinx-io paths for klap: .path() reads an operand or option value as a CliPath."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":klap"))
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(libs.kern.result.test)
        }
    }
}
