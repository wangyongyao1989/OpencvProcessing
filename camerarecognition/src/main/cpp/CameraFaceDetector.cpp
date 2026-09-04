//
// CameraFaceDetector.cpp
// 相机识别模块：实时人脸检测与跟踪 JNI 实现
// （移植自 ManiiuFace 工程的 native-lib.cpp，保持其核心算法逻辑）
//
// 原理（与 ManiiuFace 一致）：
// 1. 相机预览回调提交 NV21 帧（640×480）；
// 2. NV21 → RGBA（COLOR_YUV2RGBA_NV21）→ 按显示方向旋转/镜像校正；
// 3. RGBA → 灰度 → 直方图均衡化（适应逆光/暗光等光照变化）；
// 4. DetectionBasedTracker 检测跟踪（混合框架）：
//    - 主检测器（CascadeDetectorAdapter 包装 CascadeClassifier）
//      在独立线程周期性做全帧多尺度扫描（detectMultiScale）；
//    - 跟踪器在两次全帧检测之间，仅在已跟踪人脸的邻域内做局部
//      重检测 + 位置预测，帧间平滑输出——兼顾实时性与稳定性；
// 5. 人脸框绘制到 RGBA 帧上，经 ANativeWindow 逐行拷贝到
//    SurfaceView 的缓冲区显示（检测与渲染在预览回调线程完成）。
//

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>
#include <opencv2/objdetect/detection_based_tracker.hpp>

#include <mutex>
#include <string>
#include <vector>

#define TAG "CR_FaceDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using cv::DetectionBasedTracker;

/**
 * 级联检测器适配器（与 ManiiuFace 的 CascadeDetectorAdapter 相同）：
 * 将 cv::CascadeClassifier 适配为 DetectionBasedTracker::IDetector
 * 接口——主检测器与跟踪检测器均通过本适配器包装级联分类器。
 */
class CascadeDetectorAdapter : public DetectionBasedTracker::IDetector {
public:
    explicit CascadeDetectorAdapter(cv::Ptr<cv::CascadeClassifier> detector)
            : IDetector(), cascadeDetector(detector) {
    }

    void detect(const cv::Mat &image, std::vector<cv::Rect> &objects) override {
        cascadeDetector->detectMultiScale(
                image, objects,
                scaleFactor,      // 多尺度缩放步进（IDetector 基类成员）
                minNeighbours,    // NMS 去重邻域阈值（IDetector 基类成员）
                0,
                minObjSize,       // 最小人脸尺寸（IDetector 基类成员）
                maxObjSize);      // 最大人脸尺寸（IDetector 基类成员）
    }

private:
    CascadeDetectorAdapter();

    cv::Ptr<cv::CascadeClassifier> cascadeDetector;
};

/**
 * 相机人脸跟踪器：持有一个 DetectionBasedTracker 与一个
 * ANativeWindow 渲染目标（对应 ManiiuFace 中的全局 tracker/window，
 * 这里收敛为句柄式类，由 Kotlin 侧持有生命周期）。
 */
class CameraFaceTracker {
public:
    ~CameraFaceTracker() {
        release();
    }

    /** 创建主检测器 + 跟踪器并启动（ManiiuFace init 的逻辑）。 */
    bool init(const std::string &cascadePath) {
        cv::Ptr<cv::CascadeClassifier> mainClassifier =
                cv::makePtr<cv::CascadeClassifier>(cascadePath);
        if (mainClassifier->empty()) {
            LOGE("cascade load failed: %s", cascadePath.c_str());
            return false;
        }
        // 主检测器与跟踪检测器各持一份级联模型（与 ManiiuFace 相同）
        cv::Ptr<cv::CascadeClassifier> trackClassifier =
                cv::makePtr<cv::CascadeClassifier>(cascadePath);

        cv::Ptr<CascadeDetectorAdapter> mainDetector =
                cv::makePtr<CascadeDetectorAdapter>(mainClassifier);
        cv::Ptr<CascadeDetectorAdapter> trackingDetector =
                cv::makePtr<CascadeDetectorAdapter>(trackClassifier);

        // 640×480 预览下最小人脸 64px：比默认 96px 更易检出中远距离人脸
        mainDetector->setMinObjectSize(cv::Size(64, 64));
        trackingDetector->setMinObjectSize(cv::Size(64, 64));

        DetectionBasedTracker::Parameters params;
        tracker_ = new DetectionBasedTracker(mainDetector, trackingDetector, params);
        tracker_->run();
        LOGD("DetectionBasedTracker started, cascade: %s", cascadePath.c_str());
        return true;
    }

