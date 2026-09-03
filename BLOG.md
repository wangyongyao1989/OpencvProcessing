# 从教材到真机：一个 Kotlin + OpenCV(C++/JNI) 数字图像与视频处理工程的全景解析

> 剖析一个 7 模块的 Android 工程：图像增强、形态学、图像分割、数字水印（LSB/DCT）、质量评价（PSNR/SSIM）、基于内容的图像/视频检索（综合相似度）、模板匹配（NCC/Hu 矩）、视频人脸识别（Haar 级联 + IoU 跟踪 + 跟踪命中率）。
>
> 本文为每个模块给出**实现原理 → 代码解析 → 运用场景**三段式剖析，聊聊算法工程化中「解耦」的实践。
>
> **源码地址**：<https://github.com/wangyongyao1989/OpencvProcessing>

## 一、前言

学习数字图像处理，最痛苦的不是看不懂公式，而是：教材里的式子在代码里长什么样？跑在真机上是什么效果？这个工程（`OpencvProcessing`）就是为了回答这个问题——把《数字图像与视频处理》教材中的经典算法，落地成一个可以在 Android 真机上交互验证的实验平台。

工程全貌：

| 模块                  | 职责                      | 算法实现方式                      | 典型运用场景       |
| ------------------- | ----------------------- | --------------------------- | ------------ |
| `app`               | 主壳：导航 + 27 个功能 Fragment | Kotlin                      | 实验入口         |
| `imageCVdeal`       | 增强/形态学/分割算法底座           | **C++ + OpenCV + JNI**      | 医学影像增强、低照度监控 |
| `digitalwatermark`  | LSB/DCT 数字水印 + 视频水印     | 纯 Kotlin + MediaCodec       | 版权保护、溯源取证    |
| `qualityevaluation` | PSNR/SSIM 客观质量评价        | 纯 Kotlin                    | 编码器调优、传输监控   |
| `contentsearch`     | 基于内容的图像/视频检索            | 纯 Kotlin                    | 以图搜图、视频素材定位  |
| `imagerecognition`  | NCC 模板匹配 / Hu 矩 / 视频跟踪  | 纯 Kotlin                    | 工业质检、目标跟踪    |
| `videorecognition`  | Haar 视频人脸识别 + 关键帧检索     | **JNI + CMake（自包含 OpenCV）** | 安防监控、视频内容审核  |

## 二、工程骨架：一个 ViewModel 走天下

主界面采用「卡片主页 + 功能二级页」结构。导航没有用 Navigation 组件，而是用一个 `FFViewModel` 暴露的 `LiveData<FRAGMENT_STATUS>` 枚举做路由：

```kotlin
// FFViewModel.kt —— Fragment 路由中枢
enum class FRAGMENT_STATUS {
    MAIN, IMAGE_ENHANCE, IMAGE_GRAY_TRANSFORM, MORPHOLOGY,
    SEGMENTATION, WATERMARK, QUALITY, CONTENT_SEARCH,
    IMAGE_RECOGNITION, VIDEO_RECOGNITION, /* ...三级子页 */
}
val switchFragment = MutableLiveData<FRAGMENT_STATUS>()
```

`MainActivity` 订阅它，用 `add/hide/show` 切换——**hide/show 而非 replace**，这意味着用户切走再切回来，分析结果、播放进度全部保留，不需要重新跑一遍 459 帧的人脸检测。

所有功能页继承统一的 `BaseFragment`，生命周期模板固定为 `getLayoutBinding → initView → initObserver → initData → initListener`，配合 ViewBinding，27 个 Fragment 的结构完全一致。

## 三、imageCVdeal：JNI 封装 OpenCV 的算法底座

### 3.1 实现原理

`imageCVdeal` 是唯一的 native 算法底座，覆盖教材前三章的核心内容：

- **灰度变换**（第 2 章）：线性/反转/分段线性/削波/阈值化/对数/伽马/直方图均衡化——调整像素灰度映射曲线；

- **平滑去噪**：空间域（邻域平均/中值/NLM）与频率域（理想/高斯低通）双路线；

- **锐化与复原**：梯度法/Roberts/Sobel/Laplacian/高通滤波、同态滤波、Retinex（SSR/MSR/MSRCR）；

- **形态学**（第 6 章）：二值（腐蚀/膨胀/开闭/骨架/细化）与灰度（梯度/Top-Hat/Bottom-Hat）；

- **分割**（第 7 章）：阈值/边缘/区域/主动轮廓（Chan-Vese）。

