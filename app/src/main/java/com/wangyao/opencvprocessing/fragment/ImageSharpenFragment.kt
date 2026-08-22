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
import com.wangyao.opencvprocessing.databinding.FragmentImageSharpenLayoutBinding
import kotlin.concurrent.thread

/**
 * 图像锐化界面（三级页，与「图像灰度变换」「图像平滑与去噪」同级）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 第二章 2.4 节中的锐化方法。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」文本说明，包含对应公式编号与表达。
 *
 * 注：为兼顾频域 DFT 的处理耗时，加载时先按 inSampleSize 降采样到长边 ≤1080。
 */
class ImageSharpenFragment : BaseFragment() {

    private lateinit var binding: FragmentImageSharpenLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 九种锐化方法的元数据（对应 PDF 2.4 节）。 */
    private enum class Transform(
        val displayTitle: String,
        val formula: String,
    ) {
        ORIGINAL(
            "① 显示原始图像",
            buildString {
                append("【原理】不做任何处理，直接显示原始图像，用作对照基准。\n")
                append("【说明】图像锐化是与平滑相反的一类处理：图像模糊的实质是受到平均或积分运算的影响，")
                append("对其作逆运算（微分、差分、梯度运算）即可使边缘和轮廓变清晰；从频率域看，")
                append("模糊的实质是高频分量被衰减，故也可用高频提升滤波实现锐化。")
            }),
        HV_GRAD(
            "② 水平垂直差分法 (式 2-56)",
            buildString {
                append("【原理】梯度运算（2.4.1）：梯度值与相邻像素灰度差值成正比——")
                append("边缘区梯度大、平缓区较小、均匀区为零，故梯度运算后仅留下灰度急剧变化的边缘点。\n")
                append("【式 2-54】G[f(x,y)] = [∂f/∂x, ∂f/∂y]ᵀ = [Gx, Gy]ᵀ（梯度向量定义）\n")
                append("【式 2-55】G[f(x,y)] = √(Gx² + Gy²)（梯度幅度，各向同性算子，为降低运算量常用绝对值代替）\n")
                append("【式 2-56】水平垂直差分：G[f(i,j)] = |f(i+1,j) − f(i,j)| + |f(i,j+1) − f(i,j)|\n")
                append("【式 2-58】g(i,j) = G[f(i,j)]（输出灰度等于梯度幅度，本实现采用）\n")
                append("【说明】最后一行/列无法直接求梯度，用前一行/列梯度值近似代替。")
                append("若不想让平缓区变黑，还可采用阈值法输出（式 2-59~2-62）：如 g=G（G≥T）/ f（其他）、")
                append("背景取固定灰度 LG 或二值化输出等。")
            }),
        ROBERTS(
            "③ Roberts 梯度 (式 2-57)",
            buildString {
                append("【原理】罗伯茨梯度采用 2×2 窗口的交叉差分（对角方向）运算。\n")
                append("【式 2-57】G[f(i,j)] = |f(i+1,j+1) − f(i,j)| + |f(i,j+1) − f(i+1,j)|\n")
                append("【式 2-58】g(i,j) = G[f(i,j)]\n")
                append("【效果】对 45° 方向的陡峭边缘响应强、边缘定位精度高；")
                append("但没有平均因素，抗噪声能力差，噪声、条纹等也会被增强（图 2-32b）。")
            }),
        SOBEL(
            "④ Sobel 算子 (式 2-63)",
            buildString {
                append("【原理】Sobel 算子（2.4.2）在梯度差分基础上引入平均因素，")
                append("一定程度上克服了梯度锐化同时增强噪声的问题。\n")
                append("【式 2-63】Gx = [f(i+1,j−1)+2f(i+1,j)+f(i+1,j+1)] − [f(i−1,j−1)+2f(i−1,j)+f(i−1,j+1)]\n")
                append("【式 2-64】Gy = [f(i−1,j+1)+2f(i,j+1)+f(i+1,j+1)] − [f(i−1,j−1)+2f(i,j−1)+f(i+1,j−1)]\n")
                append("【式 2-65】Hx = [[−1,0,1],[−2,0,2],[−1,0,1]]，Hy = [[−1,−2,−1],[0,0,0],[1,2,1]]\n")
                append("【式 2-66】g(i,j) = √(Gx² + Gy²)；为简化计算用 g = |Gx| + |Gy|（本实现采用）\n")
                append("【效果】1) 引入平均因素，对随机噪声有一定平滑作用；")
                append("2) 相隔两行/两列之差分使边缘两侧元素同时增强，边缘显得粗而亮（图 2-35）。")
            }),
        LAP_H1(
            "⑤ Laplacian 直接锐化 H1 (式 2-70)",
            buildString {
                append("【原理】拉普拉斯算子（2.4.3）是二阶偏导数的线性组合，")
                append("且是各向同性（旋转不变）的线性运算，对任意方向边缘响应一致。\n")
                append("【式 2-67】∇²f = ∂²f/∂x² + ∂²f/∂y²\n")
                append("【式 2-68】∂²f/∂x² ≈ f(i+1,j) + f(i−1,j) − 2f(i,j)\n")
                append("【式 2-69】∂²f/∂y² ≈ f(i,j+1) + f(i,j−1) − 2f(i,j)\n")
                append("【式 2-70】∇²f = f(i+1,j)+f(i−1,j)+f(i,j+1)+f(i,j−1) − 4f(i,j)\n")
                append("【式 2-71】模板 H1 = [[0,1,0],[1,−4,1],[0,1,0]]（另有 H2~H5 等变体）\n")
                append("【效果】直接锐化后边缘增强，但背景信息消失（图 2-36）。")
                append("拉普拉斯算子同样会增强噪声，但作用比梯度法弱；用于边缘检测前宜先平滑。")
            }),
        LAP_H6(
            "⑥ 合成拉普拉斯 H6 (式 2-73)",
            buildString {
                append("【原理】为既体现拉普拉斯锐化效果又保留背景信息，")
                append("将原图像与拉普拉斯锐化结果叠加，得到合成拉普拉斯模板锐化。\n")
                append("【式 2-72】g(i,j) = f(i,j) − k∇²f（k 与扩散效应有关：太大会使轮廓边缘过冲，太小锐化不明显）\n")
                append("【式 2-73】k=1 时：g(i,j) = 5f(i,j) − f(i+1,j) − f(i−1,j) − f(i,j+1) − f(i,j−1)\n")
                append("【式 2-74】模板 H6 = [[0,−1,0],[−1,5,−1],[0,−1,0]]\n")
                append("【效果】边缘清晰的同时保留原图背景灰度（图 2-37），是最常用的拉普拉斯锐化模板。")
            }),
        LAP_H7(
            "⑦ 合成拉普拉斯 H7 (8 邻域)",
            buildString {
                append("【原理】H6~H9 统称合成拉普拉斯模板（2.4.3）。H7 为其 8 邻域版本，")
                append("中心系数 9 = 1 + 8（原图权重 1 加上 8 个邻域的锐化响应）。\n")
                append("【模板】H7 = [[−1,−1,−1],[−1,9,−1],[−1,−1,−1]]\n")
                append("【对比】H6 = [[0,−1,0],[−1,5,−1],[0,−1,0]] 只用 4 邻域；")
                append("同模板还有 H8 = [[0,1,0],[1,−3,1],[0,1,0]]、H9 = [[1,1,1],[1,−7,1],[1,1,1]]。\n")
                append("【效果】8 邻域参与锐化，对边缘与纹理的增强比 H6 更强（图 2-37）。")
            }),
        IHPF(
            "⑧ 理想高通滤波锐化 (式 2-75)",
            buildString {
                append("【原理】频率域高通滤波（2.4.4）：边缘、线条等细节对应高频分量，")
                append("高通滤波让高频顺利通过、抑制低频得到边缘信息，再将高频边缘附加到原图即实现锐化。\n")
                append("【式 2-40】G(u,v) = H(u,v)·F(u,v)\n")
                append("【式 2-75】H(u,v) = 1,  D(u,v) > D0；   0,  D(u,v) ≤ D0\n")
                append("【式 2-76】D(u,v) = √(u² + v²)（频率点到频率平面原点的距离）\n")
                append("【参数】本次演示 D0 = 30；锐化图像 g = f + 高频边缘（饱和截断到 [0,255]）。\n")
                append("【效果】H 从 0 到 1 陡峭突变，无法用实际元器件实现，滤波结果存在明显「振铃」现象。")
            }),
        GHPF(
            "⑨ 高斯高通滤波锐化 (式 2-79)",
            buildString {
                append("【原理】高斯高通与高斯低通（式 2-46）相对应，高低频率之间平滑过渡。\n")
                append("【式 2-79】H(u,v) = 1 − e^( −D²(u,v) / (2D0²) )\n")
                append("【参数】本次演示 D0 = 30；同样将高通边缘附加到原图：g = f + 高频边缘。\n")
                append("【效果】无振铃现象；D0 增大时增强效果更加明显，即使对微小物体和细线条也比较清晰（图 2-41）。\n")
                append("【扩展】常用高通滤波器还有：巴特沃兹高通（式 2-77/2-78，H 下降至最大值 1/2 处定义 D0，")
                append("高低频过渡平滑无振铃）与梯形高通（式 2-80，性能介于理想与平滑过渡滤波器之间，")
                append("滤波后图像既有一定模糊也有一定振铃）。")
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
        binding = FragmentImageSharpenLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02HvGrad,
            binding.cb03Roberts,
            binding.cb04Sobel,
            binding.cb05LapH1,
            binding.cb06LapH6,
            binding.cb07LapH7,
            binding.cb08Ilpf,
            binding.cb09Glpf,
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
                    Transform.HV_GRAD -> OpencvDealJni.sharpGradientHV(src)
                    Transform.ROBERTS -> OpencvDealJni.sharpRoberts(src)
                    Transform.SOBEL -> OpencvDealJni.sharpSobel(src)
                    Transform.LAP_H1 -> OpencvDealJni.sharpLaplacianH1(src)
                    Transform.LAP_H6 -> OpencvDealJni.sharpLaplacianH6(src)
                    Transform.LAP_H7 -> OpencvDealJni.sharpLaplacianH7(src)
                    Transform.IHPF -> OpencvDealJni.sharpIdealHighPass(src, 30.0)
                    Transform.GHPF -> OpencvDealJni.sharpGaussianHighPass(src, 30.0)
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
     * 频域 DFT 计算量较大，降采样可保证交互流畅。
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
