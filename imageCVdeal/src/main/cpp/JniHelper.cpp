#include "include/JniHelper.h"
#include <android/bitmap.h>
#include <opencv2/imgproc.hpp>

namespace JniHelper {

jobject grayMatToBitmap(JNIEnv *env, const cv::Mat &gray, int width, int height) {
    if (gray.empty()) return nullptr;

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethodID = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                                            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888FieldID = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Config = env->GetStaticObjectField(configClass, argb8888FieldID);

    jobject newBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethodID,
                                                    width, height,
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

cv::Mat bitmapToMat(JNIEnv *env, jobject bitmap) {
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

cv::Mat wrapRawMat(const uint8_t *data, int width, int height,
                   int bitsAllocated, int pixelSigned) {
    // 基础实现占位，可根据需求扩展
    int type = CV_8UC1;
    if (bitsAllocated == 16) {
        type = pixelSigned ? CV_16SC1 : CV_16UC1;
    }
    return cv::Mat(height, width, type, const_cast<uint8_t *>(data));
}

bool copyJByteArray(JNIEnv *env, jbyteArray src, std::vector<uint8_t> &dst) {
    if (!src) return false;
    jsize len = env->GetArrayLength(src);
    dst.resize(len);
    env->GetByteArrayRegion(src, 0, len, reinterpret_cast<jbyte *>(dst.data()));
    return true;
}

bool gray8uToRgbaJBytes(JNIEnv *env, const cv::Mat &gray, jbyteArray &outRgba) {
    if (gray.empty() || gray.type() != CV_8UC1) return false;
    cv::Mat rgba;
    cv::cvtColor(gray, rgba, cv::COLOR_GRAY2RGBA);
    int size = rgba.total() * rgba.elemSize();
    outRgba = env->NewByteArray(size);
    env->SetByteArrayRegion(outRgba, 0, size, reinterpret_cast<const jbyte *>(rgba.data));
    return true;
}

} // namespace JniHelper