架构上一条铁律：**C++ 只做计算，Kotlin 只做胶水**。Bitmap ↔ Mat 转换之外零逻辑，每个 native 方法输入 Bitmap + 参数、输出 Bitmap。

### 3.2 代码解析：同态滤波的完整链路

以最能体现「教材公式 → 工程代码」的**同态滤波**为例（`ImageHomomorphic.cpp`）。原理：图像 = 照度分量 I（低频，光照分布）× 反射分量 R（高频，物体表面细节）。取对数把乘性关系变加性，再在频域分别处理：

```
f(x,y) = i(x,y)·r(x,y)
→ z = ln f                    (式 2-82，+1 避免 ln 0)
→ Z = DFT(z)                  (式 2-83)
→ S = H(u,v)·Z                (式 2-85，H 压低频抬高频)
→ s = IDFT(S)                 (式 2-86)
→ g = e^s                     (式 2-87)
```

关键代码逐段解析：

```cpp
// ① 式 2-82：z = ln f（+1 避免 ln 0）
f8u.convertTo(f32, CV_32F);
f32 += 1.0f;
cv::log(f32, z);

// ② 补零到最优 DFT 尺寸 + 乘 (-1)^(x+y) 把频谱中心化
//    （频谱中心化等价于把低频挪到图像中心，便于构造以中心
//     为原点的传递函数——这是频域滤波最容易踩坑的一步）
int m = cv::getOptimalDFTSize(z.rows);
cv::copyMakeBorder(z, padded, 0, m - z.rows, ...);
for (int y = 0; y < padded.rows; ++y)
    for (int x = 0; x < padded.cols; ++x)
        if (((x + y) & 1) != 0) row[x] = -row[x];   // 中心化

// ③ 传递函数（图 2-42 剖面）：
//    H(u,v) = (HH−HL)·(1 − e^(−c·D²/D0²)) + HL
//    D 为频域点到中心的距离：D 小（低频/照度）→ H≈HL 被压制；
//    D 大（高频/反射）→ H≈HH 被增强
double D2 = (u - cu) * (u - cu) + (v - cvn) * (v - cvn);
hRow[v] = (hh - hl) * (1.0 - std::exp(-c * D2 / (d0 * d0))) + hl;

// ④ 频域相乘 → IDFT → 再乘 (-1)^(x+y) 去中心化 → e^s 还原
cv::idft(complexI, complexI, cv::DFT_SCALE);
cv::exp(s, g);
```

一个隐藏的性能优化同样值得注意——**大 σ 高斯的降采样等价实现**：

```cpp
// σ>30 时卷积核很长，等价做法：降采样 → 模糊(σ/factor) → 升采样
cv::resize(f32, small, ..., cv::INTER_AREA);
cv::GaussianBlur(small, smallBlur, cv::Size(0, 0), sigma / factor);
cv::resize(smallBlur, L, f32.size(), ..., cv::INTER_LINEAR);
```

这是 Retinex 光照估计的基础：光照图天然是低频的，没必要在全分辨率上算大核卷积。

### 3.3 运用场景

- **低照度监控/行车记录仪**：夜间画面直方图均衡化或 MSRCR 增强，提升可视性；

- **医学影像**：同态滤波压制 X 光片的整体曝光不均、增强组织边缘；锐化辅助医生观察病灶纹理；

- **文档扫描/OCR 预处理**：自适应阈值分割 + 形态学去噪，把拍照文档变成干净二值图；

- **工业检测**：Top-Hat 提取零件表面微小缺陷（亮目标暗背景）。

## 四、digitalwatermark：LSB 与 DCT 双水印

### 4.1 实现原理

**空间域（LSB）**：把水印比特直接写进像素最低位——人眼对最低位变化完全不敏感（PSNR 理论值 48dB+）。工程上加了两个增强：水印位阵按**平铺周期 4096** 重复铺满全图（帧内冗余），提取时**多数投票**（抗裁剪）。

**变换域（DCT）**：与 JPEG 同域，选择性地抗有损压缩。核心权衡是嵌入位置：

- 低频系数：能量大、鲁棒，但改动它画面可见（不可见性差）；

- 高频系数：不可见性好，但 JPEG 量化一步就抹掉（鲁棒性差）；

- **中频（Zig-Zag 序号 9\~16）：折中**——这正是 JPEG 量化表（表 5-4）中量化步长适中的区域。

嵌入公式：`c'j = cj + k·pnj·m`，每比特经 **PN 序列扩频**（8 chips）调制——没有密钥（PN 序列种子）就无法提取，这就是水印的安全性来源。提取是**盲提取**：无需原图，对中频系数与 PN 序列做相关检测，`corr > 0 → bit 1`。

