#include <jni.h>
#include <vector>
#include <android/log.h>
#include <android/bitmap.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include "include/JniHelper.h"
#include "include/GrayTransform.h"
#include "include/ImageSmoothDenoise.h"
#include "include/ImageSharpen.h"
#include "include/ImageHomomorphic.h"
#include "include/ImageRetinex.h"
#include "include/ImageColorEnhance.h"

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
// 图像平滑与去噪 JNI 方法（基于《数字图像与视频处理》2.3 节）
// =============================================================================

// --- 4-邻域平均法 (式 2-23 ~ 式 2-25) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothNeighborhoodAverage4(JNIEnv *env, jclass,
                                                                          jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::neighborhoodAverage4(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 8-邻域平均法 (式 2-26, 式 2-27) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothNeighborhoodAverage8(JNIEnv *env, jclass,
                                                                          jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::neighborhoodAverage8(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 阈值邻域平均法 (式 2-28) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothThresholdAverage(JNIEnv *env, jclass,
                                                                      jobject bitmap,
                                                                      jdouble t) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::thresholdAverage(src, t);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 3×3 中值滤波 (式 2-29, 式 2-30) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothMedian3x3(JNIEnv *env, jclass,
                                                               jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::medianFilter3x3(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 5×5 十字形中值滤波 (式 2-29, 式 2-30；图 2-23f) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothMedianCross5x5(JNIEnv *env, jclass,
                                                                    jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::medianFilterCross5x5(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 理想低通滤波 (式 2-40 ~ 式 2-42) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothIdealLowPass(JNIEnv *env, jclass,
                                                                  jobject bitmap,
                                                                  jdouble d0) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::idealLowPass(src, d0);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 高斯低通滤波 (式 2-45, 式 2-46) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothGaussianLowPass(JNIEnv *env, jclass,
                                                                      jobject bitmap,
                                                                      jdouble d0) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::gaussianLowPass(src, d0);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 非局部均值 NLM 去噪 (式 2-31, 式 2-32) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothNlmDenoise(JNIEnv *env, jclass,
                                                                 jobject bitmap,
                                                                 jdouble h) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSmoothDenoise::nlmDenoise(src, h);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}


// =============================================================================
// 图像锐化 JNI 方法（基于《数字图像与视频处理》2.4 节）
// =============================================================================

// --- 水平垂直差分法 (式 2-56, 输出式 2-58) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpGradientHV(JNIEnv *env, jclass,
                                                              jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::gradientHorizVert(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- Roberts 梯度（交叉差分）(式 2-57) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpRoberts(JNIEnv *env, jclass,
                                                           jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::robertsGradient(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- Sobel 算子 (式 2-63 ~ 式 2-66) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpSobel(JNIEnv *env, jclass,
                                                         jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::sobelOperator(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 拉普拉斯直接锐化（模板 H1）(式 2-70, 式 2-71) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpLaplacianH1(JNIEnv *env, jclass,
                                                               jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::laplacianDirect(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 合成拉普拉斯锐化（模板 H6）(式 2-72 ~ 式 2-74) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpLaplacianH6(JNIEnv *env, jclass,
                                                               jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::laplacianCompositeH6(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 合成拉普拉斯锐化（模板 H7，8 邻域）---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpLaplacianH7(JNIEnv *env, jclass,
                                                               jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::laplacianCompositeH7(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 理想高通滤波锐化 (式 2-75, 式 2-76) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpIdealHighPass(JNIEnv *env, jclass,
                                                                 jobject bitmap,
                                                                 jdouble d0) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::idealHighPassSharpen(src, d0);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 高斯高通滤波锐化 (式 2-79) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpGaussianHighPass(JNIEnv *env, jclass,
                                                                    jobject bitmap,
                                                                    jdouble d0) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageSharpen::gaussianHighPassSharpen(src, d0);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}


// =============================================================================
// 图像的同态滤波 JNI 方法（基于《数字图像与视频处理》2.5 节）
// =============================================================================

// --- 同态滤波全流程 (式 2-81 ~ 式 2-87) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoFilter(JNIEnv *env, jclass,
                                                         jobject bitmap,
                                                         jdouble d0, jdouble c,
                                                         jdouble hl, jdouble hh) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageHomomorphic::homomorphicFilter(src, d0, c, hl, hh);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 对数域可视化 (式 2-82) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoLogDomain(JNIEnv *env, jclass,
                                                            jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageHomomorphic::logDomainImage(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 照度分量估计 (式 2-81 低频分量) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoIllumination(JNIEnv *env, jclass,
                                                               jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageHomomorphic::illuminationComponent(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 反射分量估计 (式 2-81 高频分量) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoReflectance(JNIEnv *env, jclass,
                                                              jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageHomomorphic::reflectanceComponent(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// =============================================================================
// 基于 Retinex 理论的图像增强 JNI 方法（基于 2.6 节）
// =============================================================================

// --- 光照分量估计 (式 2-88, 式 2-92) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexIllumination(JNIEnv *env, jclass,
                                                                  jobject bitmap,
                                                                  jdouble sigma) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageRetinex::illuminationEstimate(src, sigma);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 反射分量可视化 (式 2-89 ~ 式 2-91) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexReflectance(JNIEnv *env, jclass,
                                                                 jobject bitmap,
                                                                 jdouble sigma) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageRetinex::reflectanceGray(src, sigma);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- SSR 单尺度 Retinex (式 2-91, 式 2-92) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexSSR(JNIEnv *env, jclass,
                                                         jobject bitmap,
                                                         jdouble sigma) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageRetinex::ssr(src, sigma);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- MSR 多尺度 Retinex (式 2-93, 式 2-94) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexMSR(JNIEnv *env, jclass,
                                                         jobject bitmap,
                                                         jdoubleArray sigmas) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);

    jsize len = env->GetArrayLength(sigmas);
    std::vector<double> vec(len);
    env->GetDoubleArrayRegion(sigmas, 0, len, vec.data());
    cv::Mat result = ImageRetinex::msr(src, vec);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- MSRCR 带颜色恢复的多尺度 Retinex (式 2-95, 式 2-96) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexMSRCR(JNIEnv *env, jclass,
                                                           jobject bitmap,
                                                           jdoubleArray sigmas) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);

    jsize len = env->GetArrayLength(sigmas);
    std::vector<double> vec(len);
    env->GetDoubleArrayRegion(sigmas, 0, len, vec.data());
    cv::Mat result = ImageRetinex::msrcr(src, vec);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// =============================================================================
// 彩色增强 JNI 方法（基于 2.7 节：伪彩色 + 假彩色）
// =============================================================================

// --- 灰度分层法 两层切割 (图 2-47) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorGraySlice2(JNIEnv *env, jclass,
                                                              jobject bitmap,
                                                              jint l1) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::graySlice2(src, l1);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 灰度分层法 多平面切割 (图 2-48) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorGraySliceMulti(JNIEnv *env, jclass,
                                                                  jobject bitmap,
                                                                  jint m) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::graySliceMulti(src, m);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 灰度级彩色变换 (图 2-49) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorGrayLevelTransform(JNIEnv *env, jclass,
                                                                      jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::grayLevelColorTransform(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 频率域滤波法伪彩色 (图 2-50) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFrequencyPseudo(JNIEnv *env, jclass,
                                                                   jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::frequencyPseudoColor(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 假彩色 线性映射 (式 2-97) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseLinear(JNIEnv *env, jclass,
                                                               jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::falseColorLinear(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 假彩色 细节赋予绿色 (式 2-97) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseGreen(JNIEnv *env, jclass,
                                                              jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::falseColorGreenSensitive(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 假彩色 细节赋予蓝色 (式 2-97) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseBlue(JNIEnv *env, jclass,
                                                             jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::falseColorBlueDetail(src);
    return JniHelper::grayMatToBitmap(env, result, info.width, info.height);
}

// --- 假彩色 多光谱合成 (式 2-98) ---
extern "C" JNIEXPORT jobject JNICALL
Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseMultiSpectral(JNIEnv *env, jclass,
                                                                      jobject bitmap) {
    cv::Mat src = JniHelper::bitmapToMat(env, bitmap);
    if (src.empty()) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    cv::Mat result = ImageColorEnhance::falseColorMultiSpectral(src);
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
        // 图像平滑与去噪方法
        {"smoothNeighborhoodAverage4", "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothNeighborhoodAverage4},
        {"smoothNeighborhoodAverage8", "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothNeighborhoodAverage8},
        {"smoothThresholdAverage", "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothThresholdAverage},
        {"smoothMedian3x3",        "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothMedian3x3},
        {"smoothMedianCross5x5",   "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothMedianCross5x5},
        {"smoothIdealLowPass",     "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothIdealLowPass},
        {"smoothGaussianLowPass",  "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothGaussianLowPass},
        {"smoothNlmDenoise",       "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_smoothNlmDenoise},
        // 图像锐化方法
        {"sharpGradientHV",        "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpGradientHV},
        {"sharpRoberts",           "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpRoberts},
        {"sharpSobel",             "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpSobel},
        {"sharpLaplacianH1",       "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpLaplacianH1},
        {"sharpLaplacianH6",       "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpLaplacianH6},
        {"sharpLaplacianH7",       "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpLaplacianH7},
        {"sharpIdealHighPass",     "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpIdealHighPass},
        {"sharpGaussianHighPass",  "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_sharpGaussianHighPass},
        // 同态滤波方法
        {"homoFilter",             "(Landroid/graphics/Bitmap;DDDD)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoFilter},
        {"homoLogDomain",          "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoLogDomain},
        {"homoIllumination",       "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoIllumination},
        {"homoReflectance",        "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_homoReflectance},
        // Retinex 增强方法
        {"retinexIllumination",    "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexIllumination},
        {"retinexReflectance",     "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexReflectance},
        {"retinexSSR",             "(Landroid/graphics/Bitmap;D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexSSR},
        {"retinexMSR",             "(Landroid/graphics/Bitmap;[D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexMSR},
        {"retinexMSRCR",           "(Landroid/graphics/Bitmap;[D)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_retinexMSRCR},
        // 彩色增强方法
        {"colorGraySlice2",        "(Landroid/graphics/Bitmap;I)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorGraySlice2},
        {"colorGraySliceMulti",    "(Landroid/graphics/Bitmap;I)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorGraySliceMulti},
        {"colorGrayLevelTransform", "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorGrayLevelTransform},
        {"colorFrequencyPseudo",   "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFrequencyPseudo},
        {"colorFalseLinear",       "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseLinear},
        {"colorFalseGreen",        "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseGreen},
        {"colorFalseBlue",         "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseBlue},
        {"colorFalseMultiSpectral", "(Landroid/graphics/Bitmap;)Landroid/graphics/Bitmap;",
                (void *) Java_com_wangyao_opencvdeal_jni_OpencvDealJni_colorFalseMultiSpectral},
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
