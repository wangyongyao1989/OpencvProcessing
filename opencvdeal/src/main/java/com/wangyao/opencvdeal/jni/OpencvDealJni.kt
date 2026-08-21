package com.wangyao.opencvdeal.jni

import android.util.Log
import android.graphics.Bitmap

/**
 * opencvdeal 模块的 JNI 入口
 */
object OpencvDealJni {

    private const val TAG = "OpencvDealJni"

    init {
        try {
            System.loadLibrary("opencv_java4")
            System.loadLibrary("opencvdeal_native")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libraries", t)
            throw t
        }
    }

    /**
     * 获取 OpenCV 版本号字符串。
     */
    @JvmStatic
    external fun getOpencvVersion(): String



    // =========================================================================
    // 灰度变换方法（基于《数字图像与视频处理》2.2 节）
    // =========================================================================

    /** 灰度的线性变换 (式 2-1)：将灰度范围 [a,b] 线性映射到 [c,d] */
    @JvmStatic
    external fun grayLinearTransform(bitmap: Bitmap, a: Double, b: Double, c: Double, d: Double): Bitmap?

    /** 图像的反转变换 (图 2-3)：黑变白、白变黑 */
    @JvmStatic
    external fun grayInvertTransform(bitmap: Bitmap): Bitmap?

    /** 三段分段线性变换 / 对比度扩展 (式 2-3)：压缩 [0,a] 和 [b,255]，扩展 [a,b] */
    @JvmStatic
    external fun grayPiecewiseLinear(bitmap: Bitmap, a: Double, b: Double, c: Double, d: Double): Bitmap?

    /** 削波处理 (图 2-6)：抑制 [0,a] 和 [b,255]，扩展 [a,b] */
    @JvmStatic
    external fun grayClipTransform(bitmap: Bitmap, a: Double, b: Double): Bitmap?

    /** 阈值化 (图 2-7)：大于阈值为白，否则为黑，得到二值图像 */
    @JvmStatic
    external fun grayThresholdTransform(bitmap: Bitmap, threshold: Double): Bitmap?

    /** 对数变换 (式 2-4)：扩展低灰度范围，压缩高灰度范围 */
    @JvmStatic
    external fun grayLogTransform(bitmap: Bitmap, c: Double): Bitmap?

    /** 伽马（指数）变换 (式 2-5)：γ>1 压缩高灰度，γ<1 压缩低灰度 */
    @JvmStatic
    external fun grayGammaTransform(bitmap: Bitmap, c: Double, gamma: Double): Bitmap?

    /** 直方图均衡化 (式 2-14)：使灰度分布均匀，增大对比度 */
    @JvmStatic
    external fun grayHistogramEqualize(bitmap: Bitmap): Bitmap?
}