### 4.2 代码解析：DCT 嵌入与盲提取

`DctWatermark.kt` 是教科书式的实现。先看 8×8 DCT 的**可分离实现**（行变换 → 列变换，复杂度从 O(n⁴) 降到 O(n³)）：

```kotlin
// 正交基：B[u][x] = c(u)·cos((2x+1)·u·π/16)
// c(0)=√(1/8)（能量归一化），c(u>0)=1/2
private val B = Array(8) { u ->
    val c = if (u == 0) sqrt(1.0 / 8.0) else 0.5
    DoubleArray(8) { x -> c * cos((2 * x + 1) * u * Math.PI / 16.0) }
}

// 二维 DCT = 行方向乘 B^T，再列方向乘 B（可分离性）
private fun forwardDct(f: Array<DoubleArray>): Array<DoubleArray> {
    // 行方向：tmp[x][v] = Σy f[x][y]·B[v][y]
    for (x in 0 until 8) for (v in 0 until 8) {
        var s = 0.0
        for (y in 0 until 8) s += f[x][y] * B[v][y]
        tmp[x][v] = s
    }
    // 列方向：F[u][v] = Σx tmp[x][v]·B[u][x]
    ...
}
```

嵌入主循环——注意**帧内冗余**的设计（4096 个子块承载 4096 bit 水印，每 bit 一个独立块）：

```kotlin
fun embed(luma: ByteArray, width: Int, height: Int, bits: BooleanArray,
          key: Long = DEFAULT_KEY) {
    val pn = pnSequence(key)              // 密钥生成 ±1 双极性序列
    ...
    for (i in bits.indices) {
        val bi = i * step                  // 均匀分布到全图的子块
        ...
        val F = forwardDct(f)              // 取块 → DCT
        // 中频加性嵌入：cj' = cj + k·pnj·m，m = ±1 双极性比特
        val m = if (bits[i]) 1.0 else -1.0
        for (j in 0 until CHIPS) {
            val (u, v) = MID_FREQ[j]       // Zig-Zag 序号 9~16
            F[u][v] += STRENGTH * pn[j] * m
        }
        val g = inverseDct(F)              // IDCT → 写回
        ...
    }
}
```

盲提取只需相关检测——这就是「盲」的含义：**不需要原始图像**：

```kotlin
fun extractVotes(luma: ByteArray, width: Int, height: Int,
                 key: Long = DEFAULT_KEY): IntArray {
    val pn = pnSequence(key)               // 同一密钥重建 PN 序列
    ...
    var corr = 0.0
    for (j in 0 until CHIPS) {
        val (u, v) = MID_FREQ[j]
        corr += F[u][v] * pn[j]            // 中频系数与 PN 序列相关
    }
    votes[i] = if (corr > 0) 1 else -1     // 式 8-5：相关检验
    ...
}
```

为什么相关检测能恢复比特？嵌入时若 bit=1，系数被加上 `+k·pnj`，与 pnj 的内积多出 `k·Σpnj² = k·8 > 0`；若 bit=0 则减去，内积为负。**攻击（噪声/JPEG）只能扰动内积符号，只要扰动 < k·8，比特就无差错恢复**——k=30 的强度就是按此裕量选的。

视频水印走 `MediaExtractor → MediaCodec 解码 → Y 平面嵌入 → MediaCodec 编码 → MediaMuxer` 的逐帧转码管线，多帧提取时再**帧间投票**，鲁棒性进一步提升。

### 4.3 运用场景

- **版权保护/溯源取证**：摄影作品、视频素材发布前嵌入作者 ID 水印，被盗用后提取取证——DCT 方案即使截图/转码/加 logo 也大概率存活；

- **盗版内容监测**：视频平台给每个分发渠道嵌入不同水印（指纹水印），泄露源头可从盗版片中反查是哪个渠道流出；

- **AI 生成内容标识**：AIGC 图片嵌入不可见水印声明生成来源（当前监管热点，国内《生成式 AI 服务管理暂行办法》已有标识要求）；

- **教学演示**：LSB 与 DCT 面对同一组攻击（JPEG/噪声/裁剪）的指标差异，直观展示「空间域 vs 变换域」的鲁棒性鸿沟。

## 五、qualityevaluation：PSNR 与 SSIM 的实现细节

### 5.1 实现原理

全参考（Full-Reference）客观评价：同时拥有原图与失真图，在灰度分量上计算：

