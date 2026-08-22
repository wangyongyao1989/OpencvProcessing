package com.wangyao.opencvprocessing.fragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.wangyao.opencvdeal.jni.OpencvDealJni
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentMorphOpLayoutBinding
import kotlin.concurrent.thread

/**
 * 形态学图像处理通用三级页（《数字图像与视频处理》第 3 章）。
 *
 * 一个 Fragment 通过 [module] 配置驱动四个子页面：
 * - MORPH_BIN_BASIC    二值形态学基本运算（3.2 节：腐蚀/膨胀/开/闭/对偶性）
 * - MORPH_BIN_PROCESS  二值图像的形态学处理（3.3 节：边缘/填充/骨架/细化/去噪）
 * - MORPH_GRAY_BASIC   灰度形态学基本运算（3.4 节：灰度腐蚀/膨胀/开/闭）
 * - MORPH_GRAY_PROCESS 灰度图像的形态学处理（3.5 节：梯度/平滑/顶帽/底帽）
 *
 * 结构与图像增强族三级页一致：互斥单选 checkbar + 原图/结果对比 + 原理公式卡片。
 */
class MorphOpFragment : BaseFragment() {

    private lateinit var binding: FragmentMorphOpLayoutBinding
    private lateinit var ffViewModel: FFViewModel
    private lateinit var module: FFViewModel.FRAGMENT_STATUS

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 一个可选运算项：标题 + 原理公式 + 位图计算器（子线程执行） */
    private class Op(
        val title: String,
        val formula: String,
        val compute: (Bitmap) -> Bitmap?
    )

    /** 当前模块的运算目录：由 [buildCatalog] 生成，索引与勾选框一一对应 */
    private val catalog = ArrayList<Op>()

