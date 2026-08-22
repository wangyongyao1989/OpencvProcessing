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
 * 图像分割通用三级页（《数字图像与视频处理》第 4 章）。
 *
 * 一个 Fragment 通过 [module] 配置驱动四个子页面：
 * - SEG_THRESHOLD 基于灰度阈值化的图像分割（4.2 节：固定/迭代/Otsu/自适应/多级）
 * - SEG_EDGE      基于边缘检测的图像分割（4.3 节：Roberts~Canny/轮廓跟踪）
 * - SEG_REGION    基于区域的图像分割（4.4 节：区域生长/分裂合并/连通/分水岭）
 * - SEG_CONTOUR   基于主动轮廓模型的图像分割（4.5 节：Snake/气球力/测地线/Chan-Vese）
 *
 * 复用形态学三级页布局 fragment_morph_op_layout.xml：互斥单选 checkbar +
 * 原图/结果对比 + 原理公式卡片。
 */
class SegOpFragment : BaseFragment() {

    private lateinit var binding: FragmentMorphOpLayoutBinding
    private lateinit var ffViewModel: FFViewModel
    private lateinit var module: FFViewModel.FRAGMENT_STATUS

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 一个可选分割算法项：标题 + 原理公式 + 位图计算器（子线程执行） */
    private class Op(
        val title: String,
        val formula: String,
        val compute: (Bitmap) -> Bitmap?
    )

