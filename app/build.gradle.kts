plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.wangyao.opencvprocessing"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wangyao.opencvprocessing"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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
    buildFeatures {
        viewBinding = true
    }

    // imageCVdeal 与 videorecognition 均自带 OpenCV 预编译库
    // (libopencv_java4.so，内容完全相同)，取其一即可
    packaging {
        jniLibs {
            pickFirsts += "lib/**/libopencv_java4.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(project(":imageCVdeal"))
    implementation(project(":digitalwatermark"))
    implementation(project(":qualityevaluation"))
    implementation(project(":contentsearch"))
    implementation(project(":imagerecognition"))
    implementation(project(":videorecognition"))
    implementation(project(":camerarecognition"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}