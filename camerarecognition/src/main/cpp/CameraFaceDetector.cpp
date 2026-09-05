//
// CameraFaceDetector.cpp
// 相机识别模块：实时人脸检测与跟踪 JNI 层
//

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <opencv2/core.hpp>
#include "FaceTracker.h"

#define TAG "CR_FaceDetectorJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace camerarecognition;

namespace {

inline FaceTracker *asTracker(jlong handle) {
    return reinterpret_cast<FaceTracker *>(handle);
}

std::vector<std::string> toStringVector(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto *s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (s == nullptr) continue;
        const char *c = env->GetStringUTFChars(s, nullptr);
        if (c != nullptr) out.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeCreate(
        JNIEnv *env, jobject, jobjectArray faceCascadePaths,
        jobjectArray featureCascadePaths) {
    auto *tracker = new FaceTracker();
    bool ok = false;
    try {
        ok = tracker->init(toStringVector(env, faceCascadePaths),
                           toStringVector(env, featureCascadePaths));
    } catch (const std::exception &e) {
        LOGE("tracker init exception: %s", e.what());
        ok = false;
    }
    if (!ok) {
        delete tracker;
        return 0;
    }
    return reinterpret_cast<jlong>(tracker);
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    delete asTracker(handle);
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeSetSurface(
        JNIEnv *env, jobject, jlong handle, jobject surface) {
    FaceTracker *tracker = asTracker(handle);
    if (tracker == nullptr) return;
    if (surface != nullptr) {
        tracker->setWindow(ANativeWindow_fromSurface(env, surface));
    } else {
        tracker->setWindow(nullptr);
    }
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeResetTracking(
        JNIEnv *, jobject, jlong handle) {
    FaceTracker *tracker = asTracker(handle);
    if (tracker != nullptr) tracker->resetTracking();
}

JNIEXPORT jint JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativePostFrame(
        JNIEnv *env, jobject, jlong handle,
        jbyteArray data, jint w, jint h, jint rotation, jboolean mirror) {
    FaceTracker *tracker = asTracker(handle);
    if (tracker == nullptr) return 0;
    jsize len = env->GetArrayLength(data);
    if (len < w * h * 3 / 2) return 0;

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    int faces = tracker->postFrame(
            reinterpret_cast<const uint8_t *>(buf),
            w, h, rotation, mirror == JNI_TRUE);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return faces;
}

JNIEXPORT jstring JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeOpencvVersion(
        JNIEnv *env, jobject) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

} // extern "C"
