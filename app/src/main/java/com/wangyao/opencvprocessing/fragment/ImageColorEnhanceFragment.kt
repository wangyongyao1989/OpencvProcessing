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
import com.wangyao.opencvprocessing.databinding.FragmentImageColorEnhanceLayoutBinding
import kotlin.concurrent.thread

/**
 * 彩色增强界面（三级页）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 2.7 节选项
 *   （伪彩色增强三种方法 + 假彩色增强四种映射）。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」说明（图 2-47~2-50、式 2-97/2-98）。
 *
 * 注：为兼顾频域 DFT 的耗时，加载时先降采样到长边 ≤1080。
 */
class ImageColorEnhanceFragment : BaseFragment() {

    private lateinit var binding: FragmentImageColorEnhanceLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var applyingChecked = false

    /** 九种彩色增强选项的元数据（对应 PDF 2.7 节）。 */
    private enum class Transform(
        val displayTitle: String,
        val formula: String,
    ) {
        ORIGINAL(
            "① 显示原始图像",
            buildString {
                append("【原理】不做任何处理，直接显示原始图像，用作对照基准。\n")
                append("【概念】彩色增强分为两类：\n")
                append("· 伪彩色增强（2.7.1）：把灰度图像按某种准则映射成彩色图像，")
                append("人眼能分辨的颜色数远多于灰度级，可提高细节可辨性；\n")
                append("· 假彩色增强（2.7.2）：彩色到彩色的映射，")
                append("将自然彩色或多光谱图像逐点映射到新的彩色空间。")
            }),
        SLICE2(
            "② 灰度分层法 两层切割 (图 2-47)",
            buildString {
                append("【原理】在灰度级 l1 处设平行于 xy 平面的切割平面，")
                append("把图像切成两个区域：低于 l1 的像素赋一种颜色（蓝），")
                append("高于 l1 的赋另一种颜色（红），得到两色伪彩色图像。\n")
                append("【图 2-47】灰度分层的切割示意图\n")
                append("【参数】本次演示 l1=128（灰度中值）\n")
                append("【效果】简单直观地分割亮暗区域。")
            }),
        SLICE_MULTI(
            "③ 灰度分层法 多平面切割 (图 2-48)",
            buildString {
                append("【原理】用 M 个切割平面把灰度范围切成 M+1 个区域 S1..S(M+1)，")
                append("人为给每个区域分配一种颜色，得到 M+1 色伪彩色图像。\n")
                append("【图 2-48】多灰度分层的切割示意图\n")
                append("【参数】本次演示 M=7（色相均布的 8 种光谱色）\n")
                append("【效果】优点：简单易行，便于软硬件实现，还可统计某灰度级面积；")
                append("缺点：伪彩色生硬不调和、颜色数目不多。")
            }),
        LEVEL_COLOR(
            "④ 灰度级彩色变换 (图 2-49)",
            buildString {
                append("【原理】根据三基色原理，把灰度 f 送入红/绿/蓝三个具有不同变换特性的")
                append("变换器，输出 IR/IG/IB 三个基色分量后合成彩色。")
                append("受调制的是像素灰度值而非位置，得到多种颜色渐变的连续彩色图像。\n")
                append("【图 2-49】灰度级彩色变换原理（红/绿/蓝变换器典型特性）\n")
                append("【本实现】相位错开 1/3 周期的三角波变换器：\n")
                append("IR = tri(f/255)，IG = tri(f/255 + 1/3)，IB = tri(f/255 + 2/3)，")
                append("tri(x) = 1 − |2·frac(x) − 1|\n")
                append("【效果】灰度被映射为平滑的彩虹渐变，细节层次以色相区分。")
            }),
        FREQ_COLOR(
            "⑤ 频率域滤波法伪彩色 (图 2-50)",
            buildString {
                append("【原理】伪彩色与灰度级无关而与空间频率成分有关：灰度图 DFT 后")
                append("用低通/带通/高通三个滤波器分离频谱，各自 IDFT 并做附加处理")
                append("（直方图均衡化），作为三基色显示。\n")
                append("【图 2-50】频率域滤波法实现伪彩色增强的原理框图\n")
                append("【本实现】R=高通（边缘→红）、G=带通、B=低通（背景→蓝）；")
                append("滤波器取高斯型（低通 D0=30、高通 D0=60）。\n")
                append("【效果】边缘呈现红色、缓变区域偏蓝，有利于边界的视觉检测。")
            }),
        FALSE_LINEAR(
            "⑥ 假彩色 线性映射 (式 2-97)",
            buildString {
                append("【原理】假彩色增强是彩色到彩色的映射，将自然彩色图像逐点映射到")
                append("三基色确定的色度空间，重新显示时目标颜色不同于自然本色。\n")
                append("【式 2-97】[gR, gG, gB]ᵀ = M·[fR, fG, fB]ᵀ（M 为 3×3 映射矩阵）\n")
                append("【本实现】通道轮换矩阵：gR = fB，gG = fR，gB = fG\n")
                append("【目的】经过假彩色变换比原来的自然色彩更引人注目（目的 1）。")
            }),
        FALSE_GREEN(
            "⑦ 假彩色 细节赋绿 (式 2-97)",
            buildString {
                append("【原理】根据人眼生理特点，把感兴趣而又不易分辨的细节赋予人眼")
                append("较敏感的颜色——人眼对绿色特别灵敏。\n")
                append("【式 2-97】[gR, gG, gB]ᵀ = M·[fR, fG, fB]ᵀ\n")
                append("【本实现】gG = f（灰度细节作为绿色通道），")
                append("gR = gB = 低通模糊背景\n")
                append("【效果】细节丰富的目标呈现绿色调，更易分辨（目的 2）。")
            }),
        FALSE_BLUE(
            "⑧ 假彩色 细节赋蓝 (式 2-97)",
            buildString {
                append("【原理】人眼对蓝色变化的对比灵敏度较高，")
                append("把细节较丰富的目标赋予深浅不一的蓝色可改善细节的可检测性。\n")
                append("【式 2-97】[gR, gG, gB]ᵀ = M·[fR, fG, fB]ᵀ\n")
                append("【本实现】gB = f（灰度细节作为蓝色通道），")
                append("gR = gG = 低通模糊背景\n")
                append("【效果】细节以蓝色深浅呈现，对比灵敏度演示（目的 2/3）。")
            }),
        FALSE_MULTI(
            "⑨ 多光谱合成假彩色 (式 2-98)",
            buildString {
                append("【原理】多光谱图像的假彩色增强用 n 个波段图像经变换函数")
                append("TR/TG/TB 合成 RGB 三基色，多波段综合可获得更多信息、")
                append("便于区分某些特征（目的 3）。\n")
                append("【式 2-98】gR = TR[f1..fn]，gG = TG[f1..fn]，gB = TB[f1..fn]\n")
                append("【本实现】把 RGB 三通道当作三个波段 f1/f2/f3，变换函数取波段差分：\n")
                append("gR = |f1 − f2|，gG = |f2 − f3|，gB = |f3 − f1|\n")
                append("【效果】光谱差异大的区域颜色鲜明，可突出特定材质/目标的边界。")
            }),
    }

    private val transforms: Array<Transform> = Transform.values()
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageColorEnhanceLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Slice2,
            binding.cb03SliceMulti,
            binding.cb04LevelColor,
            binding.cb05FreqColor,
            binding.cb06FalseLinear,
            binding.cb07FalseGreen,
            binding.cb08FalseBlue,
            binding.cb09FalseMulti,
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
                    Transform.SLICE2 -> OpencvDealJni.colorGraySlice2(src, 128)
                    Transform.SLICE_MULTI -> OpencvDealJni.colorGraySliceMulti(src, 7)
                    Transform.LEVEL_COLOR -> OpencvDealJni.colorGrayLevelTransform(src)
                    Transform.FREQ_COLOR -> OpencvDealJni.colorFrequencyPseudo(src)
                    Transform.FALSE_LINEAR -> OpencvDealJni.colorFalseLinear(src)
                    Transform.FALSE_GREEN -> OpencvDealJni.colorFalseGreen(src)
                    Transform.FALSE_BLUE -> OpencvDealJni.colorFalseBlue(src)
                    Transform.FALSE_MULTI -> OpencvDealJni.colorFalseMultiSpectral(src)
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
