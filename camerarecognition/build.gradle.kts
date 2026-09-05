plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wangyao.camerarecognition"
    compileSdk = 37

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++11 -frtti -fexceptions")
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
                arguments("-DANDROID_STL=c++_shared")
            }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 与 imageCVdeal / videorecognition 相同的结构：OpenCV 预编译动态库
    // (libopencv_java4.so) 放在 src/main/cpp/libs/<abi>/ 下，
    // 通过 jniLibs 告诉 AGP 打包进 APK
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/cpp/libs")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    // CameraX：替代 legacy Camera API 的相机实现（core/camera2/lifecycle 三件套）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