    /** 勾选框列表（布局固定 9 个，多余的隐藏） */
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMorphOpLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01, binding.cb02, binding.cb03,
            binding.cb04, binding.cb05, binding.cb06,
            binding.cb07, binding.cb08, binding.cb09,
        )
    }

    override fun initData() {
        module = FFViewModel.FRAGMENT_STATUS.valueOf(
            arguments?.getString(ARG_MODULE) ?: FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC.name
        )

        // 依模块装配目录：标题、勾选栏文案、公式
        buildCatalog(module)

        binding.tvPageTitle.text = pageTitle(module)
        catalog.forEachIndexed { idx, op ->
            val cb = checkBoxes.getOrNull(idx) ?: return@forEachIndexed
            cb.visibility = View.VISIBLE
            cb.text = op.title
            cb.isChecked = idx == 0
        }
        // 隐藏多余勾选框（目录不足 9 项时）
        for (idx in catalog.size until checkBoxes.size) {
            checkBoxes[idx].visibility = View.GONE
        }

        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        // 初始显示第一项
        applyOp(catalog.first())
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        // 返回二级菜单「形态学图像处理」
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MORPHOLOGY)
        }

        val onChecked =
            CompoundButton.OnCheckedChangeListener { view, isChecked ->
                if (applyingChecked) return@OnCheckedChangeListener
                val idx = checkBoxes.indexOfFirst { it === view }
                if (idx < 0 || idx >= catalog.size) return@OnCheckedChangeListener
                if (isChecked) {
                    applyingChecked = true
                    for ((i, cb) in checkBoxes.withIndex()) {
                        if (i != idx && i < catalog.size) cb.isChecked = false
                    }
                    applyingChecked = false
                    applyOp(catalog[idx])
                } else {
                    // 不允许全部取消：至少保留一项勾选
                    if (checkBoxes.none { it.isChecked && it.visibility == View.VISIBLE }) {
                        applyingChecked = true
                        view.isChecked = true
                        applyingChecked = false
                    }
                }
            }
        checkBoxes.forEach { it.setOnCheckedChangeListener(onChecked) }
    }

    // -------------------------------------------------------------------------
    // 运算目录：四个模块的配置中心（标题 / 公式 / JNI 调用）
    // -------------------------------------------------------------------------

    /** 目录装配辅助 */
    private fun addOp(title: String, formula: String, compute: (Bitmap) -> Bitmap?) {
        catalog += Op(title, formula, compute)
    }

    private fun pageTitle(module: FFViewModel.FRAGMENT_STATUS): String = when (module) {
        FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC -> "二值形态学基本运算（3.2 节）"
        FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS -> "二值图像的形态学处理（3.3 节）"
        FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC -> "灰度形态学基本运算（3.4 节）"
        else -> "灰度图像的形态学处理（3.5 节）"
    }

    private fun buildCatalog(module: FFViewModel.FRAGMENT_STATUS) {
        catalog.clear()
        when (module) {
            FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC -> buildBinBasic()
            FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS -> buildBinProcess()
            FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC -> buildGrayBasic()
            else -> buildGrayProcess()
        }
    }

    /** 3.2 二值形态学基本运算：式 3-9 ~ 3-18 */
    private fun buildBinBasic() {
        addOp("① 二值化基准", buildString {
            append("【原理】二值形态学运算的对象是集合。先把灰度图按 Otsu（大津法）自动求全局阈值分割为二值图像 A（前景白 255 / 背景黑 0），作为后续所有二值形态学运算的输入基准。\n")
            append("【实现】OpenCV cv::threshold(THRESH_BINARY | THRESH_OTSU)。\n")
            append("【说明】后面 ② ~ ⑥ 项均在同一张二值图上进行，结构元统一取 5×5 矩形。")
        }) { src -> OpencvDealJni.morphBinarize(src) }

        addOp("② 腐蚀 (式 3-9)", buildString {
            append("【原理】腐蚀表示用结构元 B 平移 z 后完全包含于集合 A 的所有平移点集合，可使图像边界向内部收缩、消除小的突出物。\n")
            append("【式 3-9】A ⊖ B = { z | (B)_z ⊆ A }\n")
            append("   即结构元平移 z 后仍完全落在目标区域内时，z 属于腐蚀结果。\n")
            append("【参数】结构元 5×5 矩形。\n")
            append("【效果】白色前景收缩，细小连接被断开，小于结构元的孤立白点被消除。")
        }) { src -> OpencvDealJni.morphBinErode(src, 5, 0) }

        addOp("③ 膨胀 (式 3-10, 3-11)", buildString {
            append("【原理】膨胀是腐蚀的对偶运算：结构元 B 平移 z 后与集合 A 至少有一个公共点的所有 z，可使边界向外扩张、弥合小的孔洞与裂缝。\n")
            append("【式 3-10】A ⊕ B = { z | [(B̂)_z ∩ A] ⊆ A }\n")
            append("【式 3-11】A ⊕ B = { z | (B̂)_z ∩ A ≠ ∅ }\n")
            append("   两式等价，B̂ 表示 B 的反射（对称核）。\n")
            append("【参数】结构元 5×5 矩形。\n")
            append("【效果】白色前景扩张，孔洞缩小，断开区域被连接。")
        }) { src -> OpencvDealJni.morphBinDilate(src, 5, 0) }

        addOp("④ 开运算 (式 3-15, 3-16)", buildString {
            append("【原理】开运算 = 先腐蚀后膨胀，能平滑目标轮廓、断开细窄连接、消除小的尖刺与孤立点，且整体尺寸基本不变。\n")
            append("【式 3-15】A ∘ B = (A ⊖ B) ⊕ B\n")
            append("【式 3-16】A ∘ B = ∪ { (B)_z | (B)_z ⊆ A }\n")
            append("   即 A 中所有能与结构元完全匹配的平移之并。\n")
            append("【参数】结构元 5×5 矩形。\n")
            append("【效果】细小白噪声被抹去，大目标形状保持。")
        }) { src -> OpencvDealJni.morphBinOpen(src, 5, 0) }

        addOp("⑤ 闭运算 (式 3-17)", buildString {
            append("【原理】闭运算 = 先膨胀后腐蚀，能弥合细窄的断裂与孔洞、平滑轮廓，且整体尺寸基本不变。\n")
            append("【式 3-17】A • B = (A ⊕ B) ⊖ B\n")
            append("【参数】结构元 5×5 矩形。\n")
            append("【效果】黑色小孔洞与细缝被填补，白色主体连通性增强。")
        }) { src -> OpencvDealJni.morphBinClose(src, 5, 0) }

        addOp("⑥ 对偶性验证 (式 3-13, 3-14)", buildString {
            append("【原理】腐蚀与膨胀互为对偶：目标集合的腐蚀等价于其补集（背景）的膨胀再取补，反之亦然。\n")
            append("【式 3-13】(A ⊖ B)^c = A^c ⊕ B̂\n")
            append("【式 3-14】(A ⊕ B)^c = A^c ⊖ B̂\n")
            append("【验证】本项把输入反相（前景/背景互换）后做腐蚀，再与直接膨胀的补集对比，两者应一致。\n")
            append("【说明】本演示输出为反相-腐蚀结果，可与 ③ 膨胀图对照观察对偶关系。")
        }) { src -> OpencvDealJni.morphBinDuality(src, 5) }
    }

    /** 3.3 二值图像的形态学处理：式 3-20 ~ 3-35 */
    private fun buildBinProcess() {
        addOp("① 二值化基准", buildString {
            append("【原理】与 3.2 节相同的 Otsu 二值化结果，作为本页 ② ~ ⑧ 各项形态学处理的输入基准。\n")
            append("【实现】OpenCV cv::threshold(THRESH_BINARY | THRESH_OTSU)。")
        }) { src -> OpencvDealJni.morphBinarize(src) }

        addOp("② 内边缘提取 (式 3-20, 3-21)", buildString {
            append("【原理】内边缘 = 原集合减去其腐蚀，得到目标内侧的一圈边界像素。\n")
            append("【式 3-20】β(A) = A − (A ⊖ B)\n")
            append("【式 3-21】β(A) = A − ∪ { (B)_z | (B)_z ⊆ A }\n")
            append("【参数】结构元 3×3 矩形（边界宽度 1 像素）。\n")
            append("【效果】白色目标内部边缘轮廓。")
        }) { src -> OpencvDealJni.morphBinInnerEdge(src, 3) }

        addOp("③ 外边缘提取 (式 3-22)", buildString {
            append("【原理】外边缘 = 目标膨胀后减去原集合，得到目标外侧的一圈边界像素。\n")
            append("【式 3-22】β_ext(A) = (A ⊕ B) − A\n")
            append("【参数】结构元 3×3 矩形。\n")
            append("【效果】白色目标外部边缘轮廓，与 ② 内边缘互补。")
        }) { src -> OpencvDealJni.morphBinOuterEdge(src, 3) }

        addOp("④ 形态学梯度边缘", buildString {
            append("【原理】形态学梯度 = 膨胀结果减去腐蚀结果，同时覆盖目标的内外边界，边缘更粗、更完整，是灰度形态学梯度（式 3-46）的二值特例。\n")
            append("【公式】Grad(A) = (A ⊕ B) − (A ⊖ B)\n")
            append("【参数】结构元 3×3 矩形。\n")
            append("【效果】完整的双边缘轮廓带。")
        }) { src -> OpencvDealJni.morphBinGradEdge(src, 3) }

        addOp("⑤ 区域填充 (式 3-23)", buildString {
            append("【原理】从孔洞内某点出发，用结构元反复膨胀并与 A 的补集求交，直至收敛，即可填满 A 内部的所有孔洞。\n")
            append("【式 3-23】X_k = (X_{k−1} ⊕ B) ∩ A^c,  k = 1, 2, …\n")
            append("   收敛时 X_k = X_{k−1}，填充结果 = A ∪ X_k。\n")
            append("【实现】从图像四周边界种子（外部背景）洪泛求补集再反演，等价于式 3-23 迭代的工程实现。\n")
            append("【效果】白色目标内部的黑色孔洞被完全填补。")
        }) { src -> OpencvDealJni.morphBinFillHoles(src, 3) }

        addOp("⑥ 骨架提取 (式 3-24 ~ 3-28)", buildString {
            append("【原理】骨架是目标的最小中心线表示。反复腐蚀并求每次腐蚀与其开运算之差，把各次差并起来即得骨架。\n")
            append("【式 3-24】S(A) = ∪_{n=0}^{N} S_n(A)\n")
            append("【式 3-25】S_n(A) = (A ⊖ nB) − [(A ⊖ nB) ∘ B]\n")
            append("【式 3-26】重构：A = ∪_{n=0}^{N} (S_n(A) ⊕ nB)\n")
            append("【式 3-27】(A ⊖ nB) 表示结构元 B 连续腐蚀 n 次。\n")
            append("【式 3-28】N 为 A 被腐蚀成空集前的最大次数。\n")
            append("【效果】白色目标被压缩为单像素中心骨架线。")
        }) { src -> OpencvDealJni.morphBinSkeleton(src, 3) }

        addOp("⑦ 细化 (式 3-29 ~ 3-31)", buildString {
            append("【原理】细化是求骨架的一种序贯算法：用结构元序列逐轮击中-击不中变换剥离边界像素，直到结果不再变化。\n")
            append("【式 3-29】A ⊗ B = A − (A * B)\n")
            append("   其中 * 为击中-击不中变换（Hit-or-Miss）。\n")
            append("【式 3-30】A ⊗ {B} = ((…((A ⊗ B_1) ⊗ B_2)…) ⊗ B_n)\n")
            append("   按顺序使用结构元序列 {B_1…B_n} 反复迭代。\n")
            append("【式 3-31】细化结果满足：A ⊗ {B} 再细化一次不变（收敛）。\n")
            append("【实现】手写 Zhang-Suen 序贯细化（两遍扫描删除边界点，对应式 3-30 旋转结构元序列 B1…B8）。\n")
            append("【效果】目标收缩为细线但不断裂，保留拓扑连接。")
        }) { src -> OpencvDealJni.morphBinThinning(src) }

        addOp("⑧ 开闭滤波去噪 (式 3-34, 3-35)", buildString {
            append("【原理】开运算滤除白色（椒）噪声、闭运算滤除黑色（盐）噪声，二者串联即形态学滤波器。\n")
            append("【式 3-34】先开后闭：(A ∘ B) • B\n")
            append("【式 3-35】先闭后开：(A • B) ∘ B\n")
            append("   本实现按式 3-34 执行。\n")
            append("【参数】结构元 5×5 矩形。\n")
            append("【效果】二值图上的孤立噪点被滤除，主体形状保持不变。")
        }) { src -> OpencvDealJni.morphBinOpenCloseFilter(src, 5) }
    }

    /** 3.4 灰度形态学基本运算：式 3-36 ~ 3-45 */
    private fun buildGrayBasic() {
        addOp("① 原图基准", buildString {
            append("【原理】不做任何处理，直接显示原始灰度图像，作为 ② ~ ⑤ 灰度形态学基本运算的对照基准。\n")
            append("【说明】灰度形态学不再把图像看作集合，而是看作二维灰度函数 f(x,y)，结构元 b(x,y) 是一个小的子图像函数，运算在像素邻域内取最大/最小值。")
        }) { src -> src }

        addOp("② 灰度腐蚀 (式 3-36, 3-37)", buildString {
            append("【原理】灰度腐蚀 = 结构元定义的邻域内取最小值，输出图像变暗，尺寸小于结构元的亮细节（高灰度峰值）被消除。\n")
            append("【式 3-36】(f ⊖ b)(x, y) = min { f(x + x′, y + y′) − b(x′, y′) }\n")
            append("        (x′, y′) ∈ D_b（结构元定义域），平移后完全含于 f 的定义域。\n")
            append("【式 3-37】平坦结构元（b = 0）时简化为：(f ⊖ b)(x, y) = min_{(x′,y′)∈D_b} f(x + x′, y + y′)\n")
            append("【参数】结构元 5×5 椭圆（平坦）。\n")
            append("【效果】整体变暗，亮点被抹平。")
        }) { src -> OpencvDealJni.morphGrayErode(src, 5, 1) }

        addOp("③ 灰度膨胀 (式 3-38, 3-39)", buildString {
            append("【原理】灰度膨胀 = 邻域内取最大值，输出图像变亮，暗细节（低灰度谷底）被填平。\n")
            append("【式 3-38】(f ⊕ b)(x, y) = max { f(x − x′, y − y′) + b(x′, y′) }\n")
            append("        (x′, y′) ∈ D_b。\n")
            append("【式 3-39】平坦结构元时简化为：(f ⊕ b)(x, y) = max_{(x′,y′)∈D_b} f(x − x′, y − y′)\n")
            append("【参数】结构元 5×5 椭圆（平坦）。\n")
            append("【效果】整体变亮，暗坑被填平，与 ② 腐蚀互为对偶。")
        }) { src -> OpencvDealJni.morphGrayDilate(src, 5, 1) }

        addOp("④ 灰度开运算 (式 3-42, 3-43)", buildString {
            append("【原理】灰度开 = 先腐蚀后膨胀，可去除小于结构元的亮细节（亮点、尖峰、亮噪声），保留整体灰度轮廓。\n")
            append("【式 3-42】f ∘ b = (f ⊖ b) ⊕ b\n")
            append("【式 3-43】几何解释：把结构元“从下方向上推”顶住 f 表面，其中心点轨迹的上包络即为开结果。\n")
            append("【参数】结构元 5×5 椭圆（平坦）。\n")
            append("【效果】亮的细小突起与噪声被滤除，暗的结构不受影响。")
        }) { src -> OpencvDealJni.morphGrayOpen(src, 5, 1) }

        addOp("⑤ 灰度闭运算 (式 3-44, 3-45)", buildString {
            append("【原理】灰度闭 = 先膨胀后腐蚀，可去除小于结构元的暗细节（暗点、沟谷、暗噪声），保留整体灰度轮廓。\n")
            append("【式 3-44】f • b = (f ⊕ b) ⊖ b\n")
            append("【式 3-45】几何解释：把结构元“从上方向下压”贴住 f 表面，其中心点轨迹的下包络即为闭结果。\n")
            append("【参数】结构元 5×5 椭圆（平坦）。\n")
            append("【效果】暗的细小沟壑与噪声被填平，亮的结构不受影响，与 ④ 开运算对偶。")
        }) { src -> OpencvDealJni.morphGrayClose(src, 5, 1) }
    }

    /** 3.5 灰度图像的形态学处理：式 3-46 ~ 3-49 及扩展 */
    private fun buildGrayProcess() {
        addOp("① 原图基准", buildString {
            append("【原理】不做任何处理，直接显示原始灰度图像，作为本页 ② ~ ⑦ 灰度形态学处理算法的对照基准。\n")
            append("【说明】以下各算法均在灰度图上以开/闭/膨胀/腐蚀为基元组合而成。")
        }) { src -> src }

        addOp("② 形态学梯度 (式 3-46)", buildString {
            append("【原理】膨胀减腐蚀得到形态学梯度，灰度突变处（边缘）响应最大，是对图像边缘检测的形态学方法。\n")
            append("【式 3-46】Grad(f) = (f ⊕ b) − (f ⊖ b)\n")
            append("【参数】结构元 3×3 椭圆（小核边缘更细）。\n")
            append("【效果】图像边缘被突出显示，平坦区域输出为零。")
        }) { src -> OpencvDealJni.morphGrayGradient(src, 3, 1) }

        addOp("③ 开-闭形态学平滑 (式 3-47, 3-48)", buildString {
            append("【原理】开、闭运算交替串联可以同时滤除正、负脉冲噪声，实现形态学平滑滤波。\n")
            append("【式 3-47】O C(f) = (f ∘ b) • b   （先开后闭）\n")
            append("【式 3-48】C O(f) = (f • b) ∘ b   （先闭后开，见第 ④ 项）\n")
            append("   本项按式 3-47 执行。\n")
            append("【参数】结构元 5×5 椭圆。\n")
            append("【效果】图像更平滑，孤立亮/暗噪点同时被抑制。")
        }) { src -> OpencvDealJni.morphGrayOpenClose(src, 5, 1) }

        addOp("④ 闭-开形态学平滑 (式 3-48)", buildString {
            append("【原理】先闭后开，与 ③ 互为镜像顺序；对暗噪声为主的图像效果更好，二者组合可进一步减少偏置。\n")
            append("【式 3-48】C O(f) = (f • b) ∘ b\n")
            append("【参数】结构元 5×5 椭圆。\n")
            append("【效果】平滑效果与 ③ 相近，边缘保持略有差异，可对照观察。")
        }) { src -> OpencvDealJni.morphGrayCloseOpen(src, 5, 1) }

        addOp("⑤ 顶帽变换 (式 3-49)", buildString {
            append("【原理】顶帽 = 原图减去其开运算，用于提取小于结构元、比周围亮的细节（亮点、小峰），也能校正不均匀光照。\n")
            append("【式 3-49】TopHat(f) = f − (f ∘ b)\n")
            append("【参数】结构元 15×15 椭圆（大核提取亮细节更充分）。\n")
            append("【效果】明亮的小目标与细节被分离出来，背景趋于零。")
        }) { src -> OpencvDealJni.morphGrayTopHat(src, 15, 1) }

        addOp("⑥ 底帽变换", buildString {
            append("【原理】底帽 = 闭运算减去原图，用于提取小于结构元、比周围暗的细节（暗点、小谷），是顶帽的对偶变换。\n")
            append("【公式】BotHat(f) = (f • b) − f\n")
            append("【参数】结构元 15×15 椭圆。\n")
            append("【效果】暗的小目标与细节被分离出来，背景趋于零。")
        }) { src -> OpencvDealJni.morphGrayBottomHat(src, 15, 1) }

        addOp("⑦ 顶帽增强", buildString {
            append("【原理】顶帽提取亮细节、底帽提取暗细节，把二者从原图中加减可同时增强正负两种微弱细节，最后归一化到全灰度范围，是顶帽/底帽联合对比度增强的典型工程应用（如阴影/低对比度区域增强）。\n")
            append("【公式】Enhance(f) = stretch[ f + TopHat(f) − BotHat(f) ]\n")
            append("        其中 TopHat 见式 3-49，BotHat(f) = (f • b) − f，\n")
            append("        stretch 为 [min, max] → [0, 255] 归一化拉伸。\n")
            append("【参数】结构元 15×15 椭圆。\n")
            append("【效果】亮暗细节同时被增强，整体对比度明显提升，可与 ⑤ 顶帽结果对照。")
        }) { src -> OpencvDealJni.morphGrayTopHatEnhance(src, 15, 1) }
    }

    // -------------------------------------------------------------------------
    // 执行与 UI 渲染
    // -------------------------------------------------------------------------

    /**
     * 应用被选中的运算项：先刷新公式文本，再在后台线程执行 JNI 运算，
     * 完成后回到主线程更新结果图。
     */
    private fun applyOp(op: Op) {
        binding.tvFormula.text = op.formula
        binding.tvResultTitle.text = op.title
        val src = originalBitmap ?: return

        thread(start = true) {
            val result: Bitmap? = runCatching { op.compute(src) }
                .getOrElse { it.printStackTrace(); null } ?: src

            activity?.runOnUiThread {
                binding.ivResult.setImageBitmap(result)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    /**
     * 从 assets 加载位图并统一为 ARGB_8888，供 OpenCV JNI 层稳定处理。
     */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            val input = requireContext().assets.open(fileName)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeStream(input, null, opts)
            input.close()
            if (bmp != null && bmp.config != Bitmap.Config.ARGB_8888) {
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            } else bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        private const val ARG_MODULE = "arg_module"
        private const val IMG_ASSET_NAME = "IMG_20260821_190758.jpg"

        /** 按模块状态实例化三级页。 */
        fun instantiate(module: FFViewModel.FRAGMENT_STATUS): MorphOpFragment {
            return MorphOpFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODULE, module.name)
                }
            }
        }
    }
}