    /** 释放检测跟踪器与渲染窗口。 */
    void release() {
        if (tracker_ != nullptr) {
            tracker_->stop();
            delete tracker_;
            tracker_ = nullptr;
        }
        setWindow(nullptr);
    }

    /** 重置跟踪状态（切换摄像头后旧跟踪框失效，需重新检测）。 */
    void resetTracking() {
        if (tracker_ != nullptr) {
            tracker_->resetTracking();
        }
    }

    /** 绑定/解绑渲染目标 Surface（ManiiuFace setSurface 的逻辑）。 */
    void setWindow(ANativeWindow *window) {
        std::lock_guard<std::mutex> lock(windowMutex_);
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        if (window != nullptr) {
            window_ = window; // 调用方已 acquire，此处接管引用
        }
    }

    /**
     * 提交一帧 NV21 预览数据：转 RGBA → 方向校正 → 灰度均衡化 →
     * 检测跟踪 → 画人脸框 → 渲染到 Surface（ManiiuFace postData 的逻辑）。
     *
     * @param data      NV21 数据（长度 = w*h*3/2，含 VU 平面）
     * @param w/h       预览宽高
     * @param rotation  顺时针旋转校正角（0/90/180/270，由显示方向与
     *                  传感器方向计算，横屏应用通常为 0）
     * @param mirror    是否水平镜像（前置摄像头自拍镜像视图）
     * @return          当前跟踪到的人脸数
     */
    int postFrame(const uint8_t *data, int w, int h,
                  int rotation, bool mirror) {
        if (tracker_ == nullptr) {
            return 0;
        }

        // NV21 一帧 = Y 平面(w*h) + VU 交错平面(w*h/2)，零拷贝包装为 Mat
        cv::Mat src(h + h / 2, w, CV_8UC1, const_cast<uint8_t *>(data));
        // 颜色格式转换 NV21 -> RGBA
        cvtColor(src, src, cv::COLOR_YUV2RGBA_NV21);

        // 方向校正（先镜像后旋转，与 ManiiuFace 前置摄像头
        // 「逆时针 90° + 水平镜像」的变换等价）：
        // rotation 由 Kotlin 侧按传感器方向与显示方向计算，
        // 本应用固定横屏，后置摄像头通常为 0°
        if (mirror) {
            // 前置摄像头：先水平镜像为自然的自拍视图
            cv::flip(src, src, 1);
        }
        switch (rotation) {
            case 90:
                cv::rotate(src, src, cv::ROTATE_90_CLOCKWISE);
                break;
            case 180:
                cv::rotate(src, src, cv::ROTATE_180);
                break;
            case 270:
                // 顺时针 270° = 逆时针 90°
                cv::rotate(src, src, cv::ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                break;
        }

        // 灰度化 + 直方图均衡（增强对比度，缓解光照不均）
        cv::Mat gray;
        cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
        equalizeHist(gray, gray);

        // 检测跟踪：process 内部按周期触发全帧检测，帧间用局部跟踪续接
        tracker_->process(gray);
        std::vector<cv::Rect> faces;
        tracker_->getObjects(faces);

        // 人脸框绘制在 RGBA 帧上（红色，与 ManiiuFace 相同）
        for (const cv::Rect &face : faces) {
            cv::rectangle(src, face, cv::Scalar(0, 0, 255), 3);
        }

        // 渲染到 SurfaceView（ANativeWindow）
        {
            std::lock_guard<std::mutex> lock(windowMutex_);
            if (window_ != nullptr) {
                drawToWindow(src);
            }
        }
        return static_cast<int>(faces.size());
    }

private:
    /**
     * 将 RGBA 帧逐行拷贝到 ANativeWindow 缓冲区并提交显示。
     * 注意目标缓冲区行距为 buffer.stride（可能大于图像宽度），需逐行拷贝。
     */
    void drawToWindow(const cv::Mat &src) {
        ANativeWindow_setBuffersGeometry(
                window_, src.cols, src.rows, WINDOW_FORMAT_RGBA_8888);

        ANativeWindow_Buffer buffer;
        if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
            // 锁定失败（Surface 已销毁等）：释放引用，后续帧不再渲染
            ANativeWindow_release(window_);
            window_ = nullptr;
            return;
        }

        int srcLineSize = src.cols * 4;              // 源行字节数（RGBA）
        int dstLineSize = buffer.stride * 4;         // 目标行字节数（含 stride）
        uint8_t *dstData = static_cast<uint8_t *>(buffer.bits);
        for (int i = 0; i < buffer.height; ++i) {
            memcpy(dstData + i * dstLineSize,
                   src.data + i * srcLineSize, srcLineSize);
        }
        ANativeWindow_unlockAndPost(window_);
    }

