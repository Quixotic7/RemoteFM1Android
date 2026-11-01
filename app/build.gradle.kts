// SPDX-License-Identifier: GPL-3.0-only
// The app: Kotlin + Compose (AGP 9's built-in Kotlin), the native engine (native/CMakeLists.txt: librfmcore.so) and
// the cores, which scripts/build_cores_android.sh builds into build/cores/android/jniLibs before every build
// (incremental; -PrfmSkipCores=true skips it). The libraries are extracted at install (useLegacyPackaging), so the
// engine's loader finds the cores by listing nativeLibraryDir.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.quixotic7.remotefm1"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.quixotic7.remotefm1"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-a0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        prefab = true
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.oboe)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
