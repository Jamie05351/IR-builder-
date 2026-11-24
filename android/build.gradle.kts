plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "jdsp.irbuilder.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "jdsp.irbuilder"
        minSdk = 26
        targetSdk = 35
        // Each CI build passes a higher number so a new APK installs over the old one.
        versionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()
        versionName = (findProperty("appVersion") as String?) ?: "1.0.0"
    }

    // One fixed signing key for every build (see android/sideload.keystore), so each new APK
    // installs as an update instead of being refused as a different app. It's a sideload key,
    // not a store key: anyone with this repository can sign with it.
    signingConfigs {
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "jdspirbuilder"
            keyAlias = "sideload"
            keyPassword = "jdspirbuilder"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sideload") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The screens are shared with the Windows app; only the dialogs and the activity live here.
    sourceSets["main"].kotlin.srcDir("../shared-ui/src/main/kotlin")

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    // Robolectric runs the real Android framework on the JVM: the app is launched and driven
    // in a unit test, no device or emulator needed.
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric's Android framework jar, fetched by Gradle (cached, retried) rather than by
// Robolectric at test time, which Maven Central can rate-limit; the tests then run offline.
val robolectricRuntime: Configuration by configurations.creating
dependencies { robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
val robolectricJars = tasks.register<Copy>("robolectricJars") {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-jars"))
    // Already there (an earlier build, or CI's cache): don't fetch ~150 MB again.
    val jars = layout.buildDirectory.dir("robolectric-jars")
    onlyIf { jars.get().asFile.listFiles { f -> f.name.endsWith(".jar") }.isNullOrEmpty() }
}
tasks.withType<Test>().configureEach {
    dependsOn(robolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.path)
}
