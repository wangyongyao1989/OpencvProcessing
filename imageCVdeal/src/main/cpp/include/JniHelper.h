#ifndef OPENCVDEAL_JNIHELPER_H
#define OPENCVDEAL_JNIHELPER_H

#include <jni.h>
#include <cstdint>
#include <vector>
#include <opencv2/core.hpp>

namespace JniHelper {

    /**
     * @brief 辅助函数：将灰度 cv::Mat 转换为 ARGB_8888 Bitmap
     */
    jobject grayMatToBitmap(JNIEnv *env, const cv::Mat &gray, int width, int height);

    /**
     * @brief 辅助函数：将 Android Bitmap 转换为**自持有内存**的 RGBA cv::Mat。
     *
     * 返回的 Mat 必须在 unlockPixels 之前完成拷贝，否则像素指针悬垂。
     * 不支持的 Bitmap 格式（ALPHA_8 / RGBA_F16 等）返回空 Mat。
     */
    cv::Mat bitmapToMat(JNIEnv *env, jobject bitmap);

    /**
     * @brief 全工程统一的「输入转灰度」：4 通道走 RGBA2GRAY、3 通道走 RGB2GRAY、
     *        单通道拷贝。各算法文件不得再各自实现，避免灰度口径不一致。
     */
    cv::Mat toGray(const cv::Mat &src);

    /**
     * @brief 从 rawBuffer 构造 cv::Mat（不拷贝）。
     */
    cv::Mat wrapRawMat(const uint8_t *data, int width, int height,
                       int bitsAllocated, int pixelSigned);

    /**
     * @brief 把 jbyteArray 拷贝到本地 std::vector<uint8_t>。
     */
    bool copyJByteArray(JNIEnv *env, jbyteArray src, std::vector<uint8_t> &dst);

    /**
     * @brief 把 CV_8UC1 灰度图转为 RGBA 字节数组并返回 jbyteArray。
     */
    bool gray8uToRgbaJBytes(JNIEnv *env, const cv::Mat &gray, jbyteArray &outRgba);

}

#endif //OPENCVDEAL_JNIHELPER_H
