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
import com.wangyao.opencvprocessing.databinding.FragmentImageRetinexLayoutBinding
import kotlin.concurrent.thread

/**
 * 基于 Retinex 理论的图像增强界面（三级页）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 2.6 节选项。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」说明（式 2-88 ~ 2-96）。
 *
 * 注：为兼顾大 σ 高斯环绕的耗时，加载时先降采样到长边 ≤1080，
 * 大 σ 环绕在 Native 层以降采样方式加速。
 */
class ImageRetinexFragment : BaseFragment() {

    private lateinit var binding: FragmentImageRetinexLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var applyingChecked = false

    /** 九种 Retinex 增强选项的元数据（对应 PDF 2.6 节）。 */
    private enum class Transform(
        val displayTitle: String,
        val formula: String,
    ) {
        ORIGINAL(
            "① 显示原始图像",
            buildString {
                append("【原理】不做任何处理，直接显示原始图像，用作对照基准。\n")
                append("【模型】Retinex（视网膜+大脑皮层）理论认为：人眼感知的图像 = 光照分量 × 反射分量")
                append("（式 2-88），物体表面的颜色取决于其反射特性而与光照无关。")
                append("据此通过对数域相减估计反射分量，可消除光照不均、")
                append("同时实现动态范围压缩与颜色恒常（颜色保真）两类增强。")
            }),
        ILLUM_L(
            "② 光照分量 L 估计 (式 2-92)",
            buildString {
                append("【原理】光照分量用高斯环绕函数与原图卷积估计（中心/环绕比），")
                append("得到缓慢变化的光滑光照场。\n")
                append("【式 2-88】f(x,y) = i(x,y)·r(x,y)（Retinex 成像模型）\n")
                append("【式 2-92】L(x,y) = I(x,y) * G(x,y)（高斯环绕卷积）\n")
                append("【参数】本次演示 σ=80（图 2-46 经典尺度）\n")
                append("【效果】显示阴影、亮度渐变等光照分布，光照不均正是图像偏暗区域质量差的根源。")
            }),
        REFLECT_R(
            "③ 反射分量 r 可视化 (式 2-90)",
            buildString {
                append("【原理】对数域中 ln f = ln i + ln r，光照取对数记为 l，")
                append("反射分量即 ln f − l，反映物体表面反射特性。\n")
                append("【式 2-89】ln f(x,y) = ln i(x,y) + ln r(x,y)\n")
                append("【式 2-90】l(x,y) = ln i(x,y)，r(x,y) = ln f − l(x,y)\n")
                append("【参数】本次演示 σ=80，灰度显示\n")
                append("【效果】亮处代表反射强（细节/边缘丰富区域）。")
            }),
        SSR15(
            "④ SSR σ=15 (式 2-91)",
            buildString {
                append("【原理】SSR（单尺度 Retinex）对 RGB 三通道分别做中心/环绕比的对数运算。\n")
                append("【式 2-91】ri(x,y) = ln Ii(x,y) − ln[Ii(x,y)*G(x,y)]，i = R,G,B\n")
                append("【式 2-92】G 为高斯环绕函数\n")
                append("【参数】本次演示 σ=15（小尺度）\n")
                append("【效果】小 σ：动态范围压缩能力强、细节突出，但颜色失真较明显（图 2-46）。")
            }),
        SSR80(
            "⑤ SSR σ=80 (图 2-46)",
            buildString {
                append("【原理】SSR 单尺度 Retinex，σ 的取值在两类增强效果间折中。\n")
                append("【式 2-91】ri(x,y) = ln Ii(x,y) − ln[Ii(x,y)*G(x,y)]\n")
                append("【式 2-92】L(x,y) = I(x,y) * G(x,y)\n")
                append("【参数】本次演示 σ=80（经典折中尺度，图 2-46）\n")
                append("【效果】兼顾一定的动态范围压缩与颜色保真，是最常用的单尺度参数。")
            }),
        SSR250(
            "⑥ SSR σ=250",
            buildString {
                append("【原理】大尺度高斯环绕接近全局平均，光照估计非常平滑。\n")
                append("【式 2-91】ri(x,y) = ln Ii(x,y) − ln[Ii(x,y)*G(x,y)]\n")
                append("【参数】本次演示 σ=250（大尺度）\n")
                append("【效果】大 σ：颜色保真度高、整体自然，但动态范围压缩不足（图 2-46），")
                append("暗区提亮效果有限。")
            }),
        MSR3(
            "⑦ MSR 三尺度 (式 2-93)",
            buildString {
                append("【原理】MSR（多尺度 Retinex）把多个 σ 尺度的 SSR 结果加权平均，")
                append("同时兼顾小尺度（细节）与大尺度（颜色保真）的优点。\n")
                append("【式 2-93】R_MSR_i = Σk ωk·{ln Ii − ln[Ii * Gk]}，k 为尺度序号\n")
                append("【式 2-94】ωk = 1/N（各尺度等权）\n")
                append("【参数】本次演示 σ = {15, 80, 250}，N=3\n")
                append("【效果】细节增强与颜色保真相对均衡，是工程上最常用的 Retinex 方案。")
            }),
        MSR5(
            "⑧ MSR 五尺度 (式 2-94)",
            buildString {
                append("【原理】增加更多尺度可以更充分地覆盖从局部细节到全局光照的频率范围。\n")
                append("【式 2-93】R_MSR_i = Σk ωk·{ln Ii − ln[Ii * Gk]}\n")
                append("【式 2-94】ωk = 1/N（本例 N=5，各尺度等权 1/5）\n")
                append("【参数】本次演示 σ = {15, 40, 80, 150, 250}\n")
                append("【效果】比三尺度更平滑稳健，计算量也相应增大。")
            }),
        MSRCR(
            "⑨ MSRCR 颜色恢复 (式 2-96)",
            buildString {
                append("【原理】MSR 各通道独立增强容易导致整体颜色偏离原色（偏灰/偏色），")
                append("MSRCR 引入颜色恢复因子 Ci 调节各通道的增强强度。\n")
                append("【式 2-95】Ci(x,y) = f[ Ii(x,y) / Σj Ij(x,y) ]，f 为变换函数")
                append("（通常取线性或对数函数，本实现取线性）\n")
                append("【式 2-96】R_MSRCR_i = Σk Ci·ωk·{ln Ii − ln[Ii * Gk]}\n")
                append("【参数】本次演示 σ = {15, 80, 250}\n")
                append("【效果】在 MSR 增强基础上改善色彩保真度，减轻颜色失真。")
            }),
    }

