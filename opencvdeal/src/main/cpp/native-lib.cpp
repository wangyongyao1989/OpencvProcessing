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

/**
 * 辅助函数：将灰度 cv::Mat 转换为 ARGB_8888 Bitmap
 */
static jobject grayMatToBitmap(JNIEnv *env, const cv::Mat &gray, int width, int height) {
    if (gray.empty()) return nullptr;

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethodID = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                                            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888FieldID = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Config = env->GetStaticObjectField(configClass, argb8888FieldID);

    jobject newBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethodID,
                                                    (jint) width, (jint) height,
                                                    argb8888Config);
    void *newPixels;
    if (AndroidBitmap_lockPixels(env, newBitmap, &newPixels) < 0) return nullptr;

    cv::Mat dst(height, width, CV_8UC4, newPixels);
    if (gray.channels() == 1) {
        cv::cvtColor(gray, dst, cv::COLOR_GRAY2RGBA);
    } else if (gray.channels() == 3) {
        cv::cvtColor(gray, dst, cv::COLOR_RGB2RGBA);
    } else if (gray.channels() == 4) {
        gray.copyTo(dst);
    }

    AndroidBitmap_unlockPixels(env, newBitmap);
    return newBitmap;
}

/**
 * 辅助函数：将 Android Bitmap 转换为 cv::Mat (RGBA)
 */
static cv::Mat bitmapToMat(JNIEnv *env, jobject bitmap) {
    if (bitmap == nullptr) return cv::Mat();
    AndroidBitmapInfo info;
    void *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return cv::Mat();
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return cv::Mat();

    cv::Mat src;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        src = cv::Mat(info.height, info.width, CV_8UC4, pixels);
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        src = cv::Mat(info.height, info.width, CV_8UC2, pixels);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return src;
}

// --- 灰度的线性变换 (式 2-1) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayLinearTransform(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble a, jdouble b,
                                                                    jdouble c, jdouble d) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::linearTransform(src, a, b, c, d);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 图像的反转变换 (图 2-3，线性变换的特例) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayInvertTransform(JNIEnv *env, jclass,
                                                                    jobject bitmap) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::invertTransform(src);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 三段分段线性变换 / 对比度扩展 (式 2-2, 式 2-3) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayPiecewiseLinear(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble a, jdouble b,
                                                                    jdouble c, jdouble d) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::piecewiseLinearTransform(src, a, b, c, d);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 削波处理 (图 2-6，式 2-3 特例) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayClipTransform(JNIEnv *env, jclass,
                                                                  jobject bitmap,
                                                                  jdouble a, jdouble b) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::clipTransform(src, a, b);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 阈值化 (图 2-7，式 2-3 特例) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayThresholdTransform(JNIEnv *env, jclass,
                                                                        jobject bitmap,
                                                                        jdouble threshold) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::thresholdTransform(src, threshold);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 对数变换 (式 2-4) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayLogTransform(JNIEnv *env, jclass,
                                                                  jobject bitmap, jdouble c) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::logTransform(src, c);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 伽马变换 / 幂次变换 (式 2-5 派生) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayGammaTransform(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble c, jdouble gamma) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::gammaTransform(src, c, gamma);
    return grayMatToBitmap(env, result, info.width, info.height);
}

// --- 直方图均衡化 (式 2-13, 式 2-14) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_grayHistogramEqualize(JNIEnv *env, jclass,
                                                                      jobject bitmap) {
    cv::Mat src = bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = GrayTransform::histogramEqualize(src);
    return grayMatToBitmap(env, result, info.width, info.height);
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
