package com.wangyao.opencvprocessing.fragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.wangyao.opencvdeal.jni.OpencvDealJni
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentImageSmoothDenoiseLayoutBinding
import kotlin.concurrent.thread

/**
 * 图像平滑与去噪界面（三级页，与「图像灰度变换」同级）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 第二章 2.3 节中的平滑/去噪方法。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」文本说明，包含对应公式编号与表达。
 *
 * 注：为兼顾 NLM/频域 DFT 的处理耗时，加载时先按 inSampleSize 降采样到长边 ≤1080。
 */
class ImageSmoothDenoiseFragment : BaseFragment() {

    private lateinit var binding: FragmentImageSmoothDenoiseLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 九种平滑/去噪方法的元数据（对应 PDF 2.3 节）。 */
    private enum class Transform(
        val displayTitle: String,
        val formula: String,
    ) {
        ORIGINAL(
            "① 显示原始图像",
            buildString {
                append("【原理】不做任何处理，直接显示原始图像，用作对照基准。\n")
                append("【公式】g(x,y) = f(x,y)\n")
                append("【说明】用于与其他八种平滑/去噪结果作并排对比，观察各方法对噪声与细节的不同影响。")
            }),
        AVG4(
            "② 4-邻域平均法 (式 2-24)",
            buildString {
                append("【原理】邻域平均法 (2.3.2)：随机噪声对单个像素的影响是孤立的，用邻域像素的灰度平均值代替中心像素即可抑制噪声。\n")
                append("【式 2-23】g(x,y) = (1/M) Σ_{(i,j)∈N} f(i,j)\n")
                append("【式 2-24】g(x,y) = (1/4)·[f(x-1,y) + f(x,y-1) + f(x,y+1) + f(x+1,y)]\n")
                append("【式 2-25】模板 H1 = (1/4)·[[0,1,0],[1,0,1],[0,1,0]]（不含中心点）\n")
                append("【效果】算法简单、速度快，能抑制噪声，但边缘与细节同时被平滑，图像变模糊。")
            }),
        AVG8(
            "③ 8-邻域平均法 (式 2-26)",
            buildString {
                append("【原理】把 4-邻域扩大为 8-邻域（含 4 个对角方向），参与平均的像素更多。\n")
                append("【式 2-26】g(x,y) = (1/8)·Σ_{(i,j)∈N8} f(i,j)\n")
                append("        = (1/8)·[f(x-1,y-1)+f(x,y-1)+f(x+1,y-1)+f(x-1,y)\n")
                append("                +f(x+1,y)+f(x-1,y+1)+f(x,y+1)+f(x+1,y+1)]\n")
                append("【式 2-27】模板 H2 = (1/8)·[[1,1,1],[1,0,1],[1,1,1]]\n")
                append("【效果】平滑作用比 4-邻域更强，图像也随之更模糊；邻域半径越大模糊越重。")
            }),
        THRESHOLD_AVG(
            "④ 阈值邻域平均法 (式 2-28)",
            buildString {
                append("【原理】为减轻邻域平均法的模糊效应：仅当像素灰度与其邻域均值之差超过阈值 T 时才判定为噪声并用邻域均值代替，否则保留原灰度。\n")
                append("【式 2-28】\n")
                append("   g(x,y) = (1/M)Σ f(i,j),   当 |f(x,y) − (1/M)Σf(i,j)| > T\n")
                append("   g(x,y) = f(x,y),          其他\n")
                append("【参数】本次演示 T = 30。\n")
                append("【说明】阈值太大减弱去噪效果，太小则模糊效应增强，需结合图像特点选取。")
            }),
        MEDIAN3(
            "⑤ 中值滤波 3×3 (式 2-29)",
            buildString {
                append("【原理】中值滤波 (2.3.3) 是非线性滤波：滑动窗口内像素按灰度排序，取中间值代替窗口中心像素。在一定条件下可克服线性滤波带来的细节模糊，对脉冲（椒盐）噪声最有效，且不需要图像统计特性。\n")
                append("【式 2-29】yi = Med{ f(i-u), …, f(i), …, f(i+u) }，u = (m-1)/2\n")
                append("【式 2-30】yij = Med_W{ Fij }（W 为滤波窗口，此处取 3×3 方形窗口）\n")
                append("【特性】不影响阶跃/斜坡信号，对边缘有保护作用；对点、线、尖角细节多的图像不宜采用。")
            }),
        MEDIAN_CROSS(
            "⑥ 5×5 十字中值滤波 (式 2-30)",
            buildString {
                append("【原理】中值滤波的关键是窗口形状与大小（图 2-21）。十字形窗口对含尖顶角几何结构的图像较合适；窗口大小一般先取 3 再取 5 依次增大，且不要超过最小目标物尺寸。\n")
                append("【式 2-30】yij = Med_W{ Fij }，W 取十字形窗口：\n")
                append("   中心 + 水平(±1,±2) + 垂直(±1,±2) 共 9 个像素排序取中值\n")
                append("【对应】图 2-23f 采用的即 5×5 十字中值滤波（对椒盐噪声效果优于邻域平均法）。\n")
                append("【效果】对椒盐噪声抑制强，细节损失比 3×3 略大。")
            }),
        ILPF(
            "⑦ 理想低通滤波 (式 2-41)",
            buildString {
                append("【原理】频率域低通滤波 (2.3.5)：图像边缘与噪声都处于频域高频部分，衰减高频分量即可去噪。流程（图 2-27）：DFT → 乘传递函数 → IDFT。\n")
                append("【式 2-40】G(u,v) = H(u,v)·F(u,v)\n")
                append("【式 2-41】H(u,v) = 1,  D(u,v) ≤ D0；   0,  D(u,v) > D0\n")
                append("【式 2-42】D(u,v) = √(u² + v²)（频率点到频率平面原点的距离）\n")
                append("【参数】本次演示 D0 = 60。\n")
                append("【效果】截止频率内无损通过、外完全滤除；D0 越小去噪越彻底但越模糊，且会产生明显的「振铃(Ring)」现象。")
            }),
        GLPF(
            "⑧ 高斯低通滤波 (式 2-46)",
            buildString {
                append("【原理】高斯函数的傅里叶变换与反变换均为高斯函数，基于高斯函数的滤波在空间域与频率域之间有特殊联系。通带与阻带之间平滑过渡。\n")
                append("【式 2-45】H(u,v) = e^{ −D²(u,v) / (2σ²) }\n")
                append("【式 2-46】取 σ = D0：H(u,v) = e^{ −D²(u,v) / (2D0²) }\n")
                append("        当 D(u,v) = D0 时 H 下降到最大值的 0.607。\n")
                append("【参数】本次演示 D0 = 40。\n")
                append("【效果】与理想低通相比无振铃现象，模糊程度较轻；同类还有巴特沃兹低通（式 2-43/2-44，过渡平滑亦无振铃）与梯形低通（式 2-47，性能介于两者之间）。")
            }),
        NLM(
            "⑨ 非局部均值 NLM 去噪 (式 2-32)",
            buildString {
                append("【原理】基于非局部相似性 (2.3.4)：自然图像中存在位置不同但结构相似的图像块，当前像素值由所有相似块加权平均得到，权重取决于块间相似度而与空间距离无关。\n")
                append("【式 2-31】w(i,j) = exp( −d(i,j) / h² )，\n")
                append("        d(i,j) = ‖N(i) − N(j)‖²_{2,α}（高斯加权欧氏距离，α 为高斯核标准差）\n")
                append("【式 2-32】f'(i) = Σ_{j∈Φ} w(i,j)·f(j) / Σ_{j∈Φ} w(i,j)\n")
                append("【参数】本次演示 h = 10（衰减控制，越大去噪越强）。\n")
                append("【扩展】BM3D 在非局部相似基础上融合三维联合滤波；WNNM（式 2-33~2-39）将相似块聚合成低秩矩阵并按奇异值加权收缩；稀疏表示去噪（式 2-48~2-53，K-SVD）则利用过完备字典的稀疏分解。")
            }),
    }

