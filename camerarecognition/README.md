# camerarecognition 模块

## 简介

`camerarecognition` 是一个基于 **CameraX + OpenCV 4 (C++/JNI)** 的 Android 实时相机识别库，提供两大开箱即用的能力：

1. **实时人脸检测与跟踪** —— 多 Haar 级联融合检测 + 面部特征验证 + 时间平滑跟踪；
2. **框选实物实时跟踪** —— 手势框选任意目标，CamShift 定位 + 多维特征相似度验证 + 模板在线自适应。

整条算法链路（解码 → 方向校正 → 检测/跟踪 → 绘制 → 渲染）全部在 native 层完成，通过 `ANativeWindow` 直写 Surface 缓冲区上屏，Java 层每帧仅一次 JNI 调用，640×480 下可实时运行。

## 功能特性

### 实时人脸检测跟踪
- **多级联融合**：正脸双模型（`frontalface_default` + `frontalface_alt2`）并集检测，侧脸模型 `profileface` 镜像补扫两个方向；
- **NMS 聚类**：贪心 IoU 聚类（阈值 0.30）合并多模型输出，簇内取平均框；
- **特征验证**：眼部 ROI（上 60%）命中眼/眼镜级联，或鼻 + 嘴同时命中，候选框才放行——显著降低误检；
- **双速检测器**：主检测器（半分辨率全帧扫描 + 验证）与跟踪检测器（原分辨率邻域重检、单模型免验证）分别注入 `DetectionBasedTracker`，兼顾精度与帧率；
- **CLAHE 光照均衡**：检测前限制对比度自适应均衡，改善逆光/侧光下的召回。

### 框选实物实时跟踪
- **手势框选**：叠加层拖动选框（最小 32px 过滤误触），View 坐标线性换算为图像坐标 ROI；
- **CamShift 定位**：HUE 32 维直方图反向投影（HSV 掩码滤除低饱和/过暗像素）+ CamShift 迭代收敛；
- **多维特征验证**：64 维灰度直方图 + 18 维 Sobel 梯度方向直方图（强边缘幅值加权）+ 256 维 LBP 直方图，综合相似度 `0.4×直方图相交 + 0.3×余弦 + 0.3×直方图相交`；
- **状态机**：`IDLE → ARMED → TRACKING ⇄ LOST`，相似度 < 0.35 连续 15 帧判丢；
- **丢失重定位**：LOST 期间每 5 帧在反向投影图上 `minMaxLoc` 全局寻峰（峰值 > 128）自动重锁目标；
- **模板在线更新**：相似度 > 0.80 时每 5 帧按 EMA（α=0.2）融合当前观测，适应光照/姿态渐变，同时以高门槛防止模板污染；
- **核验缩略图**：「框选模板」与「实时跟踪框」两幅小图经 JNI 导出，UI 并排显示，肉眼验证三者（视频/框选/跟踪）是否同一实物。

## 架构设计

```
┌──────────────────────────────────────────────────────┐
│ UI/编排层（app 模块）                                 │
│  CameraRecognitionFragment  人脸检测页                 │
│  ObjectTrackFragment        实物跟踪页                 │
│  ObjectSelectView           手势框选叠加层             │
├──────────────────────────────────────────────────────┤
│ 采集/桥接层（本模块 Kotlin）                          │
│  CameraHelper    CameraX ImageAnalysis，YUV→NV21 出帧 │
│  CameraFaceJni / ObjectTrackJni   JNI 桥（句柄式）     │
├──────────────────────────────────────────────────────┤
│ 算法层（本模块 C++，OpenCV 4）                        │
│  FaceTracker          多级联融合 + DetectionBasedTracker│
│  ObjectTrackerLogic   CamShift + 特征验证状态机        │
│  ANativeWindow        native 直绘上屏                 │
└──────────────────────────────────────────────────────┘
```

一帧数据流：`ImageAnalysis(YUV_420_888) → yuv420ToNv21 → JNI → decodeFrame(NV21→RGBA+镜像+旋转) → 检测/跟踪(方向校正显示图像坐标系) → 画框 → ANativeWindow 上屏`。

### 目录结构

```
camerarecognition/src/main/
├── AndroidManifest.xml            # CAMERA 权限
├── assets/                        # 6 个 Haar 级联模型（约 4MB）
│   ├── haarcascade_frontalface_default.xml / _alt2.xml
│   ├── haarcascade_profileface.xml
│   └── haarcascade_eye.xml / _nose.xml / _mouth.xml
├── cpp/
│   ├── CMakeLists.txt             # 链接预编译 libopencv_java4.so
│   ├── CameraFaceDetector.cpp     # 人脸 JNI 实现
│   ├── FaceTracker.cpp/.h         # MultiCascadeAdapter + FaceTracker
│   ├── ObjectTracker.cpp          # 实物跟踪 JNI 实现
│   ├── ObjectTrackerLogic.cpp/.h  # CamShift + 特征验证核心
│   ├── include/                   # OpenCV 4 头文件
│   └── libs/<abi>/libopencv_java4.so   # arm64-v8a / armeabi-v7a
└── java/com/wangyao/camerarecognition/
    ├── camera/CameraHelper.kt     # CameraX 采集封装
    └── jni/CameraFaceJni.kt / ObjectTrackJni.kt
```

## 快速开始

### 1. 添加依赖

