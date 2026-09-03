# OpencvProcessing · 数字图像与视频处理实验平台

> GitHub：https://github.com/wangyongyao1989/OpencvProcessing

一个基于 **Kotlin + OpenCV(C++/JNI) + MediaCodec** 的 Android 数字图像与视频处理工程，将《数字图像与视频处理》教材中的经典算法落地为 7 个高内聚、低耦合的 Gradle 模块：从空间域/频率域增强、形态学、分割，到数字水印、质量评价、基于内容的图像/视频检索、模板匹配与视频人脸识别，全部算法均可在真机上交互验证。

## 1. 功能总览

| 模块 | 功能 | 核心算法 |
|---|---|---|
| **imageCVdeal** | 图像增强 / 形态学 / 分割的算法底座 | 灰度变换（线性/对数/伽马/直方图均衡化）、平滑去噪（邻域均值/中值/理想与高斯低通/NLM）、锐化（Roberts/Sobel/Laplacis/高通）、同态滤波、Retinex（SSR/MSR/MSRCR）、二值与灰度形态学、阈值/边缘/区域/主动轮廓分割 |
| **digitalwatermark** | 图像与视频数字水印（嵌入/提取/攻击） | LSB 空间域水印（平铺周期 4096）、8×8 DCT 变换域水印（中频系数 + PN 序列密钥 + 盲提取投票）、PSNR/NC/BER 评价、MediaCodec 逐帧视频水印管线 |
| **qualityevaluation** | 图像与视频客观质量评价 | 全参考指标 MAE/MSE/PSNR/SSIM/熵、失真生成（JPEG/高斯噪声/椒盐噪声/均值模糊/亮度偏移）、双路解码逐帧视频评价 |
| **contentsearch** | 基于内容的图像检索（CBIR）与视频检索 | HSV 3D 颜色直方图 + 梯度方向纹理直方图、直方图相交 + 余弦相似度的综合相似度（0.6 颜色 + 0.4 纹理）、关键帧均匀抽取与索引 |
| **imagerecognition** | 模板匹配 / 形状识别 / 视频目标跟踪 | NCC 归一化互相关（金字塔粗到精搜索）、Hu 不变矩最近邻形状分类、逐帧模板跟踪 + 帧差运动检测（跟踪命中率 trackRate） |
| **videorecognition** | 视频人脸识别 / 关键帧检索 / 跟踪评价 | Haar 级联人脸检测（JNI）、IoU 时间平滑、跟踪命中率统计、关键帧索引与综合相似度检索 |
| **app** | 主壳工程：卡片式主页 + 27 个功能 Fragment | FFViewModel LiveData 导航、TextureView 播放 + 叠加层实时绘制 |

## 2. 架构设计

### 2.1 模块依赖

```
                    ┌──────────────────────────────┐
                    │              app              │  ← 主壳：导航/UI 编排
                    └──────┬───────────────────────┘
        ┌──────┬──────┬───┴──────┬──────────┬──────────┐
        ▼      ▼      ▼          ▼          ▼          ▼
  imageCVdeal  digital  quality  content-  image-     video-
  (JNI/OpenCV) watermark evaluation search  recognition recognition
```

- `app` 依赖全部 6 个业务模块，负责页面导航与交互；
- `imageCVdeal` 持有 OpenCV 预编译动态库（`opencv_java4`）与自研 C++ 算法库（`opencvdeal_native`），是唯一的算法底座；
- `videorecognition` 为自包含模块，内置 OpenCV 头文件/so 与 CMake 工程（Haar 检测）；
- 其余模块以纯 Kotlin 实现算法（MediaCodec/MediaMuxer/数学计算），不依赖 native。

### 2.2 三层解耦（以 videorecognition 为例）

```
┌─────────────────────────────────────────────────┐
│ UI 层（app 模块）                                 │
│  FaceVideoFragment / FaceOverlayView / TabLayout │
├─────────────────────────────────────────────────┤
│ 编排层（模块内）                                  │
│  FaceVideoAnalyzer / VideoKeyframeIndexer        │
│  VideoFrameSource（MediaCodec 软解，共享帧源）     │
├─────────────────────────────────────────────────┤
│ 算法层（纯 Kotlin，JVM 可测）                     │
│  FaceTrackMath / KeyframeFeatures                │
│  + JNI 边界：FaceJni → FaceDetector.cpp (Haar)   │
└─────────────────────────────────────────────────┘
```

**核心原则**：平台无关的数学（IoU、平滑、直方图、相似度）抽成 `object` 纯算法类，不 import 任何 Android 类，直接跑 JVM 单元测试；编解码用 `MediaCodec` 抽成共享帧源；UI 只做编排与渲染。

### 2.3 导航架构

`MainFragment`（功能卡片）→ `FFViewModel.switchFragment: LiveData<FRAGMENT_STATUS>` → `MainActivity.selectFragment()` 以 `add/hide/show` 切换 Fragment（保留各页面分析状态），共 27 个功能 Fragment，中英文双语。

## 3. 核心算法速览

### 3.1 视频人脸识别与跟踪（videorecognition）

```
VideoFrameSource(MediaCodec 软解, Y 平面)
   → downsample(1920×858 → 640×286)
   → FaceJni.nativeDetect: 灰度 → 直方图均衡化
       → cv::CascadeClassifier::detectMultiScale(scaleFactor=1.1, minNeighbors=5)
   → FaceTrackMath.smooth: 相邻帧 IoU>0.3 关联 + 滑动平均(窗口5)
   → 逐帧人脸框 + 全片统计
```

