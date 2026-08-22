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


    // =========================================================================
    // 图像平滑与去噪方法（基于《数字图像与视频处理》2.3 节）
    // =========================================================================

    /** 4-邻域平均法 (式 2-24, 模板 H1 式 2-25)：十字均值模板，抑制噪声但边缘变模糊 */
    @JvmStatic
    external fun smoothNeighborhoodAverage4(bitmap: Bitmap): Bitmap?

    /** 8-邻域平均法 (式 2-26, 模板 H2 式 2-27)：环形均值模板，平滑更强 */
    @JvmStatic
    external fun smoothNeighborhoodAverage8(bitmap: Bitmap): Bitmap?

    /** 阈值邻域平均法 (式 2-28)：灰度差超过阈值 T 才用邻域均值代替，减轻模糊 */
    @JvmStatic
    external fun smoothThresholdAverage(bitmap: Bitmap, t: Double): Bitmap?

    /** 3×3 中值滤波 (式 2-29, 2-30)：非线性滤波，对椒盐噪声最有效且保护边缘 */
    @JvmStatic
    external fun smoothMedian3x3(bitmap: Bitmap): Bitmap?

    /** 5×5 十字形中值滤波 (式 2-30, 图 2-23f)：十字窗口取中值 */
    @JvmStatic
    external fun smoothMedianCross5x5(bitmap: Bitmap): Bitmap?

    /** 理想低通滤波 (式 2-40 ~ 2-42)：频域 D0 截止，去噪彻底但有振铃 */
    @JvmStatic
    external fun smoothIdealLowPass(bitmap: Bitmap, d0: Double): Bitmap?

    /** 高斯低通滤波 (式 2-45, 2-46)：频域高斯传递函数，无振铃 */
    @JvmStatic
    external fun smoothGaussianLowPass(bitmap: Bitmap, d0: Double): Bitmap?

    /** 非局部均值 NLM 去噪 (式 2-31, 2-32)：相似块加权平均，去噪同时保细节 */
    @JvmStatic
    external fun smoothNlmDenoise(bitmap: Bitmap, h: Double): Bitmap?
}
