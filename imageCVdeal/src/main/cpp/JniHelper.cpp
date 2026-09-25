#include "include/JniHelper.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/imgproc.hpp>

#define TAG "JniHelper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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

    // 关键：必须在 unlockPixels 之前把像素拷进调用方持有的 Mat。
    // 直接返回包裹 pixels 指针的 Mat 视图，解锁后即为悬垂引用
    // （AndroidBitmap_unlockPixels 不保证映射仍然有效）。
    cv::Mat src;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat(info.height, info.width, CV_8UC4, pixels).copyTo(src);
    } else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        // RGB_565：5R6G5B，小端 16bit。逐像素展开为 RGBA_8888，
        // 与上游各算法的 4 通道输入约定保持一致（旧实现按 CV_8UC2
        // 包装后被当灰度处理，像素全错）。
        src.create(info.height, info.width, CV_8UC4);
        const uint16_t *p16 = static_cast<const uint16_t *>(pixels);
        for (int y = 0; y < info.height; ++y) {
            const uint16_t *row = p16 + y * info.width;
            uchar *drow = src.ptr<uchar>(y);
            for (int x = 0; x < info.width; ++x) {
                uint16_t v = row[x];
                int r5 = (v >> 11) & 0x1F, g6 = (v >> 5) & 0x3F, b5 = v & 0x1F;
                // 低位复制到高位，保证 0/满量程精确映射
                drow[4 * x] = static_cast<uchar>((r5 * 255 + 16) / 31);
                drow[4 * x + 1] = static_cast<uchar>((g6 * 255 + 32) / 63);
                drow[4 * x + 2] = static_cast<uchar>((b5 * 255 + 16) / 31);
                drow[4 * x + 3] = 255;
            }
        }
    } else {
        // ALPHA_8 / RGBA_F16 / NONE 等：无法按 8bit 像素处理，明确拒绝
        AndroidBitmap_unlockPixels(env, bitmap);
        LOGE("bitmapToMat: unsupported bitmap format %d (%ux%u)",
             static_cast<int>(info.format), info.width, info.height);
        return cv::Mat();
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return src;
}

cv::Mat toGray(const cv::Mat &src) {
    cv::Mat gray;
    if (src.channels() == 4) cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
    else if (src.channels() == 3) cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
    else gray = src.clone();
    return gray;
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
