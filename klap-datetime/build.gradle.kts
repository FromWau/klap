plugins {
    id("klap-library")
}

description = "kotlinx-datetime values for klap: .localDate(), .localDateTime() and .timeZone()."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":klap"))
            api(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kern.result.test)
        }
    }
}