    /** 当前模块的算法目录：由 [buildCatalog] 生成，索引与勾选框一一对应 */
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
            arguments?.getString(ARG_MODULE) ?: FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD.name
        )

        // 依模块装配目录：标题、勾选栏文案、公式
        buildCatalog(module)

        binding.tvPageTitle.text = pageTitle(module)
        binding.tvCheckbarHint.text = "请选择一种分割算法（一次仅选中一项）："
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
        // 返回二级菜单「图像分割」
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.SEGMENTATION)
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
    // 算法目录：四个模块的配置中心（标题 / 公式 / JNI 调用）
    // -------------------------------------------------------------------------

    /** 目录装配辅助 */
    private fun addOp(title: String, formula: String, compute: (Bitmap) -> Bitmap?) {
        catalog += Op(title, formula, compute)
    }

    private fun pageTitle(module: FFViewModel.FRAGMENT_STATUS): String = when (module) {
        FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD -> "基于灰度阈值化的图像分割（4.2 节）"
        FFViewModel.FRAGMENT_STATUS.SEG_EDGE -> "基于边缘检测的图像分割（4.3 节）"
        FFViewModel.FRAGMENT_STATUS.SEG_REGION -> "基于区域的图像分割（4.4 节）"
        else -> "基于主动轮廓模型的图像分割（4.5 节）"
    }

    private fun buildCatalog(module: FFViewModel.FRAGMENT_STATUS) {
        catalog.clear()
        when (module) {
            FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD -> buildThreshold()
            FFViewModel.FRAGMENT_STATUS.SEG_EDGE -> buildEdge()
            FFViewModel.FRAGMENT_STATUS.SEG_REGION -> buildRegion()
            else -> buildContour()
        }
    }

    /** 4.2 基于灰度阈值化的图像分割：式 4-1 ~ 4-10 */
    private fun buildThreshold() {
        addOp("① 原图基准", buildString {
            append("【原理】不做任何处理，直接显示原始图像，作为本页 ② ~ ⑥ 各阈值分割算法的对照基准。\n")
            append("【说明】灰度阈值化是最简单的图像分割方法：按灰度级把像素划分为前景/背景两类，")
            append("关键在于选取合适的阈值 T。")
        }) { src -> src }

        addOp("② 固定阈值 (式 4-1)", buildString {
            append("【原理】按人工指定的单一阈值 T 把图像分为两部分：灰度大于 T 的为一类，其余为另一类。\n")
            append("【式 4-1】g(x,y) = 1, f(x,y) > T；g(x,y) = 0, f(x,y) ≤ T\n")
            append("【参数】T = 128（演示固定值）。\n")
            append("【特点】简单快速，但依赖人工经验，光照变化时效果不稳定。")
        }) { src -> OpencvDealJni.segFixedThreshold(src, 128.0) }

        addOp("③ 迭代阈值 (式 4-3)", buildString {
            append("【原理】从初始阈值出发，反复按两类均值更新阈值，直至收敛，得到近似最优的全局阈值。\n")
            append("【式 4-3】T_{k+1} = (μ1 + μ2) / 2\n")
            append("        μ1、μ2 分别为按 T_k 划分后的两类像素灰度均值；|T_{k+1} − T_k| 足够小时停止。\n")
            append("【实现】初始 T₀ 取全局均值，迭代至阈值变化小于 0.5。\n")
            append("【特点】自动求阈值，对双峰直方图图像效果接近最优。")
        }) { src -> OpencvDealJni.segIterativeThreshold(src) }

        addOp("④ Otsu (式 4-4~4-10)", buildString {
            append("【原理】大津法：遍历所有可能阈值，使前景/背景两类之间的类间方差 σB² 最大的阈值即为最优分割阈值。\n")
            append("【式 4-4】σB²(t) = ω1(t)·ω2(t)·[μ1(t) − μ2(t)]²\n")
            append("【式 4-5/4-6】ω1、ω2：两类像素出现的概率（频率）。\n")
            append("【式 4-7/4-8】μ1、μ2：两类的平均灰度。\n")
            append("【式 4-9/4-10】T* = arg max σB²(t)，等价于类内方差最小准则。\n")
            append("【实现】OpenCV cv::threshold(THRESH_OTSU) 全局自动寻优。\n")
            append("【特点】最常用的自动阈值法，目标与背景灰度差异明显时分割稳定。")
        }) { src -> OpencvDealJni.segOtsu(src) }

        addOp("⑤ 自适应阈值 (式 4-2)", buildString {
            append("【原理】当光照不均匀时，全局阈值无法兼顾亮区与暗区。把式 4-2 的阈值 T 局部化：每个像素的阈值由其邻域窗口内像素的均值（或加权和）动态确定。\n")
            append("【式 4-2 局部化】T(x,y) = 邻域均值(x,y) − C\n")
            append("【参数】邻域窗口 31×31，修正常数 C = 10。\n")
            append("【实现】OpenCV cv::adaptiveThreshold(ADAPTIVE_THRESH_MEAN_C)。\n")
            append("【特点】能适应光照渐变，适合阴影、不均匀照明场景的二值化。")
        }) { src -> OpencvDealJni.segAdaptiveThreshold(src, 31, 10.0) }

        addOp("⑥ 多级阈值", buildString {
            append("【原理】式 4-1 的推广：用多个阈值 T1 < T2 < … 把灰度轴切成多个区间，每个区间作为一类区域，适合目标和背景灰度层次较多的图像。\n")
            append("【公式】g(x,y) = k, 若 T_{k−1} < f(x,y) ≤ T_k（k = 1…M）\n")
            append("【实现】按直方图多峰自动选取多个阈值，各区间映射为不同灰度级。\n")
            append("【特点】一次分割得到多个区域，是对单阈值二值分割的直接扩展。")
        }) { src -> OpencvDealJni.segMultiLevelThreshold(src) }

        addOp("⑦ 叠加可视化", buildString {
            append("【原理】将 Otsu 分割掩膜与原图叠加：前景保持原灰度、背景压暗并叠加红色高亮，直观展示阈值分割提取的目标区域在原图中的位置。\n")
            append("【说明】分割结果的工程可视化方式，便于检验阈值选取是否准确。\n")
            append("【效果】被分割出的前景区域以红色标记叠加显示。")
        }) { src -> OpencvDealJni.segThresholdOverlay(src) }
    }

    /** 4.3 基于边缘检测的图像分割：式 4-14 ~ 4-39 */
    private fun buildEdge() {
        addOp("① 原图基准", buildString {
            append("【原理】不做任何处理，直接显示原始图像，作为本页 ② ~ ⑦ 各边缘检测分割算法的对照基准。\n")
            append("【说明】边缘是灰度发生剧烈突变的位置，基于边缘的分割先检测边缘再将其连接成闭合边界，从而把图像划分为区域。")
        }) { src -> src }

        addOp("② Roberts (式 4-18, 4-19)", buildString {
            append("【原理】Roberts 交叉差分算子：在 2×2 邻域沿对角方向差分，模板小、计算快，边缘定位精度高但对噪声敏感。\n")
            append("【式 4-18】G[f(x,y)] ≈ |Gx| + |Gy|（式 4-14 梯度幅值近似）\n")
            append("【式 4-19】Hx = [−1 0; 0 1]，Hy = [0 −1; 1 0]\n")
            append("【效果】得到细而亮的对角方向边缘。")
        }) { src -> OpencvDealJni.segRoberts(src) }

        addOp("③ Sobel (式 4-20)", buildString {
            append("【原理】Sobel 算子在差分模板中引入平均因素：先邻域加权平均再差分，在锐化边缘的同时抑制噪声。\n")
            append("【式 4-20】Hx = [−1 0 1; −2 0 2; −1 0 1]，Hy 为其转置\n")
            append("【幅值】G ≈ |Gx| + |Gy|\n")
            append("【参数】核尺寸 3×3。\n")
            append("【效果】边缘较粗较亮，抗噪性好于 Roberts。")
        }) { src -> OpencvDealJni.segSobel(src, 3) }

        addOp("④ Prewitt (式 4-21, 4-22)", buildString {
            append("【原理】Prewitt 算子与 Sobel 思路相同，但平均权重均取 1，是纯粹的均值差分模板。\n")
            append("【式 4-21】Hx = [−1 0 1; −1 0 1; −1 0 1]，Hy 为其转置\n")
            append("【式 4-22】G ≈ |Gx| + |Gy|\n")
            append("【效果】边缘平滑，对噪声有一定抑制，但边缘略粗于 Sobel。")
        }) { src -> OpencvDealJni.segPrewitt(src) }

        addOp("⑤ Laplacian (式 4-26~4-28)", buildString {
            append("【原理】拉普拉斯是二阶导数各向同性算子，在边缘处出现过零点，对灰度突变极敏感，同时对噪声也敏感。\n")
            append("【式 4-26】∇²f = f(i+1,j) + f(i−1,j) + f(i,j+1) + f(i,j−1) − 4f(i,j)\n")
            append("【式 4-27/4-28】4 邻域/8 邻域离散模板。\n")
            append("【效果】双边缘响应，孤立噪点被强烈放大。")
        }) { src -> OpencvDealJni.segLaplacianEdge(src) }

        addOp("⑥ LoG (式 4-29~4-33)", buildString {
            append("【原理】Marr-Hildreth 算子：先高斯平滑去噪再求拉普拉斯，克服拉普拉斯对噪声敏感的缺点，通过检测二阶导数过零点得到边缘。\n")
            append("【式 4-29】∇²G(x,y)（LoG 核，墨西哥草帽形）\n")
            append("【式 4-30】g = f * Gσ（高斯平滑）\n")
            append("【式 4-31】∇²(f * G) = f * ∇²G（卷积与微分可交换次序）\n")
            append("【式 4-32/4-33】LoG 离散模板与过零点判据。\n")
            append("【参数】核尺寸 5×5，σ = 1.5。\n")
            append("【效果】边缘闭合性好，抗噪能力强。")
        }) { src -> OpencvDealJni.segLoG(src, 5, 1.5) }

        addOp("⑦ Canny (式 4-34~4-39)", buildString {
            append("【原理】Canny 是最优边缘检测算子，完整流程包含四步：\n")
            append("【式 4-34/4-35】① 高斯平滑去噪；② Sobel 计算梯度幅值与方向；\n")
            append("【式 4-36/4-37】③ 非极大值抑制：沿梯度方向只保留局部幅值最大的点，把粗边缘细化为单像素；\n")
            append("【式 4-38/4-39】④ 双阈值检测与滞后连接：高于高阈值 T2 为强边缘、介于 T1~T2 为弱边缘，")
            append("仅当弱边缘与强边缘连通时才保留。\n")
            append("【参数】T1 = 50，T2 = 150。\n")
            append("【效果】边缘连续、细锐、噪声极少的最佳边缘图。")
        }) { src -> OpencvDealJni.segCanny(src, 50.0, 150.0) }

        addOp("⑧ 边缘跟踪分割", buildString {
            append("【原理】边缘检测得到的是离散边缘点，需经边缘闭合与轮廓跟踪才能形成区域边界完成分割：先对 Canny 边缘做形态学膨胀-腐蚀闭运算弥合断裂，再提取外轮廓并填充为区域。\n")
            append("【流程】Canny 边缘 → 形态学闭合 → 轮廓提取(cv::findContours) → 轮廓填充区域化\n")
            append("【效果】输出闭合边界围成的分割区域图，实现「由边缘到区域」的完整分割链路。")
        }) { src -> OpencvDealJni.segContourTrace(src) }
    }

    /** 4.4 基于区域的图像分割：4.4.1 ~ 4.4.3 */
    private fun buildRegion() {
        addOp("① 区域生长(中心种子)", buildString {
            append("【原理】区域生长（4.4.1）：从种子点出发，将与种子性质（灰度）相似且空间相邻的像素不断并入区域，直至没有满足条件的像素，生成连通分割区域。\n")
            append("【公式】R = ∪ R_k，R_k 满足：P(R_k) = TRUE 且相邻区域 P(R_i ∪ R_j) = FALSE\n")
            append("        P 为灰度相似性准则：|f(p) − f(seed)| ≤ T。\n")
            append("【参数】种子取图像中心点，相似性阈值 T = 16。\n")
            append("【效果】从中心生长出的单一连通区域（白色），红点为种子位置。")
        }) { src -> OpencvDealJni.segRegionGrowCenter(src, 16) }

        addOp("② 区域生长(自动选种)", buildString {
            append("【原理】种子选取原则（4.4.1）：种子应位于区域内部稳定处。本项按直方图峰值自动选种——灰度分布的各个峰代表不同区域的典型灰度，取峰值点为种子并行生长，实现多区域自动分割。\n")
            append("【参数】相似性阈值 T = 16，种子数由直方图峰数自动确定。\n")
            append("【效果】多个不同灰度的连通区域被同时生长并伪彩色标记。")
        }) { src -> OpencvDealJni.segRegionGrowAutoSeed(src, 16) }

        addOp("③ 区域分裂-合并", buildString {
            append("【原理】区域分裂-合并（4.4.2）：与生长相反的自顶向下策略。先把图像不断四分为子块（四叉树），不满足均匀性准则的块继续分裂；再对相邻且满足合并准则的子块合并，最终得到分割结果。\n")
            append("【准则】分裂：块内灰度方差 > 阈值则四分裂；合并：相邻块灰度均值差 < 阈值则合并。\n")
            append("【效果】输出四叉树分块区域图，块边界即分割边界，兼顾全局与局部特性。")
        }) { src -> OpencvDealJni.segSplitMerge(src) }

        addOp("④ 连通分量标记", buildString {
            append("【原理】连通分量标记：对二值化后的图像，把 4/8 连通的白色像素归为同一区域并赋予统一标号，每个标号区域即一个独立分割目标。\n")
            append("【公式】R_i ∩ R_j = ∅ (i≠j)，∪ R_i = 前景集合\n")
            append("【实现】Otsu 二值化 → cv::connectedComponents 标记 → 按标号伪彩色着色。\n")
            append("【效果】每个连通目标显示为不同颜色，可统计目标个数与面积。")
        }) { src -> OpencvDealJni.segConnectedComponents(src) }

        addOp("⑤ 分水岭变换", buildString {
            append("【原理】分水岭算法（4.4.3）：把灰度图看作地形表面，灰度为海拔。从标记点「注水」，水在低洼处汇聚并在汇合处筑坝，堤坝即为分割边界，解决目标粘连的区域分割问题。\n")
            append("【流程】标记提取(距离变换+阈值) → cv::watershed 浸水演化 → 边界堤坝提取\n")
            append("【效果】粘连目标被堤坝分开，输出区域着色 + 白色分割边界的分水岭结果图。")
        }) { src -> OpencvDealJni.segWatershed(src) }
    }

    /** 4.5 基于主动轮廓模型的图像分割：式 4-40 ~ 4-47 */
    private fun buildContour() {
        addOp("① 基本 Snake (式 4-40~4-45)", buildString {
            append("【原理】主动轮廓模型（Snake，式 4-40）：在目标附近初始化一条参数曲线 v(s)，")
            append("在内部能量（保持曲线平滑）与图像能量（吸引曲线趋向边缘）共同作用下变形，")
            append("能量最小时曲线停在目标边界处，完成分割。\n")
            append("【式 4-40】E = ∫ [ α|v′(s)|² + β|v″(s)|² ] ds + E_img(v(s))\n")
            append("【式 4-41】第一、二项为内部能量：α 控制弹性（抗拉伸）、β 控制刚性（抗弯曲）。\n")
            append("【式 4-42~4-43】离散化：曲线上 N 个点，能量求和形式。\n")
            append("【式 4-44】E_img = −|∇I|：图像梯度大的点能量低，吸引轮廓。\n")
            append("【式 4-45】∇I 经高斯平滑，扩大边缘吸引范围。\n")
            append("【实现】Williams-Shah 贪心算法逐点在邻域窗口内寻能量最小候选点。\n")
            append("【效果】初始圆形轮廓收敛吸附到目标边缘附近。")
        }) { src -> OpencvDealJni.segSnake(src, 300) }

        addOp("② 内部能量对比 (式 4-41)", buildString {
            append("【原理】内部能量（式 4-41）中 α、β 权重决定轮廓的平滑程度：α 大抑制拉伸与间距不均，β 大抑制弯曲与尖角。本项并排演示不同 α/β 组合下轮廓的收敛形态差异。\n")
            append("【式 4-41】E_int = α|v′(s)|² + β|v″(s)|²\n")
            append("【效果】可观察低 β 轮廓出现尖角抖动、高 β 轮廓过度平滑的现象。")
        }) { src -> OpencvDealJni.segSnakeSmoothCompare(src) }

        addOp("③ 气球力 Snake", buildString {
            append("【原理】基本 Snake 的收敛半径有限，初始轮廓离目标太远时无法到达。Cohen 气球模型在能量中增加各向同性的气球力：沿轮廓法线方向持续膨胀（或收缩），推动曲线跨越灰度平坦区，到达目标边缘后由图像能量停住。\n")
            append("【公式】E = E_int + E_img + k·(法向膨胀项)\n")
            append("【效果】从远离目标的初始轮廓出发仍能膨胀并收敛到边缘。")
        }) { src -> OpencvDealJni.segBalloonSnake(src, 400) }

        addOp("④ 测地线主动轮廓 (式 4-46)", buildString {
            append("【原理】测地线主动轮廓 GAC（式 4-46）：把气球力乘以边缘停止函数 g = 1/(1+|∇Gσ*f|)，")
            append("平坦区 g≈1 气球力全速推进，强边缘处 g→0 轮廓自动停止，")
            append("解决了普通气球力越过弱边缘的问题。\n")
            append("【式 4-46】∂C/∂t = g·|C|·N − (∇g)·N\n")
            append("        第一项为 g 调制的法向推进力，第二项为边缘吸引项。\n")
            append("【实现】在贪心 Snake 框架中用 1/(1+20g) 调制气球力系数。\n")
            append("【效果】轮廓在平坦区快速推进、在边缘前精确停止。")
        }) { src -> OpencvDealJni.segGeodesicContour(src, 400) }

        addOp("⑤ Chan-Vese (式 4-47)", buildString {
            append("【原理】Chan-Vese 区域型主动轮廓（式 4-47）：不依赖图像梯度边缘，而是按「轮廓内外区域灰度应各自均匀」的准则演化曲线：把图像划分为轮廓内（均值 c1）与轮廓外（均值 c2）两个区域，能量最小时曲线即为分割边界。\n")
            append("【式 4-47】E(c1,c2,C) = ∫_inside(f−c1)² + ∫_outside(f−c2)² + ν·Length(C)\n")
            append("【实现】水平集简化为区域标记交替更新：c1/c2 取轮廓内外均值，按能量准则逐像素重新归类，迭代至收敛。\n")
            append("【效果】对弱边缘、无清晰边缘的目标也能完成分割，与梯度型 Snake 形成互补。")
        }) { src -> OpencvDealJni.segChanVese(src, 60) }
    }

    // -------------------------------------------------------------------------
    // 执行与 UI 渲染
    // -------------------------------------------------------------------------

    /**
     * 应用被选中的算法项：先刷新公式文本，再在后台线程执行 JNI 运算，
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
        fun instantiate(module: FFViewModel.FRAGMENT_STATUS): SegOpFragment {
            return SegOpFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODULE, module.name)
                }
            }
        }
    }
}
