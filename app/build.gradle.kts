import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    namespace = "com.vidlab.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vidlab.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 21
        versionName = "1.3.7"
    }

    signingConfigs {
        create("release") {
            storeFile = file("vidlab-release.jks")
            storePassword = "vidlab2026"
            keyAlias = "vidlab"
            keyPassword = "vidlab2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+1")
}

tasks.register("downloadLibrclone") {
    val rcloneLib = file("src/main/jniLibs/arm64-v8a/librclone.so")
    outputs.file(rcloneLib)
    onlyIf { !rcloneLib.exists() || rcloneLib.length() < 10_000_000L }
    doLast {
        rcloneLib.parentFile.mkdirs()
        val tmpFile = file("${rcloneLib.absolutePath}.tmp")
        var currentUrl = "https://github.com/lolzz11/VidLab/releases/download/deps-v1/librclone.so"
        println("Downloading librclone.so from $currentUrl ...")
        var redirects = 0
        while (redirects < 10) {
            val conn = URI.create(currentUrl).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            val code = conn.responseCode
            if (code in 300..399) {
                currentUrl = conn.getHeaderField("Location")
                    ?: throw GradleException("Redirect without Location header")
                conn.disconnect()
                redirects++
            } else if (code == 200) {
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                conn.disconnect()
                break
            } else {
                conn.disconnect()
                throw GradleException("Failed to download librclone.so: HTTP $code")
            }
        }
        if (!tmpFile.exists() || tmpFile.length() < 10_000_000L) {
            tmpFile.delete()
            throw GradleException("Downloaded librclone.so is missing or too small")
        }
        tmpFile.renameTo(rcloneLib)
        println("Downloaded librclone.so (${rcloneLib.length() / (1024 * 1024)} MB)")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadLibrclone")
}
