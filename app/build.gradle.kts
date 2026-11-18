import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.melo.music"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.melo.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.7.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // One signing key for debug and release, so every new build installs over the existing app with its data.
    // keystore.properties (not in Git) points to the key; without it the standard debug key of this computer is used.
    val signingProps = Properties().apply {
        val source = rootProject.file("keystore.properties")
        if (source.exists()) source.inputStream().use { stream -> load(stream) }
    }
    signingConfigs {
        create("mauri") {
            storeFile = signingProps.getProperty("storeFile")?.let { path -> rootProject.file(path) } ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = signingProps.getProperty("storePassword") ?: "android"
            keyAlias = signingProps.getProperty("keyAlias") ?: "androiddebugkey"
            keyPassword = signingProps.getProperty("keyPassword") ?: "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("mauri") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("mauri")
            // R8 removes unused library code (smaller APK); names stay readable for crash reports. See proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("melo.live", providers.gradleProperty("meloLive").getOrElse("false"))
            it.systemProperty("melo.playlist", providers.gradleProperty("meloPlaylist").getOrElse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
            it.systemProperty("melo.matchCount", providers.gradleProperty("meloMatchCount").getOrElse("3"))
            it.maxHeapSize = "2g"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-session:1.9.4")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
