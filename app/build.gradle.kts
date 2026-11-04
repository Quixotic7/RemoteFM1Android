// SPDX-License-Identifier: GPL-3.0-only
// The app: Kotlin + Compose (AGP 9's built-in Kotlin), the native engine (native/CMakeLists.txt: librfmcore.so) and
// the cores, which scripts/build_cores_android.sh builds into build/cores/android/jniLibs before every build
// (incremental; -PrfmSkipCores=true skips it). The libraries are extracted at install (useLegacyPackaging), so the
// engine's loader finds the cores by listing nativeLibraryDir.
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// (A8) The release key, outside the repo: a properties file with storeFile, storePassword, keyAlias, keyPassword.
// Default ~/.android-keys/remotefm1-release.properties; -PRFM_SIGNING_PROPS=... or the env var RFM_SIGNING_PROPS
// picks another. Without it a release build stops (never signed with the debug key).
val signingProps: File = file(
    (project.findProperty("RFM_SIGNING_PROPS") ?: System.getenv("RFM_SIGNING_PROPS")
        ?: "${System.getProperty("user.home")}/.android-keys/remotefm1-release.properties").toString()
)
val releaseSigning: Properties? = if (!signingProps.isFile) null else Properties().also { p ->
    FileInputStream(signingProps).use { p.load(it) }
}

android {
    namespace = "com.quixotic7.remotefm1"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.quixotic7.remotefm1"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            // (A8) no R8: JNI names, the JSON code and the web editors' @JavascriptInterface stay as they are
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true      // (A8) BuildConfig.DEBUG: the test-only launch extras are ignored in release builds
    }
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(rootProject.layout.projectDirectory.dir("build/cores/android/jniLibs").asFile.path)
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

val buildCores = tasks.register<Exec>("buildCores") {
    description = "Builds the FM-1 cores (scripts/build_cores_android.sh)"
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build_cores_android.sh")
    onlyIf { !(project.findProperty("rfmSkipCores")?.toString()?.toBoolean() ?: false) }
}
tasks.named("preBuild") { dependsOn(buildCores) }

// (A8) a release build without the key stops here
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (releaseSigning == null) throw GradleException(
            "No release key: $signingProps not found. Set RFM_SIGNING_PROPS (Gradle property or env var) to the " +
                "properties file with storeFile, storePassword, keyAlias, keyPassword."
        )
    }
}

// (A8) the release APK under its public name: build/release/RemoteFM1-<versionName>.apk
val releaseApk = tasks.register<Copy>("releaseApk") {
    description = "Builds the signed release APK and copies it to build/release/RemoteFM1-<version>.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("build/release"))
    rename { "RemoteFM1-${android.defaultConfig.versionName}.apk" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.oboe)
    // (A6) the firmwares' web managers: WebView's document-start script
    implementation("androidx.webkit:webkit:1.14.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // (A3) org.json on the JVM for the theme tests; Compose UI tests on the emulator
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