```kotlin
// 模块 build.gradle.kts
dependencies {
    implementation(project(":camerarecognition"))
}
```

模块自身依赖（已内置）：CameraX（core/camera2/lifecycle 1.6.1）+ OpenCV 预编译 so（经 `jniLibs` 打包）。

### 2. 声明权限

模块 Manifest 已声明 `<uses-permission android:name="android.permission.CAMERA"/>`，宿主运行时仍需动态申请（参考 `CameraRecognitionFragment`）。

### 3. 初始化与帧提交（人脸检测示例）

```kotlin
// ① 创建跟踪器（模型从 assets 拷到私有目录后传路径）
trackerHandle = CameraFaceJni.nativeCreate(facePaths, featurePaths)

// ② 绑定渲染 Surface（SurfaceView 的 holder）
CameraFaceJni.nativeSetSurface(trackerHandle, holder.surface)

// ③ CameraX 出帧回调中提交 NV21 数据
cameraHelper = CameraHelper(requireContext(), this).apply {
    previewCallback = { data ->
        CameraFaceJni.nativePostFrame(
            trackerHandle, data,
            frameWidth, frameHeight,           // 必须用实际帧尺寸
            frameRotationDegrees(this@apply),  // 传感器方向-显示方向校正角
            facing == CameraHelper.FACING_FRONT)
    }
    startPreview()
}

// ④ 释放
CameraFaceJni.nativeDestroy(trackerHandle)
```

实物跟踪把 ③ 换成 `nativeSelectObject`（框选时）+ `nativePostFrame`（返回 `[状态,x,y,w,h,相似度]`）即可。

## API 一览

### CameraFaceJni（人脸）

| 方法 | 说明 |
|---|---|
| `nativeCreate(facePaths, featurePaths): Long` | 创建多级联检测跟踪器，失败返回 0 |
| `nativePostFrame(handle, data, w, h, rotation, mirror): Int` | 提交 NV21 帧，返回当前人脸数 |
| `nativeResetTracking(handle)` | 重置跟踪状态（切摄像头后调用） |
| `nativeSetSurface(handle, surface?)` | 绑定/解绑渲染 Surface |
| `nativeDestroy(handle)` | 释放 native 资源 |

### ObjectTrackJni（实物跟踪）

| 方法 | 说明 |
|---|---|
| `nativeCreate(): Long` | 创建跟踪器（无需模型） |
| `nativeSelectObject(handle, x, y, w, h)` | 框选目标（显示图像坐标 ROI，≥24px） |
| `nativePostFrame(handle, data, w, h, rotation, mirror): FloatArray` | 提交帧，返回 `[状态,x,y,w,h,相似度]`；状态 0=待框选 1=已框选 2=跟踪中 3=丢失 |
| `nativeResetTracking(handle)` | 清除模板与状态，重新框选 |
| `nativeGetTemplateThumb(handle): ByteArray` | 框选模板缩略图 `[w(4B)][h(4B)][RGBA]`（小端） |
| `nativeGetTrackedThumb(handle): ByteArray` | 实时跟踪框缩略图，格式同上 |
| `nativeSetSurface` / `nativeDestroy` | 同上 |

## 关键约定

### 坐标系一致性

`nativeSelectObject` 的 ROI 与 `nativePostFrame` 返回的跟踪框、以及渲染画面，三者共用**方向校正后的显示图像坐标系**（native 侧镜像 + 旋转之后的帧）。Java 侧换算要点：

- `rotation = (sensorOrientation - displayRotation + [前置?540:360]) % 360`；
- rotation 为 90°/270° 时，显示图像宽高相对传感器帧互换；
- `ObjectSelectView` 必须与 `SurfaceView` 同容器同尺寸（`match_parent` 叠放），View 坐标才能线性映射到图像坐标。

### CameraHelper 契约

- 帧为**传感器原始方向**（不旋转、不镜像），`sensorOrientation`/`facing` 与旧 Camera API 同源；
- CameraX 绑定异步完成，依赖相机参数的逻辑放在 `onCameraStarted` 回调中；
- `frameWidth/frameHeight` 为最近一帧实际值，个别设备为邻近分辨率，送检务必用实际值。

## 构建与调试

```bash
./gradlew :app:assembleDebug    # 全量构建（含 native so）
```

- **ABI**：arm64-v8a / armeabi-v7a（`build.gradle.kts` 的 `abiFilters`）；
- **日志 tag**：`CameraHelper`（采集/绑定）、`CR_FaceTracker`（人脸检测）、`CR_ObjectTrackerLogic` / `CR_ObjectTracker` / `CR_ObjectTrack`（实物跟踪与框选），可按 tag 过滤定位问题。

## 常见问题

- **Q：为什么不用 mcs_nose/mcs_mouth 模型？**
  OpenCV 2 时代旧格式，特征矩形超出训练窗口，OpenCV 4 加载即抛异常，已替换为验证可加载的等价模型。
- **Q：跟踪框漂移到背景上？**
  多发生在目标与背景颜色高度相近时。相似度低于 0.35 持续 0.5s 会自动判丢并可重定位；也可点击「重新框选」重置模板。
- **Q：前置摄像头画面左右相反？**
  检查 `mirror` 参数是否按 `facing == FACING_FRONT` 传入；镜像必须在旋转之前执行。

## 技术博客

更详细的原理拆解与关键代码逐行解析，见 [CSDN_BLOG.md](./CSDN_BLOG.md)。
