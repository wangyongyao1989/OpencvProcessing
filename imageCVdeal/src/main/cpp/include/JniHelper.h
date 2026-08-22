#ifndef OPENCVDEAL_JNIHELPER_H
#define OPENCVDEAL_JNIHELPER_H

#include <jni.h>
#include <vector>
#include <opencv2/core.hpp>

namespace JniHelper {

    /**
     * @brief 辅助函数：将灰度 cv::Mat 转换为 ARGB_8888 Bitmap
     */
    jobject grayMatToBitmap(JNIEnv *env, const cv::Mat &gray, int width, int height);

    /**
     * @brief 辅助函数：将 Android Bitmap 转换为 cv::Mat (RGBA)
     */
    cv::Mat bitmapToMat(JNIEnv *env, jobject bitmap);

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
