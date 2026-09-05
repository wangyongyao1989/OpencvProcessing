//
// ObjectTracker.cpp
// 相机识别·框选实物实时检测跟踪 JNI 层
//

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <opencv2/core.hpp>
#include "ObjectTrackerLogic.h"

#define TAG "CR_ObjectTrackJni"

using namespace camerarecognition;

namespace {

inline ObjectTrackerLogic *asTracker(jlong handle) {
    return reinterpret_cast<ObjectTrackerLogic *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeCreate(
        JNIEnv *, jobject) {
    auto *tracker = new ObjectTrackerLogic();
    return reinterpret_cast<jlong>(tracker);
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    delete asTracker(handle);
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeSetSurface(
        JNIEnv *env, jobject, jlong handle, jobject surface) {
    ObjectTrackerLogic *tracker = asTracker(handle);
    if (tracker == nullptr) return;
    if (surface != nullptr) {
        tracker->setWindow(ANativeWindow_fromSurface(env, surface));
    } else {
        tracker->setWindow(nullptr);
    }
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeResetTracking(
        JNIEnv *, jobject, jlong handle) {
    ObjectTrackerLogic *tracker = asTracker(handle);
    if (tracker != nullptr) tracker->resetTracking();
}

JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeSelectObject(
        JNIEnv *, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    ObjectTrackerLogic *tracker = asTracker(handle);
    if (tracker != nullptr) tracker->selectObject(x, y, w, h);
}

JNIEXPORT jfloatArray JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativePostFrame(
        JNIEnv *env, jobject, jlong handle,
        jbyteArray data, jint w, jint h, jint rotation, jboolean mirror) {
    jfloatArray out = env->NewFloatArray(6);
    if (out == nullptr) return nullptr;
    jfloat result[6] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
    ObjectTrackerLogic *tracker = asTracker(handle);
    if (tracker == nullptr) {
        env->SetFloatArrayRegion(out, 0, 6, result);
        return out;
    }
    jsize len = env->GetArrayLength(data);
    if (len < w * h * 3 / 2) {
        env->SetFloatArrayRegion(out, 0, 6, result);
        return out;
    }

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    float box[4];
    float sim = 0.f;
    int state = tracker->postFrame(
            reinterpret_cast<const uint8_t *>(buf),
            w, h, rotation, mirror == JNI_TRUE, box, &sim);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);

    result[0] = static_cast<jfloat>(state);
    result[1] = box[0];
    result[2] = box[1];
    result[3] = box[2];
    result[4] = box[3];
    result[5] = sim;
    env->SetFloatArrayRegion(out, 0, 6, result);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeGetTemplateThumb(
        JNIEnv *env, jobject, jlong handle) {
    ObjectTrackerLogic *tracker = asTracker(handle);
    std::vector<uint8_t> buf;
    if (tracker == nullptr || !tracker->getTemplateThumb(buf)) return env->NewByteArray(0);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(buf.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(buf.size()), reinterpret_cast<const jbyte *>(buf.data()));
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeGetTrackedThumb(
        JNIEnv *env, jobject, jlong handle) {
    ObjectTrackerLogic *tracker = asTracker(handle);
    std::vector<uint8_t> buf;
    if (tracker == nullptr || !tracker->getTrackedThumb(buf)) return env->NewByteArray(0);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(buf.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(buf.size()), reinterpret_cast<const jbyte *>(buf.data()));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeOpencvVersion(
        JNIEnv *env, jobject) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

} // extern "C"
