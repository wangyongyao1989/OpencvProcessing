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
import com.wangyao.opencvprocessing.databinding.FragmentImageHomomorphicLayoutBinding
import kotlin.concurrent.thread

/**
 * 图像的同态滤波界面（三级页）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 2.5 节选项。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」说明（式 2-81 ~ 2-87）。
 *
 * 注：为兼顾逐通道 DFT 的处理耗时，加载时先降采样到长边 ≤1080。
 */
class ImageHomomorphicFragment : BaseFragment() {

    private lateinit var binding: FragmentImageHomomorphicLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var applyingChecked = false

    /** 九种同态滤波选项的元数据（对应 PDF 2.5 节）。 */
    private enum class Transform(
        val displayTitle: String,
        val formula: String,
    ) {
        ORIGINAL(
            "① 显示原始图像",
            buildString {
                append("【原理】不做任何处理，直接显示原始图像，用作对照基准。\n")
                append("【模型】图像的同态滤波基于「照度-反射」成像模型：")
                append("图像 f 由照度分量 i（取决于光源，随空间缓慢变化、集中于低频）")
                append("与反射分量 r（取决于物体表面性质、变化剧烈、集中于高频）相乘而成。")
                append("两者卷绕在一起，直接用线性滤波无法分开，需在对数域处理（式 2-81 ~ 2-87 流程）。")
            }),
        ILLUM(
            "② 照度分量 i(x,y) 估计",
            buildString {
                append("【原理】照度分量取决于光源，空间变化缓慢（低频），")
                append("可用大 σ 高斯低通（σ=60）估计光照场。\n")
                append("【式 2-81】f(x,y) = i(x,y)·r(x,y)（图像 = 照度 × 反射）\n")
                append("【效果】显示光照的明暗分布（阴影、渐晕等缓慢变化结构），")
                append("动态范围过大正是因为照度分量起伏过大。")
            }),
        REFLECT(
            "③ 反射分量 r(x,y) 估计",
            buildString {
                append("【原理】反射分量取决于物体表面性质，变化剧烈（边缘/细节，高频）。")
                append("工程近似 r = f / (i + 1)，即原图除以照度估计。\n")
                append("【式 2-81】f(x,y) = i(x,y)·r(x,y)\n")
                append("【效果】归一化后显示物体的细节与边缘结构——")
                append("同态滤波要增强的正是这部分高频反射分量。")
            }),
        LOG(
            "④ 对数域 ln f (式 2-82)",
            buildString {
                append("【原理】对成像模型取对数，把乘性关系变为加性关系，")
                append("使照度与反射分量可在（对数域）频谱上线性分离。\n")
                append("【式 2-82】z(x,y) = ln f(x,y) = ln i(x,y) + ln r(x,y)\n")
                append("【式 2-83】Z(u,v) = F{z(x,y)}（DFT）\n")
                append("【式 2-84】Z(u,v) = I(u,v) + R(u,v)（照度谱与反射谱分离）\n")
                append("【效果】显示 ln f 的归一化图像，是进入频域滤波前的中间结果。")
            }),
        HOMO_STD(
            "⑤ 同态滤波 标准参数 (式 2-87)",
            buildString {
                append("【原理】对数域 DFT 后用传递函数 H 滤波（压缩低频照度、增强高频反射），")
                append("再 IDFT、取指数还原。\n")
                append("【式 2-85】S(u,v) = H(u,v)·Z(u,v)\n")
                append("【式 2-86】s(x,y) = F⁻¹[S(u,v)]\n")
                append("【式 2-87】g(x,y) = e^s(x,y)\n")
                append("【传递函数】H(u,v) = (HH − HL)·(1 − e^(−c·D²/D0²)) + HL（图 2-42 剖面，例 2-7）\n")
                append("【参数】本次演示 D0=80, c=1.5, HL=0.5, HH=2.0（例 2-7 经典参数，图 2-44）\n")
                append("【效果】消除光照不均、压缩动态范围的同时增强边缘细节。")
            }),
        HOMO_STRONG(
            "⑥ 同态滤波 强压缩照度",
            buildString {
                append("【原理】减小低频增益 HL（更强地压缩照度分量），")
                append("并提高高频增益 HH（更强地增强反射细节）。\n")
                append("【式 2-85】S(u,v) = H(u,v)·Z(u,v)；【式 2-87】g = e^s\n")
                append("【传递函数】H(u,v) = (HH − HL)·(1 − e^(−c·D²/D0²)) + HL\n")
                append("【参数】本次演示 D0=80, c=1.5, HL=0.2, HH=2.5\n")
                append("【效果】动态范围压缩更明显，整体更均匀；HL 过小会导致暗部细节丢失。")
            }),
        HOMO_D30(
            "⑦ 同态滤波 截止频率 D0=30",
            buildString {
                append("【原理】截止频率 D0 决定高低频分界：D0 越小，被增强的「高频」")
                append("范围越宽，更多中频成分被当作反射细节提升。\n")
                append("【式 2-85】S(u,v) = H(u,v)·Z(u,v)；【式 2-87】g = e^s\n")
                append("【传递函数】H(u,v) = (HH − HL)·(1 − e^(−c·D²/D0²)) + HL\n")
                append("【参数】本次演示 D0=30, c=1.5, HL=0.5, HH=2.0\n")
                append("【效果】细节增强作用范围大，纹理更突出，但可能出现过增强。")
            }),
        HOMO_D150(
            "⑧ 同态滤波 截止频率 D0=150",
            buildString {
                append("【原理】D0 越大，滤波器剖面过渡越靠外，只有非常高的频率才被增强，")
                append("处理以整体动态范围压缩为主。\n")
                append("【式 2-85】S(u,v) = H(u,v)·Z(u,v)；【式 2-87】g = e^s\n")
                append("【传递函数】H(u,v) = (HH − HL)·(1 − e^(−c·D²/D0²)) + HL\n")
                append("【参数】本次演示 D0=150, c=1.5, HL=0.5, HH=2.0\n")
                append("【效果】光照更均匀、画面更平，细节增强相对温和。")
            }),
        HOMO_MILD(
            "⑨ 同态滤波 温和增强",
            buildString {
                append("【原理】HL 接近 1、HH 适度（如 1.5），滤波器接近平缓的高频提升，")
                append("对原图改动较小。\n")
                append("【式 2-85】S(u,v) = H(u,v)·Z(u,v)；【式 2-87】g = e^s\n")
                append("【传递函数】H(u,v) = (HH − HL)·(1 − e^(−c·D²/D0²)) + HL\n")
                append("【参数】本次演示 D0=80, c=1.5, HL=0.8, HH=1.5\n")
                append("【效果】轻微压缩照度并提升细节，适合作为保守的同态增强方案。")
            }),
    }