    DetectionBasedTracker *tracker_ = nullptr;
    ANativeWindow *window_ = nullptr;
    std::mutex windowMutex_;
};

inline CameraFaceTracker *asTracker(jlong handle) {
    return reinterpret_cast<CameraFaceTracker *>(handle);
}

} // namespace

extern "C" {

/**
 * 创建检测跟踪器并加载级联模型。
 * @return native 句柄；加载失败返回 0
 */
JNIEXPORT jlong JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeCreate(
        JNIEnv *env, jobject, jstring cascadePath) {
    const char *path = env->GetStringUTFChars(cascadePath, nullptr);
    auto *tracker = new CameraFaceTracker();
    bool ok = tracker->init(std::string(path));
    env->ReleaseStringUTFChars(cascadePath, path);
    if (!ok) {
        delete tracker;
        return 0;
    }
    return reinterpret_cast<jlong>(tracker);
}

/** 释放检测跟踪器与渲染窗口。 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    delete asTracker(handle);
}

/** 绑定渲染 Surface（surface 为 null 时解绑）。 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeSetSurface(
        JNIEnv *env, jobject, jlong handle, jobject surface) {
    CameraFaceTracker *tracker = asTracker(handle);
    if (tracker == nullptr) {
        return;
    }
    if (surface != nullptr) {
        tracker->setWindow(ANativeWindow_fromSurface(env, surface));
    } else {
        tracker->setWindow(nullptr);
    }
}

/** 重置跟踪状态（切换摄像头后调用）。 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeResetTracking(
        JNIEnv *, jobject, jlong handle) {
    CameraFaceTracker *tracker = asTracker(handle);
    if (tracker != nullptr) {
        tracker->resetTracking();
    }
}

/**
 * 提交一帧 NV21 预览数据（检测 + 跟踪 + 渲染）。
 * @return 当前跟踪到的人脸数
 */
JNIEXPORT jint JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativePostFrame(
        JNIEnv *env, jobject, jlong handle,
        jbyteArray data, jint w, jint h, jint rotation, jboolean mirror) {
    CameraFaceTracker *tracker = asTracker(handle);
    if (tracker == nullptr) {
        return 0;
    }
    jsize len = env->GetArrayLength(data);
    if (len < w * h * 3 / 2) {
        return 0;
    }

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    int faces = tracker->postFrame(
            reinterpret_cast<const uint8_t *>(buf),
            w, h, rotation, mirror == JNI_TRUE);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return faces;
}

/** 返回 OpenCV 版本串（诊断用）。 */
JNIEXPORT jstring JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeOpencvVersion(
        JNIEnv *env, jobject) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

} // extern "C"