    /** 按顺序排列的九项。 */
    private val transforms: Array<Transform> = Transform.values()

    /** 9 个 CheckBox（按 index 0..8 顺序）。 */
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageSmoothDenoiseLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Avg4,
            binding.cb03Avg8,
            binding.cb04ThresholdAvg,
            binding.cb05Median3,
            binding.cb06MedianCross,
            binding.cb07Ilpf,
            binding.cb08Glpf,
            binding.cb09Nlm,
        )
    }

    override fun initData() {
        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        // 初始显示第一项：原图
        applyTransform(Transform.ORIGINAL)
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        // 返回按钮：回到二级菜单「图像增强」
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE)
        }

        // 9 个 CheckBox 互斥单选
        val onChecked =
            CompoundButton.OnCheckedChangeListener { view, isChecked ->
                if (applyingChecked) return@OnCheckedChangeListener
                val idx = checkBoxes.indexOfFirst { it === view }
                if (idx < 0) return@OnCheckedChangeListener
                if (isChecked) {
                    applyingChecked = true
                    for ((i, cb) in checkBoxes.withIndex()) {
                        if (i != idx) cb.isChecked = false
                    }
                    applyingChecked = false
                    applyTransform(transforms[idx])
                } else {
                    // 不允许全部取消：至少保留一项勾选
                    if (checkBoxes.none { it.isChecked }) {
                        applyingChecked = true
                        view.isChecked = true
                        applyingChecked = false
                    }
                }
            }
        checkBoxes.forEach { it.setOnCheckedChangeListener(onChecked) }
    }

    // -------------------------------------------------------------------------
    // 处理执行与 UI 渲染
    // -------------------------------------------------------------------------

    /** 根据所选方法调用 JNI 并更新「结果图 + 原理公式」。 */
    private fun applyTransform(transform: Transform) {
        // 先显示原理公式（UI 立即刷新，图片异步处理好后再更新）。
        // 将【原理】【式 X-X】等段落标记转为加粗富文本，提升可读性。
        binding.tvFormula.text = transform.formula
            .replace("【".toRegex(), "<b>【")
            .replace("】".toRegex(), "】</b>")
            .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY) }
        binding.tvResultTitle.text = transform.displayTitle
        val src = originalBitmap ?: return

        thread(start = true) {
            val result: Bitmap? = runCatching {
                when (transform) {
                    Transform.ORIGINAL -> src
                    Transform.AVG4 -> OpencvDealJni.smoothNeighborhoodAverage4(src)
                    Transform.AVG8 -> OpencvDealJni.smoothNeighborhoodAverage8(src)
                    Transform.THRESHOLD_AVG -> OpencvDealJni.smoothThresholdAverage(src, 30.0)
                    Transform.MEDIAN3 -> OpencvDealJni.smoothMedian3x3(src)
                    Transform.MEDIAN_CROSS -> OpencvDealJni.smoothMedianCross5x5(src)
                    Transform.ILPF -> OpencvDealJni.smoothIdealLowPass(src, 60.0)
                    Transform.GLPF -> OpencvDealJni.smoothGaussianLowPass(src, 40.0)
                    Transform.NLM -> OpencvDealJni.smoothNlmDenoise(src, 10.0)
                }
            }.getOrElse { it.printStackTrace(); null } ?: src

            activity?.runOnUiThread {
                binding.ivResult.setImageBitmap(result)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    /**
     * 从 assets 加载图片并降采样（长边 ≤ [MAX_SIDE]）。
     * NLM 与频域 DFT 计算量较大，降采样可保证交互流畅。
     */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            // 第一次解码仅获取尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().assets.open(fileName).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SIDE) {
                sampleSize *= 2
            }
            // 第二次解码真正取像素
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = requireContext().assets.open(fileName).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp != null && bmp.config != Bitmap.Config.ARGB_8888) {
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            } else bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        private const val IMG_ASSET_NAME = "IMG_20260821_190758.jpg"
        private const val MAX_SIDE = 1080
    }
}