- **MAE/MSE**：逐像素误差的均值/均方值——简单但对「误差的位置」不敏感（人眼对边缘错位远比平坦区噪声敏感）；

- **PSNR** = 10·lg(L²/MSE)：MSE 的对数换算，单位 dB，越高越好（工程惯例 >35dB 认为质量良好）；

- **SSIM**：亮度 l、对比度 c、结构 s 三因子乘积，∈(0,1]，比 PSNR 更贴合人眼感知——因为它把「结构保持程度」显式建模了；

- **熵** H = −Σp·log₂p：衡量信息量，评价增强类算法是否引入/丢失信息。

### 5.2 代码解析：SSIM 的三因子分解

`ImageQualityMetrics.ssim()` 按 8×8 不重叠分块计算（局部统计是人眼感知特性的离散化）：

```kotlin
fun ssim(a: ByteArray, b: ByteArray, width: Int, height: Int): Double {
    ...
    for (by in 0 until blocksY) for (bx in 0 until blocksX) {
        // 块内一阶/二阶统计量：一次遍历同时累加
        // sa/sb（和）、saa/sbb（平方和）、sab（互相关）
        ...
        val ma = sa / n; val mb = sb / n           // 块均值 μ
        val va2 = saa / n - ma * ma                 // 方差 σ²（E[X²]−E[X]²）
        val vab = sab / n - ma * mb                 // 协方差 σxy

        // 式 9-4~9-6：三因子乘积
        val l = (2.0 * ma * mb + C1) / (ma * ma + mb * mb + C1)          // 亮度
        val c = (2.0 * sqrt(va2 * vb2) + C2) / (va2 + vb2 + C2)          // 对比度
        val s = (vab + c3) / (sqrt(va2 * vb2) + c3)                      // 结构
        total += l * c * s
    }
    return total / count    // 全图平均
}
```

两个工程细节：

1. **稳定常数 C1=(0.01L)²、C2=(0.03L)²**：分母加常数避免「平坦块方差≈0 → 除零 → 指标爆跳」，同时保证当 μa=μb=0（全黑）时 SSIM 仍有良定义极限 1；
2. **PSNR 上限 99dB**：MSE=0（两图完全相同）时 `log10(0)` 无定义，工程上返回上限值而非 NaN——这类边界处理是「教材代码」与「可用代码」的分水岭。

模块还内置**失真生成器**（JPEG/高斯噪声/椒盐噪声/均值模糊/亮度偏移），可以亲手制造失真再看指标变化——这是理解「PSNR 高 ≠ 看着好」的最快途径（椒盐噪声 PSNR 不低但视觉极差）。

### 5.3 运用场景

- **编码器/转码参数调优**：视频平台在码率-质量曲线上选拐点（CRF 每加 2，PSNR 掉多少 dB）；

- **传输链路监控**：直播推流链路逐段对比 PSNR/SSIM，定位画质劣化发生在哪一环；

- **算法回归测试**：图像增强/水印模块的 CI 中以 PSNR/SSIM 为阈值断言——水印嵌入后 PSNR 必须 >38dB（不可见性），攻击后 NC 必须 >0.9（鲁棒性）；

- **超分/去噪模型评价**：深度学习超分模型训练中 SSIM 作为 loss 或评价指标（本工程的实现与业界标准完全一致）。

## 六、contentsearch：基于内容的检索（CBIR）

### 6.1 实现原理

CBIR 流程：`查询图 → 提取内容特征 → 与图像库特征逐一匹配 → 按相似度排序返回 Top-K`。核心是「用什么特征描述图像内容」：

- **颜色特征**：HSV 空间 3D 直方图，按 (H,S,V) 联合量化——H 8 级 × S 3 级 × V 3 级 = **72 维**（教材第 10 章经典量化方案）。选 HSV 而非 RGB 是因为 HSV 把「色相」与「亮度」分离，对光照变化更鲁棒；

- **纹理特征**：Sobel 梯度方向直方图（18 bins，幅值加权、仅统计显著边缘）——区分「天空大海（平滑）」与「草地树林（复杂纹理）」这类颜色相近但结构迥异的图；

- **综合相似度**：`Sim = 0.6 × 直方图相交(颜色) + 0.4 × 余弦(纹理)`——颜色为主、纹理补歧义。

### 6.2 代码解析：HSV 3D 直方图

`ImageFeatures.colorHistogram()` 的量化索引是关键：

