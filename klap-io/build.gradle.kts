plugins {
    id("klap-library")
}

description = "Files for klap: .path() reads an operand or option value as a CliPath, and .input() as a file or " +
    "standard input that readBytes reads through kern-fs and the action's Terminal."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":klap"))
            api(libs.kotlinx.io.core)
            api(libs.kern.fs)
        }

        commonTest.dependencies {
            implementation(libs.kern.result.test)
            implementation(libs.kern.terminal.test)
            implementation(libs.kern.fs.test)
        }
    }
}