- **播放同步**：MediaPlayer 播放原始视频，叠加层 33ms 循环按进度二分查找最近分析帧绘制人脸框——检测与播放解耦，保证流畅。
- **跟踪命中率**：相邻帧对 (i-1, i)，第 i-1 帧检出目标计为候选；第 i 帧存在 IoU>0.3 关联框计为命中。命中率 = 命中/候选，辅以最长连续跟踪段、平均关联 IoU。

### 3.2 基于内容的视频检索（关键帧 + 综合相似度）

```
抽取关键帧并建立索引：均匀采样 12 帧 → 宽 320 亮度平面
   → 64 维灰度直方图(颜色) + 18 维 Sobel 梯度方向直方图(纹理)
以查询帧检索视频：
   Sim = 0.6 × 直方图相交(颜色) + 0.4 × 余弦(纹理) ∈ [0,1]
   → Top-5 → 点击跳转播放定位
```

「人脸识别」页签另支持**以当前播放帧检索**：`TextureView.getBitmap()` → BT.601 加权转亮度平面 → 复用同一索引检索，结果附该时刻检出人脸数。

### 3.3 数字水印（digitalwatermark）

- **LSB**：水印位阵按平铺周期 4096 重复嵌入最低位平面，提取时按多数投票恢复，抗裁剪；
- **DCT**：图像分 8×8 块 → 中频系数嵌入 PN 序列（±1 调制）→ 盲提取相关检测投票；抗 JPEG/噪声攻击；
- **视频水印**：`MediaExtractor → MediaCodec 解码 → Y 平面嵌入 → MediaCodec 编码 → MediaMuxer` 逐帧管线；
- **评价**：PSNR（不可见性）、NC 归一化互相关（保真度）、BER 误码率（鲁棒性）。

### 3.4 质量评价（qualityevaluation）

全参考客观评价：MAE、MSE、PSNR、SSIM（亮度/对比度/结构三因子）、信息熵；内置 5 类失真生成器构造「原始-失真」图像对；视频评价按「转码 → 双路解码 → 逐帧 PSNR/SSIM → 聚合」管线执行。

### 3.5 模板匹配与形状识别（imagerecognition）

- **NCC 匹配**：归一化互相关 + 1/4 分辨率粗搜 + 全分辨率局部精化（金字塔思想）；
- **形状识别**：Hu 不变矩（平移/旋转/尺度不变）+ 最近邻分类，返回类别/距离/置信度/Top-3；
- **视频跟踪**：逐帧 NCC 模板跟踪 + 帧差运动检测，统计跟踪命中率（trackScore < 0.5 判丢失）。

## 4. 目录结构

```
OpencvProcessing/
├── app/                      # 主壳：导航 + 27 个功能 Fragment + 播放/叠加 View
│   └── src/main/assets/      # midway.mp4 / video.mp4 / 测试图 / Haar 级联
├── imageCVdeal/              # OpenCV JNI 算法底座（cpp/ + jni/ + CMake）
├── digitalwatermark/         # LSB/DCT 水印 + 视频水印管线
├── qualityevaluation/        # PSNR/SSIM 质量评价 + 失真生成
├── contentsearch/            # CBIR 图像检索 + 关键帧视频检索
├── imagerecognition/         # NCC 匹配 / Hu 矩 / 视频跟踪
├── videorecognition/         # Haar 人脸检测 + 跟踪命中率 + 关键帧检索
│   └── doc/                  # 视频识别及物体人脸识别需求文档.pdf
└── doc/                      # 数字图像与视频处理.pdf（教材）
```

## 5. 构建与运行

```bash
# 环境：Android Studio + JDK 17 + NDK（CMake 工程）；中英文双语言
./gradlew :app:assembleDebug          # 构建 APK（约 75MB，含 OpenCV so 与演示视频）
./gradlew :videorecognition:testDebugUnitTest   # 运行模块单元测试
# 全部模块测试
./gradlew test
```

在 Android 设备（Android 8.0+，横屏体验最佳）上安装运行，主页选择功能卡片进入各实验界面。

## 6. 单元测试

算法层与 UI/Android 完全解耦，纯算法类直接 JVM 测试：

| 测试类 | 覆盖内容 |
|---|---|
| `FaceTrackMathTest`（33 例） | IoU（重合/相离/半叠/对称/包含）、时间平滑、均值下采样、二分最近帧、跟踪命中率统计 |
| `KeyframeFeaturesTest`（15 例） | 灰度直方图统计/L1 归一化/无符号量化、梯度方向直方图、综合相似度权重分解/正交退化/自比为 1 |
| `NccMatcherTest` / `ShapeRecognizerTest` / `VideoRecognitionPipelineTest` | NCC 匹配、Hu 矩形状识别、视频跟踪管线 |
| `ImageQualityMetricsTest` / `ImageDistortionsTest` / `VideoQualityPipelineTest` | PSNR/SSIM/熵、失真生成、视频评价管线 |

## 7. 需求与文档

- [videorecognition/doc/视频识别及物体人脸识别需求文档.pdf](videorecognition/doc/) —— 视频识别模块的用例（F-05 视频人脸检测与跟踪等）、核心类设计与开发计划；
- [doc/数字图像与视频处理.pdf](doc/) —— 教材，各模块算法均标注了对应章节（如 Haar 检测→第 11 章、综合相似度→第 10 章 10.5 节、跟踪命中率→「视频的图像识别处理」）。

## 8. 技术栈

Kotlin · C++ (JNI/CMake) · OpenCV 4.x · MediaCodec/MediaExtractor/MediaMuxer · MediaPlayer/TextureView · AndroidX (ViewModel/LiveData/ViewBinding) · Material Design · JUnit4
