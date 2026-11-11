plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.facefollow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.facefollow"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // OpenCV native libs: keep all ABIs for the demo; trim for release
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    val camX = "1.3.4"
    implementation("androidx.camera:camera-core:$camX")
    implementation("androidx.camera:camera-camera2:$camX")
    implementation("androidx.camera:camera-lifecycle:$camX")
    implementation("androidx.camera:camera-view:$camX")

    // OpenCV (bundles native libs including the dnn module)
    implementation("org.opencv:opencv:4.10.0")

    // Coroutines (for model download / IO work)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 闭眼检测：MediaPipe Face Landmarker（Face Mesh + blendshape 睁闭眼分类头），Apache-2.0
    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    testImplementation("junit:junit:4.13.2")
}