    private val transforms: Array<Transform> = Transform.values()
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageRetinexLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02IllumL,
            binding.cb03ReflectR,
            binding.cb04Ssr15,
            binding.cb05Ssr80,
            binding.cb06Ssr250,
            binding.cb07Msr3,
            binding.cb08Msr5,
            binding.cb09Msrcr,
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
                    Transform.ILLUM_L -> OpencvDealJni.retinexIllumination(src, 80.0)
                    Transform.REFLECT_R -> OpencvDealJni.retinexReflectance(src, 80.0)
                    Transform.SSR15 -> OpencvDealJni.retinexSSR(src, 15.0)
                    Transform.SSR80 -> OpencvDealJni.retinexSSR(src, 80.0)
                    Transform.SSR250 -> OpencvDealJni.retinexSSR(src, 250.0)
                    Transform.MSR3 -> OpencvDealJni.retinexMSR(src, doubleArrayOf(15.0, 80.0, 250.0))
                    Transform.MSR5 -> OpencvDealJni.retinexMSR(
                        src, doubleArrayOf(15.0, 40.0, 80.0, 150.0, 250.0)
                    )
                    Transform.MSRCR -> OpencvDealJni.retinexMSRCR(src, doubleArrayOf(15.0, 80.0, 250.0))
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
