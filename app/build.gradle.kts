
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "raf.console.quran7hours"
    compileSdk = 36

    defaultConfig {
        // DO NOT CHANGE: Google Play identity
        applicationId = "raf.console.quran7hours"

        minSdk = 27
        targetSdk = 35

        versionCode = 6
        versionName = "v2.622.98134.72789532"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // Required for seeking local MP3 via AssetDataSource.
        noCompress.addAll(
            listOf("mp3", "webp")
        )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// Room database schema export
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {

    // ==========================================
    // JETPACK COMPOSE
    // ==========================================

    implementation(
        platform("androidx.compose:compose-bom:2025.06.01")
    )

    implementation(
        "androidx.activity:activity-compose:1.10.1"
    )

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")

    implementation(
        "androidx.compose.material3:material3:1.4.0"
    )

    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    // ==========================================
    // LIFECYCLE
    // ==========================================

    implementation(
        "androidx.lifecycle:lifecycle-runtime-ktx:2.9.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-runtime-compose:2.9.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0"
    )

    implementation(
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0"
    )

    // ==========================================
    // ROOM DATABASE
    // ==========================================

    val roomVersion = "2.8.5"

    implementation(
        "androidx.room:room-runtime:$roomVersion"
    )

    // Room compiler via KSP
    ksp(
        "androidx.room:room-compiler:$roomVersion"
    )

    // Room paging integration
    implementation(
        "androidx.room:room-paging:$roomVersion"
    )

    // ==========================================
    // MEDIA3 / EXOPLAYER
    // ==========================================

    val media3Version = "1.11.1"

    implementation(
        "androidx.media3:media3-exoplayer:$media3Version"
    )

    implementation(
        "androidx.media3:media3-session:$media3Version"
    )

    implementation(
        "androidx.media3:media3-common:$media3Version"
    )

    // ==========================================
    // CORE
    // ==========================================

    implementation(
        "androidx.core:core-ktx:1.16.0"
    )

    // ==========================================
    // COROUTINES
    // ==========================================

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
    )

    // ==========================================
    // TESTING
    // ==========================================

    testImplementation(
        "junit:junit:4.13.2"
    )

    testImplementation(
        "androidx.room:room-testing:$roomVersion"
    )

    androidTestImplementation(
        "androidx.test.ext:junit:1.2.1"
    )

    androidTestImplementation(
        "androidx.test.espresso:espresso-core:3.6.1"
    )

    androidTestImplementation(
        "androidx.room:room-testing:$roomVersion"
    )

    debugImplementation(
        "androidx.compose.ui:ui-tooling"
    )
}
