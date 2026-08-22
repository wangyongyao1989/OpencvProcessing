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


    // =========================================================================
    // 形态学图像处理方法（基于《数字图像与视频处理》第 3 章）
    // shape：0=矩形结构元素，1=圆形（椭圆）结构元素
    // =========================================================================

    /** Otsu 自动阈值二值化（二值形态学运算预处理） */
    @JvmStatic
    external fun morphBinarize(bitmap: Bitmap): Bitmap?

    /** 二值腐蚀 (式 3-9)：A㊀B = {x | B+x ⊆ A}，缩小目标 */
    @JvmStatic
    external fun morphBinErode(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 二值膨胀 (式 3-10/3-11)：扩大目标、桥接裂缝 */
    @JvmStatic
    external fun morphBinDilate(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 二值开运算 (式 3-15/3-16)：先腐蚀后膨胀，去亮噪声毛刺 */
    @JvmStatic
    external fun morphBinOpen(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 二值闭运算 (式 3-17)：先膨胀后腐蚀，填暗孔洞细缝 */
    @JvmStatic
    external fun morphBinClose(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 对偶性 (式 3-13/3-14)：Aᶜ㊀B̂ = (A⊕B)ᶜ */
    @JvmStatic
    external fun morphBinDuality(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 内边缘 (式 3-20)：β内(A) = A − (A㊀B) */
    @JvmStatic
    external fun morphBinInnerEdge(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 外边缘 (式 3-21)：β外(A) = (A⊕B) − A */
    @JvmStatic
    external fun morphBinOuterEdge(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 梯度边缘 (式 3-22)：β梯度(A) = (A⊕B) − (A㊀B) */
    @JvmStatic
    external fun morphBinGradEdge(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 区域填充 (式 3-23)：Xk = (X(k-1)⊕B) ∩ Aᶜ 迭代 */
    @JvmStatic
    external fun morphBinFillHoles(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 骨架抽取 (式 3-24~3-28)：S(A) = ∪Sn(A) */
    @JvmStatic
    external fun morphBinSkeleton(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 细化 (式 3-29~3-31)：保持连通性的单像素宽骨架 */
    @JvmStatic
    external fun morphBinThinning(bitmap: Bitmap): Bitmap?

    /** 形态开-闭滤波 (式 3-34)：去前景+背景噪声 */
    @JvmStatic
    external fun morphBinOpenCloseFilter(bitmap: Bitmap, ksize: Int): Bitmap?

    /** 灰度腐蚀 (式 3-36/3-37)：min 滤波，变暗去亮细节 */
    @JvmStatic
    external fun morphGrayErode(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 灰度膨胀 (式 3-38/3-39)：max 滤波，变亮去暗细节 */
    @JvmStatic
    external fun morphGrayDilate(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 灰度开运算 (式 3-42)：削平比结构元素小的亮峰 */
    @JvmStatic
    external fun morphGrayOpen(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 灰度闭运算 (式 3-43)：填充比结构元素小的暗谷 */
    @JvmStatic
    external fun morphGrayClose(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 形态学梯度 (式 3-46)：g = (f⊕b) − (f㊀b) */
    @JvmStatic
    external fun morphGrayGradient(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 形态开-闭平滑 (式 3-47) */
    @JvmStatic
    external fun morphGrayOpenClose(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** 形态闭-开平滑 (式 3-48) */
    @JvmStatic
    external fun morphGrayCloseOpen(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** Top-Hat 高帽变换 (式 3-49)：f − (f°b) 检测亮波峰 */
    @JvmStatic
    external fun morphGrayTopHat(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** Bottom-Hat 低帽变换 (表 3-1)：(f·b) − f 检测暗波谷 */
    @JvmStatic
    external fun morphGrayBottomHat(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?

    /** Top-Hat 增强：f + TopHat − BottomHat */
    @JvmStatic
    external fun morphGrayTopHatEnhance(bitmap: Bitmap, ksize: Int, shape: Int): Bitmap?


    // =========================================================================
    // 图像分割方法（基于《数字图像与视频处理》第 4 章）
    // =========================================================================

    // --- 4.2 基于灰度阈值化的图像分割 ---

    /** 固定阈值分割 (式 4-1)：手动指定 T，二值化为前景/背景 */
    @JvmStatic
    external fun segFixedThreshold(bitmap: Bitmap, thresh: Double): Bitmap?

    /** 迭代阈值分割 (式 4-3)：均值迭代收敛至最优阈值 */
    @JvmStatic
    external fun segIterativeThreshold(bitmap: Bitmap): Bitmap?

    /** Otsu 最大类间方差分割 (式 4-4~4-10)：自动最优全局阈值 */
    @JvmStatic
    external fun segOtsu(bitmap: Bitmap): Bitmap?

    /** 自适应阈值分割 (式 4-2)：局部邻域均值动态阈值，适应不均匀光照 */
    @JvmStatic
    external fun segAdaptiveThreshold(bitmap: Bitmap, blockSize: Int, c: Double): Bitmap?

    /** 多级阈值分割：多阈值划分多灰度区间 */
    @JvmStatic
    external fun segMultiLevelThreshold(bitmap: Bitmap): Bitmap?

    /** 阈值化叠加可视化：分割掩膜红色高亮叠加原图 */
    @JvmStatic
    external fun segThresholdOverlay(bitmap: Bitmap): Bitmap?

    // --- 4.3 基于边缘检测的图像分割 ---

    /** Roberts 交叉差分边缘检测：2×2 对角差分，定位精度高 */
    @JvmStatic
    external fun segRoberts(bitmap: Bitmap): Bitmap?

    /** Sobel 边缘检测：带平均因素的一阶梯度，ksize 为核尺寸 */
    @JvmStatic
    external fun segSobel(bitmap: Bitmap, ksize: Int): Bitmap?

    /** Prewitt 边缘检测：均值差分模板 */
    @JvmStatic
    external fun segPrewitt(bitmap: Bitmap): Bitmap?

    /** Laplacian 二阶边缘检测：各向同性，对噪声敏感 */
    @JvmStatic
    external fun segLaplacianEdge(bitmap: Bitmap): Bitmap?

    /** LoG (Laplacian of Gaussian) 边缘检测：先高斯平滑再拉普拉斯 */
    @JvmStatic
    external fun segLoG(bitmap: Bitmap, ksize: Int, sigma: Double): Bitmap?

    /** Canny 最优边缘检测：非极大值抑制 + 双阈值滞后连接 */
    @JvmStatic
    external fun segCanny(bitmap: Bitmap, t1: Double, t2: Double): Bitmap?

    /** 边缘跟踪分割：边缘闭合 + 轮廓提取，完成区域分割 */
    @JvmStatic
    external fun segContourTrace(bitmap: Bitmap): Bitmap?

    // --- 4.4 基于区域的图像分割 ---

    /** 区域生长（中心种子）：图像中心为种子，th 为灰度相似性阈值 */
    @JvmStatic
    external fun segRegionGrowCenter(bitmap: Bitmap, th: Int): Bitmap?

    /** 区域生长（自动选种）：梯度极小值点自动选种 */
    @JvmStatic
    external fun segRegionGrowAutoSeed(bitmap: Bitmap, th: Int): Bitmap?

    /** 区域分裂-合并：四叉树分裂 + 区域邻接合并 */
    @JvmStatic
    external fun segSplitMerge(bitmap: Bitmap): Bitmap?

    /** 连通分量标记分割：区域标记伪彩色可视化 */
    @JvmStatic
    external fun segConnectedComponents(bitmap: Bitmap): Bitmap?

    /** 分水岭变换分割：标记驱动的浸水模拟 */
    @JvmStatic
    external fun segWatershed(bitmap: Bitmap): Bitmap?

    // --- 4.5 基于主动轮廓模型的图像分割 ---

    /** 基本贪心 Snake (式 4-40, 4-42~4-45)：能量最小化活动轮廓 */
    @JvmStatic
    external fun segSnake(bitmap: Bitmap, iterations: Int): Bitmap?

    /** Snake 内部能量对比 (式 4-41)：不同 α/β 平滑效果对比 */
    @JvmStatic
    external fun segSnakeSmoothCompare(bitmap: Bitmap): Bitmap?

    /** 气球力 Snake：加入膨胀/收缩力推进轮廓跨越平坦区 */
    @JvmStatic
    external fun segBalloonSnake(bitmap: Bitmap, iterations: Int): Bitmap?

    /** 测地线主动轮廓 (式 4-46)：g 停止函数调制外力 */
    @JvmStatic
    external fun segGeodesicContour(bitmap: Bitmap, iterations: Int): Bitmap?

    /** Chan-Vese 区域型主动轮廓 (式 4-47)：不依赖边缘的曲线演化 */
    @JvmStatic
    external fun segChanVese(bitmap: Bitmap, iterations: Int): Bitmap?
}