```kotlin
fun colorHistogram(argb: IntArray): FloatArray {
    val hist = FloatArray(COLOR_DIM)          // 72 维
    for (p in argb) {
        // RGB → HSV（max-min 公式）
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val v = max                            // 明度 = 最大分量
        val s = if (max <= 0.0) 0.0 else delta / max
        val h = when {
            max == r -> 60.0 * (((g - b) / delta) % 6.0)
            max == g -> 60.0 * (((b - r) / delta) + 2.0)
            else     -> 60.0 * (((r - g) / delta) + 4.0)
        }.let { if (it < 0) it + 360.0 else it }

        // 联合量化：三维 → 一维索引
        val hi = (h / 360.0 * H_BINS).toInt()      // 色调 8 级
        val si = (s * S_BINS).toInt()              // 饱和度 3 级
        val vi = (v * V_BINS).toInt()              // 明度 3 级
        hist[hi * S_BINS * V_BINS + si * V_BINS + vi]++
    }
    // L1 归一化（和为 1）：消除分辨率/像素数差异
    for (i in hist.indices) hist[i] /= n
    return hist
}
```

**为什么这样量化？** H 用 8 级（人眼对色相最敏感，分得最细），S/V 各 3 级（人眼对明暗变化相对钝感）——72 维在区分度与存储/计算量之间取得平衡，这是 90 年代 IBM QBIC 系统验证过的工程方案。

纹理直方图与 videorecognition 模块的 `KeyframeFeatures` 同源：Sobel 求梯度 → 幅值加权统计方向 → **方向折叠到 \[0°,180°)**（无极性：梯度方向 θ 与 θ+180° 是同一边缘）→ 仅统计显著边缘（幅值>均值）抗噪。

检索引擎 `ImageSearchEngine` 的图像库会自动构造「原图 + 光度/几何变换副本（亮度调整/翻转/裁剪）+ 合成干扰图」，检索结果能直观验证特征的不变性。

### 6.3 运用场景

- **以图搜图**：电商拍照搜同款（颜色+纹理特征是最早的工业方案，现在多与深度特征融合）；

- **视频素材管理**：从长视频中以关键帧检索定位「同一场景」片段（本工程 videorecognition 模块复用此原理）；

- **重复内容检测**：内容平台查重——同一图片加水印/调色/裁剪后仍能被 72 维直方图召回；

- **相册自动归类**：按颜色分布聚类（海滩=蓝黄主导、森林=绿主导）。

## 七、imagerecognition：NCC 匹配与 Hu 矩

### 7.1 实现原理

**NCC（归一化互相关）**（式 11-1）：

```
NCC(x,y) = Σ[(f−μf)·(t−μt)] / √(Σ(f−μf)²·Σ(t−μt)²) ∈ [-1,1]
```

相比 SSD/SAD，NCC 的杀手锏是**对线性光照变化（增益/偏置）不变**：窗口像素整体变亮/变暗时，减均值消偏置、除标准差消增益——模板在阴影里也能匹配上。

**Hu 不变矩**：7 个由二阶/三阶中心矩构造的不变量，对**平移/旋转/尺度**不变（对数变换压缩动态范围后做最近邻分类）。形状识别的经典baseline。

**视频跟踪**：首帧取模板 → 逐帧 NCC 搜索 → trackScore < 0.5 判丢失（跟踪命中率 trackRate 的原始定义）。

### 7.2 代码解析：金字塔粗到精搜索

`NccMatcher.searchFull()` 是性能设计的好例子。暴力全图滑窗的复杂度是 O(W·H·tw·th)，1920×858 图搜 100×100 模板 ≈ 160 亿次乘加——必须降：

```kotlin
fun searchFull(...): DoubleArray {
    // ---- 粗搜：1/4 分辨率（2×2 均值下采样，面积缩 16 倍）----
    val small = downsample(gray, width, height, 4)
    val smallTpl = downsample(tpl, tw, th, 4)
    val coarse = searchWindow(small, sw, sh, smallTpl, stw, sth,
                              0, 0, sw - stw, sh - sth)   // 全图搜（1/16 计算量）

    // ---- 精化：粗结果 ×4 映射回原分辨率，局部 ±8px 搜 ----
    val cx = (coarse[0] * 4).toInt().coerceIn(0, width - tw)
    val cy = (coarse[1] * 4).toInt().coerceIn(0, height - th)
    return searchWindow(gray, width, height, tpl, tw, th,
                        cx - refine, cy - refine, cx + refine, cy + refine)
}
```

