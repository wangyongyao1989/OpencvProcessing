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


    // =========================================================================
    // 图像锐化方法（基于《数字图像与视频处理》2.4 节）
    // =========================================================================

    /** 水平垂直差分法 (式 2-56, 输出式 2-58)：一阶梯度，仅留边缘点 */
    @JvmStatic
    external fun sharpGradientHV(bitmap: Bitmap): Bitmap?

    /** Roberts 梯度/交叉差分 (式 2-57)：2×2 窗口对角差分，边缘定位精度高 */
    @JvmStatic
    external fun sharpRoberts(bitmap: Bitmap): Bitmap?

    /** Sobel 算子 (式 2-63~2-66)：带平均因素的梯度，抗噪且边缘粗亮 */
    @JvmStatic
    external fun sharpSobel(bitmap: Bitmap): Bitmap?

    /** 拉普拉斯直接锐化 H1 (式 2-70, 2-71)：二阶各向同性，仅显示边缘 */
    @JvmStatic
    external fun sharpLaplacianH1(bitmap: Bitmap): Bitmap?

    /** 合成拉普拉斯锐化 H6 (式 2-72~2-74)：g=f−∇²f，锐化同时保背景 */
    @JvmStatic
    external fun sharpLaplacianH6(bitmap: Bitmap): Bitmap?

    /** 合成拉普拉斯锐化 H7 (8 邻域模板)：边缘增强比 H6 更强 */
    @JvmStatic
    external fun sharpLaplacianH7(bitmap: Bitmap): Bitmap?

    /** 理想高通滤波锐化 (式 2-75, 2-76)：高频边缘附加原图，有振铃 */
    @JvmStatic
    external fun sharpIdealHighPass(bitmap: Bitmap, d0: Double): Bitmap?

    /** 高斯高通滤波锐化 (式 2-79)：平滑过渡无振铃 */
    @JvmStatic
    external fun sharpGaussianHighPass(bitmap: Bitmap, d0: Double): Bitmap?


    // =========================================================================
    // 图像的同态滤波方法（基于《数字图像与视频处理》2.5 节）
    // =========================================================================

    /** 同态滤波全流程 (式 2-81~2-87)：ln→DFT→H·F→IDFT→exp，压缩照度增强反射 */
    @JvmStatic
    external fun homoFilter(bitmap: Bitmap, d0: Double, c: Double,
                            hl: Double, hh: Double): Bitmap?

    /** 对数域可视化 (式 2-82)：z = ln f，乘性模型转加性 */
    @JvmStatic
    external fun homoLogDomain(bitmap: Bitmap): Bitmap?

    /** 照度分量 i 估计 (式 2-81)：低频缓变光照场 */
    @JvmStatic
    external fun homoIllumination(bitmap: Bitmap): Bitmap?

    /** 反射分量 r 估计 (式 2-81)：高频细节/边缘 */
    @JvmStatic
    external fun homoReflectance(bitmap: Bitmap): Bitmap?


    // =========================================================================
    // 基于 Retinex 理论的图像增强方法（基于 2.6 节）
    // =========================================================================

    /** 光照分量 L 估计 (式 2-88, 2-92)：高斯环绕卷积 */
    @JvmStatic
    external fun retinexIllumination(bitmap: Bitmap, sigma: Double): Bitmap?

    /** 反射分量 r 可视化 (式 2-89~2-91)：灰度显示 */
    @JvmStatic
    external fun retinexReflectance(bitmap: Bitmap, sigma: Double): Bitmap?

    /** SSR 单尺度 Retinex (式 2-91, 2-92) */
    @JvmStatic
    external fun retinexSSR(bitmap: Bitmap, sigma: Double): Bitmap?

    /** MSR 多尺度 Retinex (式 2-93, 2-94)：多尺度加权融合 */
    @JvmStatic
    external fun retinexMSR(bitmap: Bitmap, sigmas: DoubleArray): Bitmap?

    /** MSRCR 带颜色恢复的多尺度 Retinex (式 2-95, 2-96) */
    @JvmStatic
    external fun retinexMSRCR(bitmap: Bitmap, sigmas: DoubleArray): Bitmap?


    // =========================================================================
    // 彩色增强方法（基于 2.7 节：伪彩色 + 假彩色）
    // =========================================================================

    /** 灰度分层法 两层切割 (图 2-47)：两种颜色伪彩色 */
    @JvmStatic
    external fun colorGraySlice2(bitmap: Bitmap, l1: Int): Bitmap?

    /** 灰度分层法 多平面切割 (图 2-48)：M+1 种颜色伪彩色 */
    @JvmStatic
    external fun colorGraySliceMulti(bitmap: Bitmap, m: Int): Bitmap?

    /** 灰度级彩色变换 (图 2-49)：三通道不同变换特性合成连续彩色 */
    @JvmStatic
    external fun colorGrayLevelTransform(bitmap: Bitmap): Bitmap?

    /** 频率域滤波法伪彩色 (图 2-50)：低通/带通/高通 → R/G/B */
    @JvmStatic
    external fun colorFrequencyPseudo(bitmap: Bitmap): Bitmap?

    /** 假彩色 线性映射 (式 2-97)：通道轮换矩阵 */
    @JvmStatic
    external fun colorFalseLinear(bitmap: Bitmap): Bitmap?

    /** 假彩色 细节赋予绿色 (式 2-97)：人眼对绿色灵敏 */
    @JvmStatic
    external fun colorFalseGreen(bitmap: Bitmap): Bitmap?

    /** 假彩色 细节赋予蓝色 (式 2-97)：蓝色对比灵敏度高 */
    @JvmStatic
    external fun colorFalseBlue(bitmap: Bitmap): Bitmap?

    /** 假彩色 多光谱合成 (式 2-98)：波段差分变换 */
    @JvmStatic
    external fun colorFalseMultiSpectral(bitmap: Bitmap): Bitmap?
}
