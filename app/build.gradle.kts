import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.paymentsmstracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.paymentsmstracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing from an untracked keystore.properties (storeFile, storePassword, keyAlias,
    // keyPassword). Without it, assembleRelease produces an unsigned APK.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    if (keystoreProps != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            if (keystoreProps != null) signingConfig = signingConfigs.getByName("release")
            // R8: smaller APK, and the -assumenosideeffects rule strips debug/info logs.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

ksp {
    // Commit app/schemas/: each version's JSON is what a future migration test diffs against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    implementation(libs.androidx.lifecycle.viewmodel.ktx) // Use latest
    // LiveData
    implementation(libs.androidx.lifecycle.livedata.ktx) // Use latest
    // For by viewModels()
    implementation(libs.androidx.activity.ktx) // Use latest

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.recyclerview)
    // annotationProcessor "androidx.room:room-compiler:$room_version" // REMOVE THIS (for Java annotation processing)
    ksp(libs.androidx.room.compiler)
    // Optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.androidx.room.ktx)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    // Optional - Test helpers
    testImplementation(libs.androidx.room.testing)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json) // real org.json; android.jar only has stubs

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}