为什么 1/4 均值下采样是「安全」的？均值滤波是低通，模板与大图同时低通后 NCC 极大值位置基本不变（只是变平坦了）——所以粗搜定位到 ±4·像素精度，精搜在全分辨率补回亚像素级精度。**总计算量降到约 1/16 + 局部精搜**。

单点 NCC 的实现里，模板统计量（均值/方差）**只算一次**，滑窗时每个位置重算窗口统计量：

```kotlin
// 模板均值与方差（循环外一次）
val tMean = tSum / tpl.size
var tVar = 0.0
for (i in tpl.indices) { val d = tpl[i] - tMean; tVar += d * d }

// 每个候选位置：窗口均值 → 互相关与窗口方差一遍累加
val score = cross / kotlin.math.sqrt(fVar * tVar)   // 归一化到 [-1,1]
```

`similarityMap()` 还能输出粗网格 NCC 热力图，UI 上渲染成相似度分布——教学演示「匹配发生在哪里」。

### 7.3 运用场景

- **工业质检**：PCB 板上定位元件/Mark 点（光照车间明暗不一，NCC 的光照不变性恰好对症）；缺陷区域与模板比对找差异；

- **机器人视觉抓取**：传送带上定位工件位置与角度（Hu 矩识别形状类别 + NCC 定位）；

- **视频目标跟踪**：无人机锁定地面目标、运动相机防抖参考点跟踪——trackScore 实时监控跟踪质量，低于阈值报警或触发重捕获；

- **OCR 前的版面分析**：定位票据表格线/印章位置。

## 八、videorecognition：四条算法链共用一个解码器

工程中复杂度最高的模块：**一条视频流，四条算法链**。

```
                    ┌──────────────────────────────┐
                    │  VideoFrameSource             │
                    │  MediaCodec 软解 → Y 亮度平面  │
                    └───────┬──────────┬───────────┘
                            │          │
              ┌─────────────▼──┐   ┌───▼────────────────────┐
              │ FaceVideoAnalyzer│   │ VideoKeyframeIndexer    │
              │ ①人脸检测+跟踪   │   │ ③关键帧抽取+索引         │
              └───────┬─────────┘   └───┬────────────────────┘
                      │                 │
              ┌───────▼─────────┐   ┌───▼────────────────────┐
              │ ②跟踪命中率评价   │   │ ④综合相似度检索 Top-5    │
              └─────────────────┘   └────────────────────────┘
```

### 8.1 实现原理：为什么要软解 + 只取 Y 平面

Android 的 `MediaCodec` **硬解码器实例是稀缺资源**——同规格解码器一个进程往往只允许创建一个实例，人脸分析和关键帧抽取两条链同时跑会撞车。工程解法是显式选择**软件解码器**（`c2.android.*` 前缀），无并发实例限制：

```kotlin
// VideoFrameSource.kt —— 软解选择
private fun createSoftwareDecoder(mime: String): MediaCodec? {
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    for (ci in codecs) {
        if (ci.isEncoder) continue
        if (!ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
        val name = ci.name.lowercase()
        if (name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("c2.google.")
        ) return MediaCodec.createByCodecName(ci.name)   // 软解
    }
    return null   // 兜底硬解
}
```

拉取式解码循环 + 防御性设计：

```kotlin
fun nextFrame(): ByteArray? {
    var idleRounds = 0
    while (!outputDone) {
        // 输入侧：读 sample → queueInputBuffer；流结束送 EOS 标志
        ...
        // 输出侧：0.5s 超时取帧
        val outIdx = decoder.dequeueOutputBuffer(info, 500_000)
        if (outIdx >= 0) {
            idleRounds = 0
            val image = decoder.getOutputImage(outIdx)
            luma = extractY(image)            // 只取 Y 平面
            ...
        } else {
            if (++idleRounds > 60)            // 连续 30s 无输出 → 抛异常
                throw IllegalStateException("Decoder stalled...")
        }
    }
}
```

只取 Y 平面不是偷懒——后续所有算法（Haar 检测、直方图、Sobel）都在灰度上进行，**YUV420 的 Y 分量就是现成的灰度图**，省掉一次颜色转换。`extractY()` 还处理了 rowStride/pixelStride 两种内存布局，输出**行紧凑**数组（后续所有算法的统一输入契约）。

### 8.2 代码解析：Haar 人脸检测的 JNI 边界

C++ 侧严格遵循 Viola-Jones 框架（积分图 O(1) 区域和 + AdaBoost 特征选择 + 级联快速排除），并做了**光照自适应**：

