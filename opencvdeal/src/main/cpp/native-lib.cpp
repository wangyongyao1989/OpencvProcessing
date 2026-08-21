#include <jni.h>
#include <vector>
#include <android/log.h>
#include <android/bitmap.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include "include/JniHelper.h"
#include "include/GrayTransform.h"

#define TAG "OpencvDealJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


extern "C" JNIEXPORT jstring JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_getOpencvVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

// =============================================================================
// 灰度变换 JNI 方法（基于《数字图像与视频处理》2.2 节）
// =============================================================================

// --- 灰度的线性变换 (式 2-1) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayLinearTransform(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble a, jdouble b,
                                                                    jdouble c, jdouble d) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::linearTransform(src, a, b, c, d);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 图像的反转变换 (图 2-3，线性变换的特例) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayInvertTransform(JNIEnv *env, jclass,
                                                                    jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::invertTransform(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 三段分段线性变换 / 对比度扩展 (式 2-2, 式 2-3) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayPiecewiseLinear(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble a, jdouble b,
                                                                    jdouble c, jdouble d) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::piecewiseLinearTransform(src, a, b, c, d);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 削波处理 (图 2-6，式 2-3 特例) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayClipTransform(JNIEnv *env, jclass,
                                                                  jobject bitmap,
                                                                  jdouble a, jdouble b) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::clipTransform(src, a, b);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 阈值化 (图 2-7，式 2-3 特例) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayThresholdTransform(JNIEnv *env, jclass,
                                                                        jobject bitmap,
                                                                        jdouble threshold) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::thresholdTransform(src, threshold);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 对数变换 (式 2-4) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayLogTransform(JNIEnv *env, jclass,
                                                                  jobject bitmap, jdouble c) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::logTransform(src, c);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 伽马变换 / 幂次变换 (式 2-5 派生) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayGammaTransform(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble c, jdouble gamma) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::gammaTransform(src, c, gamma);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 直方图均衡化 (式 2-13, 式 2-14) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayHistogramEqualize(JNIEnv *env, jclass,
                                                                      jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::histogramEqualize(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}


// =============================================================================
// JNI 注册
// =============================================================================
static const char *const kClassName = "com/wangyao/opencvdeal/jni/OpencvDealJni";
static const JNINativeMethod kMethods[] = {
        {"getOpencvVersion",       "()Ljava/lang/String;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_getOpencvVersion},
        // 灰度变换方法
        {"grayLinearTransform",    "(Landroid/graphics/Bitmap;DDDD)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayLinearTransform},
        {"grayInvertTransform",    "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayInvertTransform},
        {"grayPiecewiseLinear",    "(Landroid/graphics/Bitmap;DDDD)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayPiecewiseLinear},
        {"grayClipTransform",      "(Landroid/graphics/Bitmap;DD)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayClipTransform},
        {"grayThresholdTransform", "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayThresholdTransform},
        {"grayLogTransform",       "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayLogTransform},
        {"grayGammaTransform",     "(Landroid/graphics/Bitmap;DD)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayGammaTransform},
        {"grayHistogramEqualize",  "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayHistogramEqualize},
};

extern "C" jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clz = env->FindClass(kClassName);
    if (!clz) return JNI_ERR;
    if (env->RegisterNatives(clz, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) < 0)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}