    private val transforms: Array<Transform> = Transform.values()
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageHomomorphicLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Illum,
            binding.cb03Reflect,
            binding.cb04Log,
            binding.cb05HomoStd,
            binding.cb06HomoStrong,
            binding.cb07HomoD30,
            binding.cb08HomoD150,
            binding.cb09HomoMild,
        )
    }

    override fun initData() {
        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        applyTransform(Transform.ORIGINAL)
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE)
        }

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
                    if (checkBoxes.none { it.isChecked }) {
                        applyingChecked = true
                        view.isChecked = true
                        applyingChecked = false
                    }
                }
            }
        checkBoxes.forEach { it.setOnCheckedChangeListener(onChecked) }
    }

    /** 根据所选选项调用 JNI 并更新「结果图 + 原理公式」。 */
    private fun applyTransform(transform: Transform) {
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
                    Transform.ILLUM -> OpencvDealJni.homoIllumination(src)
                    Transform.REFLECT -> OpencvDealJni.homoReflectance(src)
                    Transform.LOG -> OpencvDealJni.homoLogDomain(src)
                    Transform.HOMO_STD -> OpencvDealJni.homoFilter(src, 80.0, 1.5, 0.5, 2.0)
                    Transform.HOMO_STRONG -> OpencvDealJni.homoFilter(src, 80.0, 1.5, 0.2, 2.5)
                    Transform.HOMO_D30 -> OpencvDealJni.homoFilter(src, 30.0, 1.5, 0.5, 2.0)
                    Transform.HOMO_D150 -> OpencvDealJni.homoFilter(src, 150.0, 1.5, 0.5, 2.0)
                    Transform.HOMO_MILD -> OpencvDealJni.homoFilter(src, 80.0, 1.5, 0.8, 1.5)
                }
            }.getOrElse { it.printStackTrace(); null } ?: src

            activity?.runOnUiThread {
                binding.ivResult.setImageBitmap(result)
            }
        }
    }

    /** 从 assets 加载图片并降采样（长边 ≤ [MAX_SIDE]）。 */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().assets.open(fileName).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SIDE) {
                sampleSize *= 2
            }
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