```cpp
// FaceDetector.cpp
std::vector<cv::Rect> detect(const cv::Mat &gray, int minFace,
                             std::vector<double> &confidences) {
    cv::Mat eq;
    cv::equalizeHist(gray, eq);          // 直方图均衡化：战争片明暗剧烈，
                                          // 均衡后暗部人脸特征可辨
    cascade_.detectMultiScale(
            eq, faces, rejectLevels, levelWeights,
            1.1,   // scaleFactor：多尺度步进（1.1~1.3，越小越细越慢）
            5,     // minNeighbors：NMS 去重邻域（越大越严格）
            0, cv::Size(minFace, minFace), cv::Size(),
            true); // outputRejectLevels：拿到级联末级权重作置信度

    for (size_t i = 0; i < faces.size(); i++) {
        double conf = levelWeights[i] / 15.0;   // 权重典型 [0,15] → (0,1]
        confidences.push_back(conf);
    }
}
```

JNI 边界的两个设计决策：

1. **句柄模式管生命周期**：`nativeCreate` 返回 `jlong` 句柄（C++ 对象指针），`nativeDestroy` 释放——cascade 模型只加载一次，检测调用零模型加载开销；
2. **零拷贝 + 扁平数组返回**：灰度数组直接包装成 `cv::Mat`（不拷贝，只读）；返回 `[n, x1,y1,w1,h1,conf1*1000, ...]` 扁平 int 数组——比构造 Java 对象数组快得多。

```cpp
// 零拷贝包装：GetByteArrayElements 后直接构造 Mat 视图
jbyte *buf = env->GetByteArrayElements(gray, nullptr);
cv::Mat mat(h, w, CV_8UC1, buf);              // 不拷贝
auto faces = detector->detect(mat, minFace, confs);
env->ReleaseByteArrayElements(gray, buf, JNI_ABORT);   // ABORT：不回写
```

### 8.3 代码解析：IoU 时间平滑与跟踪命中率

Kotlin 侧拿到逐帧检测框后做**时间平滑**——Haar 逐帧独立检测，框会有 1\~2px 抖动，肉眼可见地「跳」：

```kotlin
// FaceTrackMath.smooth：当前帧每个框在历史窗口(5帧)中
// 按 IoU>0.3 找关联框，位置/尺寸取「当前值与关联均值」的折中
fun smooth(current: List<FaceBox>, history: List<List<FaceBox>>): List<FaceBox> {
    return current.map { box ->
        var sx = 0.0; var sy = 0.0; var cnt = 0
        for (hf in history) {
            val best = hf.maxByOrNull { iou(box, it) }      // 最佳关联框
            if (best != null && iou(box, best) > 0.3) {     // 阈值关联
                sx += best.x; sy += best.y; cnt++
            }
        }
        if (cnt == 0) box    // 新目标：无历史可平滑，保持原样立即显示
        else FaceBox(
            ((box.x + sx / cnt) / 2).toInt(),               // 折中
            /* ... */
        )
    }
}
```

**跟踪命中率**是教材「视频的图像识别处理」中 trackRate（trackScore < 0.5 判丢失）向多目标检测框的时序推广：

```kotlin
fun trackingStats(frames: List<FaceFrame>): TrackStats {
    for (i in 1 until frames.size) {
        val prev = frames[i - 1].faces
        if (prev.isEmpty()) { longest = maxOf(longest, streak); streak = 0; continue }
        candidates++                                   // 候选：前一帧有目标
        // 前一帧每个框在当前帧找最大 IoU，取所有目标中的最优
        val best = prev.maxOf { p -> cur.maxOfOrNull { iou(p, it) } ?: 0.0 }
        if (best > TRACK_IOU_THRESHOLD) {              // 命中：IoU>0.3 关联成功
            hits++; iouSum += best; streak++
        } else streak = 0                              // 丢失：目标消失或位移过大
    }
    return TrackStats(candidates, hits, longestStreak, iouSum / hits)
}
```

四个指标各有含义：**hitRate**（整体跟踪质量）、**lostPairs**（丢失是否集中在快速运动段）、**longestStreak**（稳定性）、**avgAssociationIoU**（框贴合程度）。

### 8.4 代码解析：播放同步与「以当前帧检索」

**离线分析与在线播放解耦**——分析一次，播放时 O(log n) 查表同步：

```kotlin
// FaceVideoFragment：33ms ≈ 30fps 播放同步循环
private val syncRunnable = object : Runnable {
    override fun run() {
        val pos = player.currentPosition
        updateFacesFor(pos)        // 二分查找最近分析帧 → 刷新叠加层
        mainHandler.postDelayed(this, 33)
    }
}

// FaceTrackMath.findNearestFrameIndex：按时间戳二分定位
// O(log n)——459 帧查找只要 9 次比较
```

