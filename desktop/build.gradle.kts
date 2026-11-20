import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "jdsp.irbuilder.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "JDSP-IR-Builder"
            packageVersion = "1.0.0"
            description = "Multi-seat Low/Mid/High correction IR designer for J_DSP"
            vendor = "J_DSP"
            windows {
                menuGroup = "J_DSP"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1d3c2a-8b47-4e59-9a0e-3c5b7d21e4f8"
            }
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.path)
}
