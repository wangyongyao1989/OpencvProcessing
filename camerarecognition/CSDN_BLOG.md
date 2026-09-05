# Android 实时视觉感知实战：CameraX + OpenCV(C++/JNI) 实现多级联人脸检测与框选实物跟踪

> 在移动端做实时视觉，最常见的两个需求就是「检测人脸」和「跟踪任意物体」。本文基于一个可真机运行的 Android 工程 `camerarecognition` 模块，完整拆解：如何用 **CameraX ImageAnalysis** 采集 YUV 帧、如何在 **JNI/C++ 层**用 OpenCV 完成「多级联融合人脸检测」与「CamShift + 多维特征实物跟踪」、如何用 **ANativeWindow** 零拷贝渲染，以及工程上最容易踩坑的 **三个"Object"的坐标系一致性**问题。全部关键代码逐一解析，文末附运用场景与踩坑经验。

---

## 目录

1. [先看全景：模块架构与一帧数据的旅程](#一先看全景模块架构与一帧数据的旅程)
2. [采集层：CameraX ImageAnalysis 工程实现](#二采集层camerax-imageanalysis-工程实现)
3. [桥接层：轻量句柄式 JNI 设计](#三桥接层轻量句柄式-jni-设计)
4. [人脸检测跟踪：多级联融合方案](#四人脸检测跟踪多级联融合方案)
5. [实物跟踪：CamShift + 多维特征验证](#五实物跟踪camshift--多维特征验证)
6. [渲染层：ANativeWindow 零拷贝直绘](#六渲染层anativewindow-零拷贝直绘)
7. [坐标系一致性：三个"Object"的统一](#七坐标系一致性三个object的统一)
8. [运用场景](#八运用场景)
9. [工程踩坑与性能经验](#九工程踩坑与性能经验)
10. [总结](#十总结)

---

## 一、先看全景：模块架构与一帧数据的旅程

### 1.1 三层架构

`camerarecognition` 模块采用 **Kotlin(相机/UI) + JNI(桥接) + C++(OpenCV 算法)** 的经典三层架构，职责高度单一：

```
┌────────────────────────────────────────────────────────────┐
│  UI/编排层（Kotlin，app 模块）                              │
│  CameraRecognitionFragment   人脸检测页（开关预览/切摄像头） │
│  ObjectTrackFragment         实物跟踪页（状态/相似度/缩略图）│
│  ObjectSelectView            手势框选叠加层（只管"选"）      │
├────────────────────────────────────────────────────────────┤
│  采集/桥接层（Kotlin，camerarecognition 模块）              │
│  CameraHelper    CameraX ImageAnalysis：YUV→NV21 出帧      │
│  CameraFaceJni / ObjectTrackJni   external 声明 + so 加载  │
├────────────────────────────────────────────────────────────┤
│  算法层（C++，OpenCV 4）                                    │
│  FaceTracker          多级联融合人脸检测 + 时间跟踪          │
│  ObjectTrackerLogic   CamShift 跟踪 + 多维特征相似度验证     │
│  ANativeWindow        native 直绘预览帧（绕过 Bitmap/View） │
└────────────────────────────────────────────────────────────┘
```

### 1.2 一帧数据的完整旅程

以「框选实物跟踪」为例，一帧数据从传感器到屏幕要经过 8 步：

```
Camera2 传感器（YUV_420_888，传感器原始方向）
   │ ① ImageAnalysis 回调（分析线程）
   ▼
CameraHelper.yuv420ToNv21()          ② YUV→NV21 重排（native 只认 NV21）
   │ previewCallback(ByteArray)
   ▼
ObjectTrackJni.nativePostFrame()     ③ JNI 进入 C++（零拷贝读数组）
   ▼
decodeFrame()                        ④ NV21→RGBA + 镜像 + 旋转 →「方向校正显示图像」
   ▼
反向投影 + CamShift + 综合相似度      ⑤ 定位候选框 & 验证（防止跟丢/跟错）
   ▼
模板在线更新（EMA）/ 丢失重定位       ⑥ 自适应与兜底
   ▼
cv::rectangle/putText 画绿框         ⑦ 结果绘制在校正后的帧上
   ▼
ANativeWindow 直写 Surface 缓冲区    ⑧ 上屏（不经过 Bitmap）
```

**设计要点**：②~⑧ 全部在 C++ 侧完成，Java 层每帧只做一次 JNI 调用和一个 `ByteArray` 传递——这是保证 640×480@30fps 实时性的关键。

### 1.3 模块目录结构

```
camerarecognition/
├── CSDN_BLOG.md / README.md
├── build.gradle.kts                 # CameraX 三件套依赖 + jniLibs 打包
└── src/main/
    ├── AndroidManifest.xml          # CAMERA 权限声明
    ├── assets/                      # 6 个 Haar 级联模型（<5MB）
    ├── cpp/
    │   ├── CMakeLists.txt           # 链接预编译 libopencv_java4.so
    │   ├── CameraFaceDetector.cpp   # 人脸 JNI 层
    │   ├── FaceTracker.cpp/.h       # 多级联融合检测 + DetectionBasedTracker
    │   ├── ObjectTracker.cpp        # 实物跟踪 JNI 层
    │   ├── ObjectTrackerLogic.cpp/.h# CamShift + 特征验证核心逻辑
    │   ├── include/                 # OpenCV 4 头文件
    │   └── libs/{arm64-v8a,armeabi-v7a}/libopencv_java4.so
    └── java/com/wangyao/camerarecognition/
        ├── camera/CameraHelper.kt   # CameraX 采集封装
        └── jni/CameraFaceJni.kt / ObjectTrackJni.kt
```

---

## 二、采集层：CameraX ImageAnalysis 工程实现

### 2.1 为什么选 ImageAnalysis 而不是 Preview

CameraX 提供三个 UseCase：`Preview`（直接上屏，拿不到像素）、`ImageCapture`（拍照，慢）、`ImageAnalysis`（为机器视觉设计的帧流）。做检测/跟踪必须逐帧拿像素，`ImageAnalysis` 是唯一正解。模块已从废弃的 `android.hardware.Camera`（旧 Camera API）全面迁移到 **CameraX 1.6.1**。

### 2.2 相机绑定：分辨率、背压与生命周期

```kotlin
fun startPreview() {
    val generation = startGeneration.incrementAndGet()   // ① 启动代号
    val future = ProcessCameraProvider.getInstance(appContext)
    future.addListener({
        if (generation != startGeneration.get()) return@addListener  // ② 过期请求直接丢弃
        val provider = future.get()
        provider.unbindAll()

        // ③ 目标分辨率 640×480：检测实时性最好的平衡点
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(
                Size(WIDTH, HEIGHT),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)  // ④
            .build()
        analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
            try {
                frameWidth = image.width; frameHeight = image.height  // ⑤ 实际尺寸
                previewCallback?.invoke(yuv420ToNv21(image))
            } finally { image.close() }
        }
        val camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)  // ⑥
        sensorOrientation = camera.cameraInfo.sensorRotationDegrees  // ⑦ 与旧 API 同源
        facing = ...
        onCameraStarted?.invoke()
    }, ContextCompat.getMainExecutor(appContext))
}
```

逐点解析：

| # | 代码 | 为什么这样写 |
|---|---|---|
| ①② | `startGeneration` 代号 | CameraX 绑定是**异步**的。用户快速「切摄像头/关预览」时，旧的绑定回调可能晚于新请求到达，代号校验让过期回调直接失效，避免旧相机"复活" |
| ③ | 640×480 + `CLOSEST_LOWER_THEN_HIGHER` | 640×480 是检测精度与耗时的甜点；宁可降一档也保证帧率（`HIGHER` 档 1080p 会拖垮逐帧算法） |
| ④ | `STRATEGY_KEEP_ONLY_LATEST` | 消费者（C++ 算法）比生产者（传感器）慢时**自动丢旧帧**，永远处理最新帧——检测/跟踪场景要的是"现在"，不是"排队" |
| ⑤ | 记录 `frameWidth/Height` | 个别设备会给 632×480 这类邻近分辨率，**送检必须用实际值**而不是常量，否则 NV21 长度对不上、ROI 换算出错 |
| ⑥ | `bindToLifecycle` | 绑定到 Fragment 生命周期，页面 `onDestroy` 自动释放相机，告别手动 `release()` 泄漏时代 |
| ⑦ | `sensorRotationDegrees` | 与旧 API `Camera.CameraInfo.orientation` 同源，坐标语义无缝衔接（见第七节） |

### 2.3 YUV_420_888 → NV21：rowStride/pixelStride 的坑

CameraX 输出的是 **YUV_420_888**（逻辑格式），落到具体设备上是两种排布：**planar**（U、V 各自连续，常见 Camera2 后置）与 **semi-planar**（U、V 已交错，常见部分厂商/前置）。而 OpenCV 的 `COLOR_YUV2RGBA_NV21` 只认 **NV21**（Y 平面 + VU 交错平面）。

更麻烦的是硬件对齐：**每行末尾可能有 padding**（`rowStride > width`），U/V 的 `pixelStride` 还可能是 2（semi-planar 时 U、V 间隔存放）。所以转换必须逐像素按 stride 取数：

```kotlin
private fun yuv420ToNv21(image: ImageProxy): ByteArray {
    val width = image.width; val height = image.height
    val ySize = width * height
    if (nv21.size != ySize * 3 / 2) nv21 = ByteArray(ySize * 3 / 2)

    // Y 平面：pixelStride 恒为 1；rowStride==width 时整块拷贝（快路径）
    val yPlane = image.planes[0]
    if (yPlane.rowStride == width) {
        yPlane.buffer.get(nv21, 0, ySize)
    } else {
        for (row in 0 until height) {           // 慢路径：逐行跳过行尾 padding
            yPlane.buffer.position(row * yPlane.rowStride)
            yPlane.buffer.get(nv21, row * width, width)
        }
    }

    // U/V 平面 → NV21 要求 V 在前 U 在后交错；
    // 用绝对索引读取天然兼容 planar(pixelStride=1) 与 semi-planar(pixelStride=2)
    val u = image.planes[1]; val v = image.planes[2]
    var pos = ySize
    for (row in 0 until height / 2) {
        var uIdx = row * u.rowStride
        var vIdx = row * v.rowStride
        for (col in 0 until width / 2) {
            nv21[pos++] = v.buffer.get(vIdx)    // V 先
            nv21[pos++] = u.buffer.get(uIdx)    // U 后
            uIdx += u.pixelStride; vIdx += v.pixelStride
        }
    }
    return nv21                                  // 复用缓冲区，避免每帧分配触发 GC
}
```

**三个细节**：
- 输出缓冲区 `nv21` 是成员变量**复用**的——每帧分配 460KB 字节数组会引发剧烈 GC 抖动，这在分析线程独占的前提下是安全的；
- `duplicate()` 拿到的 buffer 不动原 position，`image.close()` 不受影响；
- 首帧会打一行 plane 参数日志（rowStride/pixelStride），真机排查排布问题时直接看 logcat。

### 2.4 对外契约：坐标系语义与旧 API 完全一致

`CameraHelper` 对上层暴露的核心字段刻意保持了 legacy 语义：

- `sensorOrientation` ≡ 旧 `Camera.CameraInfo.orientation`（后置通常 90，前置通常 270）；
- `facing` 沿用 0=后置 / 1=前置 的旧常量值；
- 帧内容为**传感器原始方向**（不旋转、不镜像）——与旧 API 预览帧一致。

这样上层 Fragment 的方向校正公式一行都不用改，坐标语义无缝迁移。

---

## 三、桥接层：轻量句柄式 JNI 设计

两个 JNI 桥（`CameraFaceJni`/`ObjectTrackJni`）采用同一套**句柄模式**：native 对象在 C++ 堆上创建，`nativeCreate` 返回指针作为 `Long` 句柄，之后所有调用都传句柄进来。

```kotlin
object ObjectTrackJni {
    init {
        System.loadLibrary("opencv_java4")            // 先加载 OpenCV（约 40MB 的 so）
        System.loadLibrary("camerarecognition_native") // 再加载本模块（DT_NEEDED 依赖前者）
    }
    external fun nativeCreate(): Long
    external fun nativeSelectObject(handle: Long, x: Int, y: Int, w: Int, h: Int)
    external fun nativePostFrame(handle: Long, data: ByteArray, w: Int, h: Int,
                                 rotation: Int, mirror: Boolean): FloatArray
    ...
}
```

C++ 侧帧提交的写法有一个关键点：

```cpp
jbyte *buf = env->GetByteArrayElements(data, nullptr);
int state = tracker->postFrame(reinterpret_cast<const uint8_t*>(buf),
                               w, h, rotation, mirror == JNI_TRUE, box, &sim);
env->ReleaseByteArrayElements(data, buf, JNI_ABORT);   // ★ 只读回拷，禁止写回
```

`JNI_ABORT` 表示「释放缓冲区但**不把修改写回** Java 数组」——native 只读帧数据，用 `JNI_COPY`/写回模式纯属浪费一次 460KB 拷贝。这个参数在「只读大数组」场景是标准优化。

**设计收益**：算法对象生命周期完全由 Java 侧显式控制（`onDestroy` 里 `nativeDestroy`），不依赖 GC；句柄是普通 Long，可以在任意线程传递；JNI 函数保持无状态，线程安全由 C++ 内部 `std::mutex` 保证。

---

## 四、人脸检测跟踪：多级联融合方案

### 4.1 Haar 级联原理速览

Haar 级联检测器 = **积分图**（任意矩形区域灰度和 O(1) 查表）+ **AdaBoost 级联分类器**（前几层用少量特征快速排除 90%+ 的非人脸窗口，越往后越严格）。优点是 CPU 友好、模型小（数百 KB）；缺点是单一模型对侧脸、暗光、遮挡敏感——这正是「多级联融合」要解决的问题。

### 4.2 适配器模式：接入 DetectionBasedTracker

OpenCV 自带的 `DetectionBasedTracker` 是一个「检测 + 卡尔曼式时间平滑」的跟踪框架，但它要求注入一个 `IDetector`。模块用 `MultiCascadeAdapter` 实现该接口，把「多模型融合」封装在适配器内部，**框架的时间跟踪能力零成本复用**：

```cpp
bool FaceTracker::init(const std::vector<std::string> &facePaths,
                       const std::vector<std::string> &featurePaths) {
    // 主检测器：全帧扫描模式（全部模型 + 特征验证 + 半分辨率加速）
    auto mainDetector = cv::makePtr<MultiCascadeAdapter>(facePaths, featurePaths, false);
    // 跟踪检测器：轻量模式（只留 1 个正脸模型 + 侧脸，不做验证、不降采样）
    auto trackingDetector = cv::makePtr<MultiCascadeAdapter>(facePaths, featurePaths, true);
    ...
    tracker_ = new DetectionBasedTracker(mainDetector, trackingDetector, params);
    tracker_->run();
}
```

注意两个适配器用**同一份模型路径、不同模式**构建：

- **主检测器（`trackMode=false`）**：`DetectionBasedTracker` 周期性全帧扫描时调用。加载全部 3 个人脸模型 + 4 个特征模型，帧先 `resize` 到 0.5 倍再扫（面积降 75%），检测结果坐标再映射回原尺寸。
- **跟踪检测器（`trackMode=true`）**：在已有目标邻域内做小范围重检。只保留一个正脸模型（构造函数里 `frontalKept` 标志保证），跳过特征验证——**每一帧都跑的话，验证的耗时会让帧率崩掉**，这是精度与速度的工程取舍。

### 4.3 多模型检测 + NMS 聚类

不同模型对同一张脸会各自出一个框，必须合并。模块用的是**贪心聚类 + IoU 阈值**的轻量 NMS：

```cpp
constexpr float MERGE_IOU = 0.30f;   // 不同模型对同一张脸的框 IoU 通常 > 0.4，0.3 留余量

std::sort(raw.begin(), raw.end(),            // ① 大框优先（更可能是完整人脸）
         [](const RawBox &a, const RawBox &b){ return a.r.area() > b.r.area(); });
std::vector<std::vector<RawBox>> clusters;
std::vector<cv::Rect> reps;                  // 每簇的"代表框"
for (const RawBox &b : raw) {
    int best = -1; float bestIoU = 0.f;
    for (size_t i = 0; i < clusters.size(); i++) {   // ② 找与当前框 IoU 最大的簇
        float v = boxIou(b.r, reps[i]);
        if (v > bestIoU) { bestIoU = v; best = (int)i; }
    }
    if (best >= 0 && bestIoU > MERGE_IOU) {  // ③ 入簇并重算簇内平均框
        clusters[best].push_back(b);
        reps[best] = averageBox(clusters[best]);
    } else {
        clusters.push_back({b}); reps.push_back(b.r);  // ④ 新开一簇
    }
}
```

相比标准 NMS「保留最高分、抑制其余」，这里取**簇内平均框**——多模型各自偏一点，平均后反而更贴近真值；而且 `RawBox` 带了 `fromProfile` 来源标记，后面验证阶段要用。

### 4.4 侧脸镜像补扫

`profileface` 模型只训练了**左脸**（或右脸）。模块的解法很直白：把图水平翻转后再检测一遍，命中的框坐标 `r.x = w - r.x - r.width` 映射回来，两个方向的侧脸都能抓到：

```cpp
if (fc.isProfile) {
    cv::Mat flipped; cv::flip(work, flipped, 1);        // 镜像补扫另一侧
    fc.classifier.detectMultiScale(flipped, found, ...);
    const int w = work.cols;
    for (cv::Rect r : found) {
        r.x = w - r.x - r.width;                        // 坐标反映射
        raw.push_back({r, true});
    }
}
```

### 4.5 面部特征验证：用"器官分布"杀误检

正脸模型偶尔会把纹理复杂的背景误判成人脸。人在人脸框内的分布是有先验的——**眼在上部、鼻在中部、嘴在下部**——分别在对应子区域内用小级联找器官，找不到就否决这个候选框：

```cpp
bool MultiCascadeAdapter::verifyFace(const cv::Mat &gray, const cv::Rect &r) {
    // 眼部 ROI：上 60%、居中 80% 宽——用经验比例圈定
    cv::Rect eyeRoi(roi.x + roi.width*0.1, roi.y, roi.width*0.8, roi.height*0.6);
    eye_->detectMultiScale(gray(eyeRoi), hits, 1.1, 2, 0, fmin);
    if (!hits.empty()) return true;              // 命中眼睛 → 直接通过

    // 未命中眼睛（可能戴眼镜/闭眼），退而求其次：鼻 AND 嘴 同时存在
    bool hasNose = ..., hasMouth = ...;
    return hasNose && hasMouth;
}
```

两个工程细节：

- **验证策略是「眼睛 OR（鼻子 AND 嘴）」**：戴眼镜的人眼模型可能失效，但鼻嘴通常还在——比"必须命中眼睛"召回率高得多；
- **侧脸框跳过验证**（`fromProfile` 标记）：侧脸本来就只能看到半张脸，器官分布先验不成立，强验证会把真侧脸杀掉。

### 4.6 CLAHE：光照不均的救星

逆光、侧光下人脸半明半暗，灰度直方图失衡会显著拉低 Haar 检测召回。每帧检测前做一次 **CLAHE（限制对比度自适应直方图均衡化）**，它按 8×8 网格分块均衡、再裁剪直方图峰值抑制噪声放大，比全局均衡化自然得多：

```cpp
clahe_ = cv::createCLAHE(2.0, cv::Size(8, 8));   // clipLimit=2.0, 8×8 tiles
...
cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
clahe_->apply(gray, gray);                        // 检测前必做
tracker_->process(gray);
```

### 4.7 帧处理管线全貌

`FaceTracker::postFrame` 是人脸链路的总入口，顺序极为讲究：

```cpp
int FaceTracker::postFrame(const uint8_t *data, int w, int h,
                           int rotation, bool mirror) {
    cv::Mat src(h + h/2, w, CV_8UC1, const_cast<uint8_t*>(data)); // 包一层，零拷贝
    cv::cvtColor(src, src, cv::COLOR_YUV2RGBA_NV21);  // ① NV21 → RGBA
    if (mirror) cv::flip(src, src, 1);                 // ② 前置镜像（自拍语义）
    switch (rotation) { /* ③ 顺时针旋转 90/180/270 */ }

    cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);      // ④ 转灰度
    clahe_->apply(gray, gray);                         // ⑤ 光照均衡
    tracker_->process(gray);                           // ⑥ 检测+时间跟踪
    tracker_->getObjects(faces);
    for (const auto &face : faces)
        cv::rectangle(src, face, cv::Scalar(0, 0, 255), 3);  // ⑦ 红框画在 RGBA 上
    drawToWindow(src);                                 // ⑧ 上屏
    return faces.size();
}
```

**为什么先镜像再旋转**？前置摄像头传感器帧是「别人眼中的你」，先 `flip` 得到镜像（自拍语义），再按 `sensorOrientation - displayRotation` 旋转对齐显示方向——顺序反了会导致镜像方向错误（文字左右颠倒）。

---

## 五、实物跟踪：CamShift + 多维特征验证

### 5.1 为什么纯 CamShift 不够

CamShift（Continuously Adaptive Mean-Shift）用**颜色（HUE）直方图反向投影**做概率图，在概率图上迭代找密度峰。它快、对形变鲁棒，但有致命弱点：

- **纯色背景同色干扰**：白墙前跟踪白杯子，反向投影满屏高亮，框直接漂走；
- **无验证机制**：漂了也不知道，框会在背景上"自信地"锁死错误目标；
- **光照剧变**：HUE 直方图失配，投影概率图塌陷。

本模块的方案是「**CamShift 管定位，多维特征管验证，在线更新管适应**」三权分立。

### 5.2 特征体系：一个结构体，三种互补特征

```cpp
struct Feature {
    static constexpr int GRAY_BINS  = 64;   // 灰度直方图（颜色/明暗分布）
    static constexpr int ORI_BINS   = 18;   // Sobel 梯度方向直方图（纹理结构，每 bin 10°）
    static constexpr int LBP_BINS   = 256;  // 局部二值模式（微观纹理模式）

    float color[GRAY_BINS]  = {0};
    float texture[ORI_BINS] = {0};
    float lbp[LBP_BINS]     = {0};
};
```

三种特征互补性极强：

| 特征 | 抓什么 | 抗什么 | 弱点 |
|---|---|---|---|
| 64 维灰度直方图 | 明暗分布 | 旋转、平移、形变 | 纯色目标区分度低 |
| 18 维 Sobel 方向直方图 | 边缘结构走向 | 光照整体变化 | 平滑目标无梯度 |
| 256 维 LBP | 局部微观纹理模式 | 全局光照变化 | 对噪声略敏感 |

**Sobel 方向直方图**的实现有个巧思——只统计**幅值高于均值**的像素（强边缘），弱纹理/噪声直接跳过，直方图信噪比大幅提升：

```cpp
// 手写 Sobel：直接在裸指针上算，比 cv::Sobel + 角度分解少两次中间 Mat 分配
const float gx = (row[x-w+1] + 2*row[x+1] + row[x+w+1]) -
                 (row[x-w-1] + 2*row[x-1] + row[x-w-1]);
const float gy = (row[x+w-1] + 2*row[x+w] + row[x+w+1]) -
                 (row[x-w-1] + 2*row[x-w] + row[x-w+1]);
...
if (m > meanMag && m > 0.f) {           // 只统计强边缘
    float deg = ori[i] * 57.29577951f;  // atan2 结果弧度→角度
    if (deg < 0) deg += 180.f;          // 方向无极性：180° 折叠（0° 与 180° 同方向）
    hist[(int)(deg / 180.f * ORI_BINS)] += m;   // 幅值加权投票
}
```

### 5.3 综合相似度：加权组合

```cpp
float comprehensiveSimilarity(const Feature &a, const Feature &b) {
    return WEIGHT_COLOR * histogramIntersection(a.color,  b.color,  Feature::GRAY_BINS) +   // 0.4
           WEIGHT_SOBEL * cosine(               a.texture, b.texture, Feature::ORI_BINS)  +  // 0.3
           WEIGHT_LBP  * histogramIntersection(a.lbp,     b.lbp,     Feature::LBP_BINS);    // 0.3
}
```

- **直方图相交**（`Σ min(a[i], b[i])`，归一化后 ∈ [0,1]）：对直方图类特征最稳健的度量，天然抗噪声；
- **余弦相似度**（方向无关，只看分布形状）：对幅值整体缩放不敏感——纹理整体变强/变弱不影响方向分布。

### 5.4 跟踪状态机

```
              selectObject(x,y,w,h)         首帧 buildTemplate
  ┌──────┐ ──────────────────────────► ┌───────┐ ────────────► ┌──────────┐
  │ IDLE │                             │ ARMED │               │ TRACKING │
  └──────┘                             └───────┘               └──────────┘
      ▲                                   │ sim<0.35 连续15帧      ▲    │
      │ resetTracking                    ▼                        │    │ sim<0.35
      │                              ┌──────┐   每5帧 minMaxLoc    │    │ 连续15帧
      └──────────────────────────── │ LOST │ ───全局重定位命中─────┘    ▼
                                     └──────┘                          (回 LOST)
```

- `IDLE`：无模板，只渲染预览帧；
- `ARMED`：用户刚框选，`pendingRoi_` 待下一帧处理（框选发生在 UI 线程，帧在分析线程，用标记解耦时序）；
- `TRACKING`：正常跟踪，绿框 + 相似度标签；
- `LOST`：相似度连续 15 帧低于 0.35，画红框警示，同时每 5 帧做一次全局重定位。

**为什么要连续 15 帧才判丢**？单帧相似度抖动很常见（目标快速运动时 CamShift 候选框跟不上、瞬间遮挡），立即判丢会让跟踪"忽好忽坏"。15 帧缓冲约 0.5s，兼顾响应速度与稳定性。

### 5.5 核心跟踪循环逐行解析

```cpp
int ObjectTrackerLogic::trackLocked(...) {
    cv::Mat frame = decodeFrame(data, w, h, rotation, mirror);  // ① 方向校正
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    cv::cvtColor(frame, hsv,  cv::COLOR_RGB2HSV);
    // ② 低饱和/过暗区域掩码：S∈[30,255]、V∈[30,255] 之外的（近黑/近白/灰）
    //    像素不参与反向投影——这类像素 HUE 无意义且噪声大
    cv::inRange(hsv, cv::Scalar(0,30,30), cv::Scalar(180,255,255), mask);

    if (armed_) {                                   // ③ 框选后第一帧：建模板
        buildTemplate(planes[0], mask, gray, roi);
        armed_ = false; hasTemplate_ = true;
        trackWindow_ = roi;
        templateThumb_ = frame(roi).clone();        //    留作 UI 核验缩略图
    }

    // ④ HUE 反向投影 → 概率图，与 mask 相与滤除无效像素
    cv::calcBackProject(&planes[0], 1, 0, hueHist_, prob, ranges, 1, true);
    prob.convertTo(prob, CV_8U);
    cv::bitwise_and(prob, mask, prob);

    // ⑤ LOST 状态下每 5 帧全局重定位（详见 5.6）
    if (state_ == STATE_LOST && frameIdx_ % 5 == 0) { ... minMaxLoc ... }

    // ⑥ CamShift 迭代收敛（10 次迭代 / 精度 1 为止）
    cv::TermCriteria criteria(cv::TermCriteria::EPS | cv::TermCriteria::COUNT, 10, 1);
    cv::RotatedRect trackBox = cv::CamShift(prob, trackWindow_, criteria);
    cv::Rect cand = trackBox.boundingRect() & cv::Rect(0, 0, frame.cols, frame.rows);

    // ⑦ 相似度验证：CamShift 找到的框，特征对不对得上？
    grayHistogram(gray(cand), candFeat.color);
    textureHistogram(gray(cand), candFeat.texture);
    lbpHistogram(gray(cand), candFeat.lbp);
    float sim = comprehensiveSimilarity(template_, candFeat);

    // ⑧ 三分支：丢失计数 / 在线更新 / 正常输出
    if (sim < SIM_LOST) {                    // < 0.35：可能漂移
        if (++lostCount_ > LOST_FRAMES) { state_ = STATE_LOST; ... }  // 连续15帧 → 判丢
    } else {
        lostCount_ = 0; state_ = STATE_TRACKING;
        if (sim > SIM_ADAPT && frameIdx_ % ADAPT_INTERVAL == 0)      // >0.80 且每5帧
            adaptTemplate(candFeat, planes[0], mask, cand);          //    在线更新模板
        if (frameIdx_ % THUMB_INTERVAL == 0)
            trackedThumb_ = frame(cand).clone();     //    跟踪框缩略图（UI 核验用）
    }
    cv::rectangle(frame, cand, cv::Scalar(0, 255, 0), 3);            // ⑨ 绿框
    cv::putText(frame, "sim xx%", ...);
    ...
}
```

模板构建同样有一处防御性细节——**掩码像素不足时放弃掩码**：

```cpp
cv::Mat roiMask = mask(roi);
// 框选纯黑/纯白目标时，有效像素可能不足 1/10，此时 mask 几乎全零，
// calcHist 会得到空直方图 → 直接退化用全 ROI 统计
if (cv::countNonZero(roiMask) < roi.area() / 10) roiMask = cv::Mat();
cv::calcHist(&roiHue, 1, chans, roiMask, hueHist_, 1, bins, ranges);
cv::normalize(hueHist_, hueHist_, 0, 255, cv::NORM_MINMAX);   // 归一化到 0~255（反向投影动态范围最大化）
```

### 5.6 丢失恢复：minMaxLoc 全局重定位

目标被遮挡或移出框后，CamShift 在旧位置邻域迭代注定失败。模块的兜底策略是：LOST 期间**每 5 帧在整幅反向投影图上找全局最大值**，概率峰值超过 128（即模板 HUE 在该处高度匹配）时，把跟踪窗口平移过去重新进入 CamShift：

```cpp
if (state_ == STATE_LOST && frameIdx_ % 5 == 0) {
    double maxVal; cv::Point maxLoc;
    cv::minMaxLoc(prob, nullptr, &maxVal, nullptr, &maxLoc);
    if (maxVal > 128) {   // 峰值够高 → 大概率是目标重新出现
        trackWindow_ = cv::Rect(maxLoc.x - prevWin.width/2,
                                maxLoc.y - prevWin.height/2,
                                prevWin.width, prevWin.height);
        trackWindow_ &= cv::Rect(0, 0, frame.cols, frame.rows);
    }
}
```

为什么每 5 帧而不是每帧？`minMaxLoc` 是全图扫描，每帧都跑浪费算力；且目标重现初期概率峰可能不稳，稍等几帧更可靠。

### 5.7 在线模板更新：EMA 平滑，防漂移与防污染的平衡

环境光照渐变、目标自身姿态渐变会让初始模板逐渐"过时"。模块在 `sim > 0.80`（非常确信是同一目标）时，每 5 帧用**指数移动平均（EMA）**把当前观测融入模板：

```cpp
void ObjectTrackerLogic::adaptTemplate(const Feature &candFeat, ...) {
    // 三组特征直方图全部按 alpha=0.2 融合：新模板 = 0.8×旧 + 0.2×当前观测
    for (int i = 0; i < Feature::GRAY_BINS; i++)
        template_.color[i] = (1.f - ADAPT_ALPHA) * template_.color[i]
                           + ADAPT_ALPHA * candFeat.color[i];
    ... // texture、lbp 同理
    hueHist_ = (1.f - ADAPT_ALPHA) * hueHist_ + ADAPT_ALPHA * cur;  // HUE 直方图同样融合
    cv::normalize(hueHist_, hueHist_, 0, 255, cv::NORM_MINMAX);
}
```

三个参数的权衡逻辑：

- **`SIM_ADAPT = 0.80` 门槛**：只在"高度确信"时更新。若在相似度 0.5（可能已轻微漂移）时也更新，误差会被滚雪球式写进模板，最终"漂到哪跟到哪"（模板污染）；
- **`ADAPT_ALPHA = 0.2` 小步长**：单次更新只动 20%，渐进适应光照变化，不会突变；
- **每 5 帧一次**：降低累计漂移速率。

这套参数的本质是：**宁可更新慢一点，也不能让模板被污染**——污染后只能重新框选，而慢更新最多是相似度读数变低，用户感知很小。

### 5.8 结果核验缩略图：让"跟对了没"肉眼可见

模块把「框选模板」和「实时跟踪框内画面」两幅小图经 JNI 导出到 UI 并排显示（`nativeGetTemplateThumb` / `nativeGetTrackedThumb`），格式为 `[w(4B)][h(4B)][RGBA]` 小端字节流：

```kotlin
private fun decodeThumb(data: ByteArray): Bitmap? {
    if (data.size < 8) return null
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val w = buf.int; val h = buf.int
    if (w <= 0 || h <= 0 || buf.remaining() < w * h * 4) return null
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .apply { copyPixelsFromBuffer(buf) }
}
```

这是排查"三个 Object 是否一致"最直观的手段——两幅小图画面对得上，就是同一实物；对不上，立即能发现跟错了。

---

## 六、渲染层：ANativeWindow 零拷贝直绘

检测结果如果走「`Bitmap` → `ImageView.setImageBitmap` → 主线程」路线，每帧要跨 JNI 拷贝一次 + 主线程排队，UI 线程还会被高频 `invalidate` 卡住。模块直接用 **ANativeWindow** 把 C++ 里画好框的 `cv::Mat` 写进 SurfaceView 的缓冲区：

```cpp
void ObjectTrackerLogic::drawToWindow(const cv::Mat &src) {
    ANativeWindow_setBuffersGeometry(window_, src.cols, src.rows, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) { ... return; }

    int srcLineSize = src.cols * 4;
    int dstLineSize = buffer.stride * 4;        // ★ stride：GPU 对齐后的行宽，可能 > 图像宽
    uint8_t *dstData = static_cast<uint8_t*>(buffer.bits);
    for (int i = 0; i < buffer.height; ++i)
        memcpy(dstData + i * dstLineSize,       // 逐行拷贝，目标按 stride 步进
               src.data + i * srcLineSize, srcLineSize);
    ANativeWindow_unlockAndPost(window_);       // 提交上屏（VSync 时机合成）
}
```

**`buffer.stride` 是这里唯一的坑**：Surface 缓冲区的行宽会按硬件对齐补齐（比如 640 宽的图 stride 可能是 640 或 768），按 `width*4` 步进写会导致画面出现斜条纹。必须按 `stride` 步进、按 `srcLineSize` 拷贝。

整条渲染链路（画框 → 上屏）全程不回 Java 层，分析线程一气呵成。

---

## 七、坐标系一致性：三个"Object"的统一

这是本模块工程上最关键的一环，也是实时跟踪最容易出错的地方。页面上存在三个"Object"：

1. **视频中的 Object**：预览画面里肉眼看到的目标；
2. **框选的 Object**：手指在屏幕上拖出的绿色选框；
3. **自动跟踪的 Object**：算法输出的绿框 + 缩略图。

三者必须落在**同一个坐标系**——本模块定义为「**方向校正后的显示图像坐标系**」，即 `decodeFrame()` 处理完（镜像 + 旋转）的 RGBA 帧。链路如下：

```
                    ┌── ① 传感器帧（原始方向，w×h）
                    │        │ decodeFrame(): NV21→RGBA + mirror + rotate
                    ▼
        ┌──── 方向校正后的显示图像（W×H）────┐
        │                                    │
   ②手势框选（View 坐标）                ③CamShift 绿框
   线性换算到图像坐标                    直接就在该坐标系
   （90°/270° 时宽高互换）                （native 内部闭环）
```

Java 侧的换算代码（`ObjectTrackFragment.onObjectSelected`）：

```kotlin
val rotation = frameRotationDegrees(helper)
// 校正角为 90°/270° 时，显示图像相对传感器帧宽高互换
val imgW: Int; val imgH: Int
if (rotation == 90 || rotation == 270) { imgW = helper.frameHeight; imgH = helper.frameWidth }
else                                   { imgW = helper.frameWidth;  imgH = helper.frameHeight }

val vw = binding.objectSelectView.width.toFloat()    // 叠加层尺寸
val vh = binding.objectSelectView.height.toFloat()
// 线性换算（叠加层与 SurfaceView 在同一 wrapper 内 match_parent，区域严格重合）
val x = (rect.left / vw * imgW).roundToInt()
val y = (rect.top  / vh * imgH).roundToInt()
val w = (rect.width()  / vw * imgW).roundToInt()
val h = (rect.height() / vh * imgH).roundToInt()
ObjectTrackJni.nativeSelectObject(trackerHandle, x, y, w, h)   // 进入同一坐标系
```

而 `rotation` 的计算公式与旧 API 时代完全一致：

```kotlin
private fun frameRotationDegrees(helper: CameraHelper): Int {
    val d = when (displayRotation()) {   // 横屏应用通常 ROTATION_0 → 0
        Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }
    return if (helper.facing == CameraHelper.FACING_FRONT) {
        (helper.sensorOrientation - d + 540) % 360   // 前置：附加 180°（镜像等效）
    } else {
        (helper.sensorOrientation - d + 360) % 360
    }
}
```

**保证一致的四个锚点**：

| 锚点 | 保障机制 |
|---|---|
| 布局 | `SurfaceView` 与 `ObjectSelectView` 在同一个 `preview_wrapper` 里都是 `match_parent`，View 坐标与渲染画面严格共域 |
| 帧尺寸 | ROI 换算用 `helper.frameWidth/Height`（实际值），native 侧 `nativeSelectObject` 的 ROI 与渲染帧同坐标系（JNI 契约注释明确约定） |
| 方向 | 两条链路（框选换算、帧解码）用同一个 `frameRotationDegrees()` 结果，且 90°/270° 时宽高互换逻辑对称 |
| 核验 | 模板缩略图 vs 跟踪框缩略图并排显示 + `CR_ObjectTrack` / `CR_ObjectTracker` 日志输出 ROI/框坐标，肉眼与日志双通道验证 |

---

## 八、运用场景

这套「检测 + 跟踪 + 坐标一致性」能力可以直接落地到多个业务方向：

**1. 智能相机 / 拍摄辅助**
人脸检测驱动「自动对焦、自动曝光、人脸优先构图提示」；框选跟踪可做「运动目标追焦」——长焦拍鸟/拍球赛时框住主体，算法持续输出目标位置驱动对焦马达。

**2. AR 增强现实底座**
AR 叠加的前提是"知道目标在屏幕哪个位置"。本模块输出的跟踪框（显示图像坐标系）可直接换算为 ARCore/OpenGL 的锚点坐标——在跟踪的实物上叠加说明文字、3D 模型、虚拟按钮。

**3. 工业质检 / 产线监控**
传送带上的零部件用框选方式"示教"一次，后续帧自动跟踪其位置；结合尺寸测量（跟踪框宽高）做在线公差筛查。LBP + Sobel 特征对金属反光的鲁棒性优于纯颜色方案。

**4. 智慧零售 / 无人货架**
顾客拿起商品的动作 = 框选目标在连续帧中的轨迹；跟踪框与商品库特征比对（模块的 `Feature` 体系可直接扩展为检索特征），实现"拿了就走"的结算预判。

**5. 无障碍辅助**
视障用户对准摄像头问"这是什么"——框选跟踪保证关注目标始终在画面内，检测/识别算法只需处理跟踪框内 ROI，省算力且不被背景干扰。

**6. 教育与科研**
本模块本身就是「数字图像处理」课程的一站式实验台：Haar 级联（检测）、CLAHE（增强）、HSV 反向投影与 CamShift（分割与跟踪）、直方图相交/余弦相似度（检索度量）——每个算法都可以在真机上实时观察效果，右栏面板附原理说明。

---

## 九、工程踩坑与性能经验

**1. OpenCV 4 的严格校验：旧模型加载即崩溃**
教材推荐的 `haarcascade_mcs_nose/mouth.xml` 是 OpenCV 2 时代的旧格式，特征矩形超出训练窗口，OpenCV 4 加载时直接抛异常（线上崩溃根因）。解法：换成验证过可正常加载的等价模型；启动时清理历史遗留的坏模型文件。

**2. CameraX YUV 的 stride 坑**
`rowStride != width`、U/V `pixelStride == 2` 在不同机型上都真实存在。按绝对索引逐像素取数（2.3 节）是唯一稳妥写法；首帧打印 plane 参数日志，新机型接入一眼定位。

**3. 背压策略选错 = 越追越滞后**
若用 `STRATEGY_BLOCK_PRODUCER`（排队），算法越慢积压越多，跟踪框滞后越来越大。`KEEP_ONLY_LATEST` 丢旧保新，检测/跟踪类业务永远选它。

**4. 相似度阈值不是拍脑袋**
`SIM_LOST=0.35`（低于判丢）、`SIM_ADAPT=0.80`（高于更新）之间的 0.45 区间是"死区"——既不更新模板也不判丢，只正常输出。这个死区是防止模板污染的关键缓冲：若更新门槛降到 0.5，轻微漂移时框住的其实是"目标+背景"，融合几次模板就废了。

**5. 分析线程独占复用缓冲区**
`yuv420ToNv21` 复用成员数组的前提是**单线程分析器**（`newSingleThreadExecutor`）；若换成多线程池，复用缓冲区会数据竞争。保持单线程 + `KEEP_ONLY_LATEST`，帧处理天然串行化。

**6. Surface 缓冲区 stride 不可假设**
ANativeWindow 渲染必须按 `buffer.stride` 步进写行（第六节），按图像宽度步进在某些 GPU 上会出现斜条纹/绿边。

**7. Android Studio 旧缓冲区覆盖磁盘文件**
多人/多工具协作开发时，IDE 编辑器里打开的旧版本文件可能在空闲自动保存时覆盖磁盘上的新代码。修改后若"编译结果与代码不符"，先 `File → Reload All from Disk` 再排查。

---

## 十、总结

本文完整拆解了 `camerarecognition` 模块的实时视觉管线：

- **采集层**：CameraX ImageAnalysis（640×480、KEEP_ONLY_LATEST、异步绑定代号码），YUV_420_888→NV21 兼容两种排布与 stride 对齐；
- **人脸链路**：多级联融合（正脸双模型并集 + 侧脸镜像补扫）+ 贪心 NMS 聚类 + 眼/鼻/嘴分区验证 + CLAHE 光照均衡，适配进 `DetectionBasedTracker` 获得时间平滑；
- **实物链路**：CamShift 定位 + 「64 维灰度直方图 + 18 维 Sobel 方向直方图 + 256 维 LBP」综合相似度验证 + EMA 在线模板更新 + minMaxLoc 丢失重定位，状态机管理全生命周期；
- **渲染层**：ANativeWindow 直写 Surface，零 Bitmap、不占 UI 线程；
- **工程关键**：三个"Object"统一到「方向校正显示图像坐标系」，布局共域 + 实际帧尺寸 + 对称旋转换算 + 缩略图核验四重保障。

相比深度学习方案，这套传统 CV 管线的优势是：**无模型训练成本、单帧毫秒级、离线可用、可解释性强**（相似度数值直接反映跟踪质量）——在中小目标、算力受限、需要快速落地的场景下，依然是性价比极高的选择。

---

*本文基于开源工程 [OpencvProcessing](https://github.com/wangyongyao1989/OpencvProcessing) 的 `camerarecognition` 模块整理，全部代码可真机运行验证。*