\*\*「以当前帧检索视频」\*\*让四条链闭环——播放中任意时刻，捕获画面作为查询帧检索相似时刻，结果附该时刻人脸数，点击跳转检查识别效果：

```kotlin
private fun runFaceFrameSearch() {
    val frame = binding.textureView.bitmap      // ① 捕获当前画面（所见即所查）
    thread(start = true, name = "face-frame-search") {
        if (indexer.keyframes.isEmpty()) {      // ② 索引未建：先抽关键帧（后台）
            indexer.extractKeyframes(videoFile.absolutePath, KEYFRAME_COUNT) { ... }
        }
        // ③ 画面 → 亮度平面（BT.601 加权）→ 与关键帧特征同空间
        val query = VideoKeyframeIndexer.bitmapToLuma(frame)
        // ④ 综合相似度 Top-5：Sim = 0.6×直方图相交 + 0.4×余弦
        val topK = indexer.search(query.luma, query.width, query.height, k = TOP_K)
    }
}
```

### 8.5 运用场景

- **安防监控**：录像中人脸出现位置快速浏览（检出人脸的帧占比 43% 这类统计直接告诉侦查人员哪些时段有人）；关键帧检索定位「与某画面相似的时刻」；

- **视频内容审核**：跟踪命中率作为质量指标——命中率过低说明检测框不可信，需要降权或人工复核；

- **影视后期**：从长素材中以查询帧检索相似镜头（同机位/同场景），剪辑师快速聚合素材；

- **智能相册/短视频**：自动统计「谁在什么时间出现」，人脸覆盖率驱动的精彩片段抽取。

### 8.6 自包含的 native 构建

`videorecognition` 没有依赖 `imageCVdeal`，而是把 OpenCV 头文件和 `.so` 复制进自己的 `src/main/cpp`，独立 CMake 编译。代价是 APK 体积（\~75MB），换来的是**模块可独立编译、可整体移植**到其他工程。

## 九、数字水印 × 质量评价：一对孪生实验

`digitalwatermark` 与 `qualityevaluation` 共享同一套 MediaCodec 管线思路，构成「嵌入—攻击—评价」闭环：

| <br /> | LSB（空间域）  | DCT（变换域）                 |
| ------ | --------- | ------------------------ |
| 嵌入位置   | 最低位平面     | 8×8 块中频系数（Zig-Zag 9\~16） |
| 密钥     | 平铺周期 4096 | PN 序列（`0x5A5A2026`）      |
| 提取     | 多数投票      | 相关检测 + 投票（**盲提取**）       |
| 抗攻击    | 弱（裁剪可恢复）  | 强（抗 JPEG/噪声）             |

UI 上一键执行 JPEG 压缩/加噪/模糊攻击，实时看 PSNR（不可见性）、NC（保真度）、BER（鲁棒性）变化——**空间域与变换域的鲁棒性差异，跑一遍胜过背十遍结论**。

## 十、工程亮点总结

1. **算法/UI 严格分层**：所有数学都是不 import Android 的纯 Kotlin object，80+ JVM 单测直接验证——包括浮点精度边界（直方图 L1 归一化的 1e-5 容差）与算法边界（Sobel 对棋盘格梯度恰为零的退化）；
2. **JNI 只做「薄胶水」**：句柄管理生命周期、零拷贝 Mat 视图、扁平数组返回，C++ 零业务逻辑；
3. **一个解码器喂多条算法链**：软解规避硬解实例限制，Y 平面紧凑数组成为模块统一契约；
4. **离线分析与在线播放解耦**：分析一次，播放时 O(log n) 查表同步；
5. **算法概念产品化**：跟踪命中率、综合相似度不是躺在文档里的名词，而是界面上一键可见的数字与可视化。

## 十一、写在最后

这个工程的价值不在「造轮子」，而在于**把教材公式到工程代码之间的每一层胶水都亲手写一遍**：频域滤波为什么要中心化？Sobel 方向直方图为什么折叠到 180°？DCT 中频为什么是 9\~16？LSB 和 DCT 的鲁棒性差异到底多大？跑一遍实验，比看十篇博客都记得牢。

工程结构与完整说明见仓库根目录 [`README.md`](README.md)，视频识别模块的需求文档（用例/核心类设计/开发计划）在 `videorecognition/doc/` 下。

> **GitHub**：<https://github.com/wangyongyao1989/OpencvProcessing>

