//
// CameraFaceDetector.cpp
// 相机识别模块：实时人脸检测与跟踪 JNI 实现
// 按《视频识别及物体
//  人脸识别需求文档》第九章的 Haar 级联模型库扩展为多级联融合
//  检测，解决单模型识别率差的问题）
//
// 多级联融合策略（需求文档「第九章 Haar级联模型文件」全部人脸相关
// 预训练 XML 模型）：
// 1. 正脸双模型并集：haarcascade_frontalface_default +
//    haarcascade_frontalface_alt2 —— 两个模型训练侧重不同，
//    任一命中即保留（并集直接提升召回率）；
// 2. 侧脸检测：haarcascade_profileface 级联 + 水平镜像各扫一次
//    （覆盖左右侧脸）—— 转头/侧脸是单正脸模型漏检的主因；
// 3. 面部特征验证（降误检）：较大候选框在上部检出眼睛
//    （haarcascade_eye），或同时检出鼻子（haarcascade_nose）与
//    嘴巴（haarcascade_mouth）才确认为人脸；侧脸候选跳过验证
//    （侧脸常无可见双眼）。
//    注：需求文档第九章的 mcs_nose/mcs_mouth 为 OpenCV 2 时代旧
//    格式（特征矩形超出训练窗口），OpenCV 4 加载即抛异常，已替换
//    为验证兼容的等价模型；所有模型加载均带异常防御，坏模型只会
//    被跳过并记日志，不会导致进程崩溃。
// 4. NMS 融合去重：多模型结果按 IoU 聚类，簇内取平均框；
// 5. 参数面向识别率放宽：scaleFactor=1.08（更细的多尺度扫描）、
//    minNeighbors=3、最小人脸 48px（原 96px 默认值漏检远小脸）；
// 6. 全帧扫描内部降采样 0.5（640×480 → 320×240，检测框坐标
//    还原回全分辨率），多模型并集的开销与单模型全分辨率相当；
// 7. CLAHE 自适应局部直方图均衡（替代 ManiiuFace 的全局
//    equalizeHist）：对逆光/侧光/阴影等局部光照不均更鲁棒。
//
// DetectionBasedTracker 混合检测跟踪框架（与 ManiiuFace 一致）：
// - 主检测器（本文件 MultiCascadeAdapter 全模型模式）在后台线程
//   周期性做全帧多级联扫描；
// - 跟踪检测器（轻量模式：首个正脸模型 + 侧脸模型）在两次全帧
//   扫描之间，仅在已跟踪人脸的邻域内局部重检测 + 位置加权平滑，
//   保证逐帧速度与跟踪框的连续稳定。
//
// 渲染（与 ManiiuFace 一致）：人脸框绘制在 RGBA 帧上，经
// ANativeWindow 逐行拷贝到 SurfaceView 缓冲区显示。
//

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>
#include <opencv2/objdetect/detection_based_tracker.hpp>

#include <algorithm>
#include <climits>
#include <mutex>
#include <string>
#include <vector>

#define TAG "CR_FaceDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using cv::DetectionBasedTracker;

/** NMS 聚类 IoU 阈值：不同模型对同一张脸的框 IoU 通常 > 0.4。 */
constexpr float MERGE_IOU = 0.30f;

/** 候选框参加面部特征验证的最小边长（更小的脸特征太弱，跳过验证）。 */
constexpr int VERIFY_MIN_SIZE = 70;

/** 主检测器全帧扫描内部降采样比例。 */
constexpr float FULL_SCAN_DOWNSCALE = 0.5f;

/** 带来源标记的检测框（fromProfile：来自侧脸模型，验证阶段跳过）。 */
struct RawBox {
    cv::Rect r;
    bool fromProfile;
};

/** 两矩形交并比。 */
inline float boxIou(const cv::Rect &a, const cv::Rect &b) {
    float ix1 = std::max(a.x, b.x), iy1 = std::max(a.y, b.y);
    float ix2 = std::min(a.x + a.width, b.x + b.width);
    float iy2 = std::min(a.y + a.height, b.y + b.height);
    float iw = std::max(0.f, ix2 - ix1), ih = std::max(0.f, iy2 - iy1);
    float inter = iw * ih;
    if (inter <= 0.f) return 0.f;
    return inter / (static_cast<float>(a.area()) + static_cast<float>(b.area()) - inter);
}

/**
 * 多级联融合检测适配器：将第九章的多个 Haar 级联模型适配为
 * DetectionBasedTracker::IDetector 接口。
 *
 * detect() 流程：各模型扫描（侧脸附加镜像补扫）→ 坐标还原 →
 * IoU 聚类取平均框 →（全模型模式）面部特征验证 → 输出。
 */
class MultiCascadeAdapter : public DetectionBasedTracker::IDetector {
public:
    /**
     * @param facePaths    人脸级联模型路径（文件名含 "profileface"
     *                     视为侧脸模型并自动镜像补扫另一侧，其余视为
     *                     正脸模型）
     * @param featurePaths 面部特征模型路径（按文件名识别：eyeglasses
     *                     / eye / mcs_nose / mcs_mouth），仅全模型模式
     *                     用于候选框验证
     * @param trackMode    true = 跟踪检测器（帧间局部区域重检测）：
     *                     只加载首个正脸模型 + 全部侧脸模型，不降采样、
     *                     不做特征验证，保证逐帧速度；
     *                     false = 主检测器（后台周期性全帧扫描）：
     *                     加载全部人脸模型 + 特征验证 + 内部降采样
     */
    MultiCascadeAdapter(const std::vector<std::string> &facePaths,
                        const std::vector<std::string> &featurePaths,
                        bool trackMode)
            : IDetector(), trackMode_(trackMode) {
        bool frontalKept = false;
        for (const std::string &p : facePaths) {
            faces_.emplace_back();
            faces_.back().isProfile = p.find("profileface") != std::string::npos;
            // 防御式加载：模型文件损坏/格式不合法时 CascadeClassifier
            // 可能抛 cv::Exception（如旧版 mcs 模型特征矩形超出训练
            // 窗口，OpenCV 4 严格校验直接抛错），捕获后跳过该模型，
            // 绝不让单坏模型导致进程崩溃
            try {
                if (!faces_.back().classifier.load(p)) {
                    LOGE("face cascade load failed: %s", p.c_str());
                    faces_.pop_back();
                    continue;
                }
            } catch (const cv::Exception &e) {
                LOGE("face cascade load exception (%s): %s",
                     e.what(), p.c_str());
                faces_.pop_back();
                continue;
            }
            // 跟踪模式只保留第一个正脸模型（逐帧局部重检测求快）
            if (trackMode_ && !faces_.back().isProfile) {
                if (frontalKept) {
                    faces_.pop_back();
                    continue;
                }
                frontalKept = true;
            }
            LOGD("loaded face cascade (track=%d, profile=%d): %s",
                 trackMode_, faces_.back().isProfile, p.c_str());
        }

        if (!trackMode_) {
            for (const std::string &p : featurePaths) {
                auto *c = new cv::CascadeClassifier();
                try {
                    if (!c->load(p)) {
                        LOGE("feature cascade load failed: %s", p.c_str());
                        delete c;
                        continue;
                    }
                } catch (const cv::Exception &e) {
                    LOGE("feature cascade load exception (%s): %s",
                         e.what(), p.c_str());
                    delete c;
                    continue;
                }
                if (p.find("eyeglasses") != std::string::npos) {
                    glasses_ = c;
                } else if (p.find("eye") != std::string::npos) {
                    eye_ = c;
                } else if (p.find("nose") != std::string::npos) {
                    nose_ = c;
                } else if (p.find("mouth") != std::string::npos) {
                    mouth_ = c;
                } else {
                    delete c; // 未识别用途的模型不参与验证
                    continue;
                }
                LOGD("loaded feature cascade: %s", p.c_str());
            }
        }

        // 面向识别率的检测参数（IDetector 基类成员）：
        // 主检测器更细的多尺度步进；minNeighbors 放宽到 3，
        // 误检由 NMS 融合 + 特征验证 + 跟踪器时间平滑共同抑制
        setScaleFactor(trackMode_ ? 1.1f : 1.08f);
        setMinNeighbours(3);
    }

    ~MultiCascadeAdapter() override {
        delete eye_;
        delete glasses_;
        delete nose_;
        delete mouth_;
    }

    /** 是否加载到至少一个可用的人脸级联模型。 */
    bool empty() const { return faces_.empty(); }

    void detect(const cv::Mat &image, std::vector<cv::Rect> &objects) override {
        objects.clear();
        if (faces_.empty() || image.empty()) return;
        // 整体兜底：检测过程中任何异常（异常模型/异常输入）只丢弃
        // 本帧结果，绝不向 Java 层传播导致进程崩溃
        try {
            detectInternal(image, objects);
        } catch (const cv::Exception &e) {
            LOGE("detect exception: %s", e.what());
            objects.clear();
        }
    }

private:
    void detectInternal(const cv::Mat &image, std::vector<cv::Rect> &objects) {

        // ---- 1. 工作图（主检测器内部降采样，跟踪检测器原尺度） ----
        cv::Mat work = image;
        cv::Mat small;
        if (!trackMode_) {
            cv::resize(image, small, cv::Size(),
                       FULL_SCAN_DOWNSCALE, FULL_SCAN_DOWNSCALE,
                       cv::INTER_LINEAR);
            work = small;
        }

        // ---- 2. 各级联模型扫描（侧脸模型附加水平镜像补扫） ----
        const float scale = trackMode_ ? 1.f : FULL_SCAN_DOWNSCALE;
        const cv::Size minS(cvRound(minObjSize.width * scale),
                            cvRound(minObjSize.height * scale));
        std::vector<RawBox> raw;
        // 注意：detectMultiScale 非 const 成员，需以非 const 引用迭代
        for (FaceCascade &fc : faces_) {
            std::vector<cv::Rect> found;
            fc.classifier.detectMultiScale(
                    work, found, scaleFactor, minNeighbours, 0, minS, maxObjSize);
            for (const cv::Rect &r : found) {
                raw.push_back({r, fc.isProfile});
            }
            if (fc.isProfile) {
                // 侧脸级联只识别朝向一侧的脸，水平镜像后可覆盖另一侧
                cv::Mat flipped;
                cv::flip(work, flipped, 1);
                found.clear();
                fc.classifier.detectMultiScale(
                        flipped, found, scaleFactor, minNeighbours, 0, minS, maxObjSize);
                const int w = work.cols;
                for (cv::Rect r : found) {
                    r.x = w - r.x - r.width; // 镜像坐标还原
                    raw.push_back({r, true});
                }
            }
        }

        // ---- 3. 坐标还原回原分辨率 ----
        if (!trackMode_) {
            for (RawBox &b : raw) {
                b.r.x = cvRound(b.r.x / FULL_SCAN_DOWNSCALE);
                b.r.y = cvRound(b.r.y / FULL_SCAN_DOWNSCALE);
                b.r.width = cvRound(b.r.width / FULL_SCAN_DOWNSCALE);
                b.r.height = cvRound(b.r.height / FULL_SCAN_DOWNSCALE);
            }
        }

        // ---- 4. NMS 融合去重：IoU 聚类，簇内取平均框 ----
        // 按面积降序贪心聚类（大框通常是更完整的人脸检测）
        std::sort(raw.begin(), raw.end(), [](const RawBox &a, const RawBox &b) {
            return a.r.area() > b.r.area();
        });
        std::vector<std::vector<RawBox>> clusters;
        std::vector<cv::Rect> reps; // 各簇当前平均框（IoU 比较基准）
        for (const RawBox &b : raw) {
            int best = -1;
            float bestIoU = 0.f;
            for (size_t i = 0; i < clusters.size(); i++) {
                float v = boxIou(b.r, reps[i]);
                if (v > bestIoU) {
                    bestIoU = v;
                    best = static_cast<int>(i);
                }
            }
            if (best >= 0 && bestIoU > MERGE_IOU) {
                clusters[best].push_back(b);
                reps[best] = averageBox(clusters[best]);
            } else {
                clusters.push_back({b});
                reps.push_back(b.r);
            }
        }

        // ---- 5. 输出（全模型模式：正脸大框做面部特征验证） ----
        for (size_t i = 0; i < clusters.size(); i++) {
            const cv::Rect box = reps[i];
            bool fromProfile = false;
            for (const RawBox &m : clusters[i]) fromProfile |= m.fromProfile;

            if (!trackMode_ && !fromProfile &&
                box.width >= VERIFY_MIN_SIZE && box.height >= VERIFY_MIN_SIZE &&
                !verifyFace(image, box)) {
                continue; // 验证不通过：框内无任何面部特征，判为误检丢弃
            }
            objects.push_back(box);
        }
    }

private:
    MultiCascadeAdapter();

    struct FaceCascade {
        cv::CascadeClassifier classifier;
        bool isProfile;
    };

    /** 簇内平均框（四舍五入）。 */
    static cv::Rect averageBox(const std::vector<RawBox> &c) {
        double x = 0, y = 0, w = 0, h = 0;
        for (const RawBox &b : c) {
            x += b.r.x; y += b.r.y; w += b.r.width; h += b.r.height;
        }
        const double n = static_cast<double>(c.size());
        return cv::Rect(cvRound(x / n), cvRound(y / n),
                        cvRound(w / n), cvRound(h / n));
    }

    /**
     * 面部特征验证：候选框内是否检出支持「这是人脸」的特征。
     * 宽松规则（保识别率优先）：
     *   检出眼睛 或 戴眼镜眼睛 或 （鼻子 且 嘴巴）→ 通过；
     * 未加载任何特征模型时直接通过。
     */
    bool verifyFace(const cv::Mat &gray, const cv::Rect &r) {
        if (eye_ == nullptr && glasses_ == nullptr &&
            nose_ == nullptr && mouth_ == nullptr) {
            return true;
        }
        const cv::Rect bounds(0, 0, gray.cols, gray.rows);
        const cv::Rect roi = r & bounds;
        if (roi.empty()) return true;

        std::vector<cv::Rect> hits;
        const cv::Size fmin(std::max(12, roi.width / 6),
                            std::max(12, roi.width / 6));

        // 眼睛：候选框上部 60%（先查普通眼睛，未命中再查戴眼镜模型）
        cv::Rect eyeRoi(roi.x + cvRound(roi.width * 0.1), roi.y,
                        cvRound(roi.width * 0.8), cvRound(roi.height * 0.6));
        eyeRoi &= bounds;
        if (eyeRoi.width > fmin.width && eyeRoi.height > fmin.height) {
            const cv::Mat eyeImg = gray(eyeRoi);
            if (eye_ != nullptr) {
                eye_->detectMultiScale(eyeImg, hits, 1.1, 2, 0, fmin);
                if (!hits.empty()) return true;
            }
            if (glasses_ != nullptr) {
                glasses_->detectMultiScale(eyeImg, hits, 1.1, 2, 0, fmin);
                if (!hits.empty()) return true;
            }
        }

        // 鼻子：中部条带；嘴巴：下部条带（两者同时命中才通过）
        bool hasNose = false, hasMouth = false;
        if (nose_ != nullptr) {
            cv::Rect nRoi(roi.x + cvRound(roi.width * 0.2),
                          roi.y + cvRound(roi.height * 0.25),
                          cvRound(roi.width * 0.6), cvRound(roi.height * 0.5));
            nRoi &= bounds;
            if (nRoi.width > fmin.width && nRoi.height > fmin.height) {
                nose_->detectMultiScale(gray(nRoi), hits, 1.1, 2, 0, fmin);
                hasNose = !hits.empty();
            }
        }
        if (mouth_ != nullptr) {
            cv::Rect mRoi(roi.x + cvRound(roi.width * 0.2),
                          roi.y + cvRound(roi.height * 0.55),
                          cvRound(roi.width * 0.6), cvRound(roi.height * 0.4));
            mRoi &= bounds;
            if (mRoi.width > fmin.width && mRoi.height > fmin.height) {
                mouth_->detectMultiScale(gray(mRoi), hits, 1.1, 2, 0, fmin);
                hasMouth = !hits.empty();
            }
        }
        return hasNose && hasMouth;
    }

    std::vector<FaceCascade> faces_;

    // 面部特征模型（仅主检测器使用，候选框验证）
    cv::CascadeClassifier *eye_ = nullptr;
    cv::CascadeClassifier *glasses_ = nullptr;
    cv::CascadeClassifier *nose_ = nullptr;
    cv::CascadeClassifier *mouth_ = nullptr;

    bool trackMode_;
};

/**
 * 相机人脸跟踪器：持有一个 DetectionBasedTracker（主检测器 +
 * 跟踪检测器均为 MultiCascadeAdapter）与一个 ANativeWindow
 * 渲染目标（对应 ManiiuFace 中的全局 tracker/window，这里收敛
 * 为句柄式类，由 Kotlin 侧持有生命周期）。
 */
class CameraFaceTracker {
public:
    ~CameraFaceTracker() {
        release();
    }

    /** 创建多级联主/跟踪检测器并启动。 */
    bool init(const std::vector<std::string> &facePaths,
              const std::vector<std::string> &featurePaths) {
        cv::Ptr<MultiCascadeAdapter> mainDetector =
                cv::makePtr<MultiCascadeAdapter>(facePaths, featurePaths, false);
        cv::Ptr<MultiCascadeAdapter> trackingDetector =
                cv::makePtr<MultiCascadeAdapter>(facePaths, featurePaths, true);
        if (mainDetector->empty() || trackingDetector->empty()) {
            LOGE("no valid face cascade loaded");
            return false;
        }

        // 640×480 预览下最小人脸 48px：兼顾远距离小脸与误检抑制
        mainDetector->setMinObjectSize(cv::Size(48, 48));
        trackingDetector->setMinObjectSize(cv::Size(48, 48));

        // CLAHE 局部自适应直方图均衡（clipLimit=2.0 保守增强）
        clahe_ = cv::createCLAHE(2.0, cv::Size(8, 8));

        DetectionBasedTracker::Parameters params;
        tracker_ = new DetectionBasedTracker(mainDetector, trackingDetector, params);
        tracker_->run();
        LOGD("DetectionBasedTracker started (multi-cascade: %d face models)",
             static_cast<int>(facePaths.size()));
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
     * 提交一帧 NV21 预览数据：转 RGBA → 方向校正 → CLAHE 灰度增强 →
     * 多级联检测跟踪 → 画人脸框 → 渲染到 Surface
     * （ManiiuFace postData 的逻辑 + 多级联扩展）。
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

        // 灰度化 + CLAHE 局部自适应直方图均衡（增强局部对比度，
        // 缓解逆光/侧光/阴影；比 ManiiuFace 的全局均衡更鲁棒）
        cv::Mat gray;
        cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
        clahe_->apply(gray, gray);

        // 检测跟踪：process 内部按周期触发全帧多级联检测，
        // 帧间用局部跟踪续接
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
    cv::Ptr<cv::CLAHE> clahe_;
};

inline CameraFaceTracker *asTracker(jlong handle) {
    return reinterpret_cast<CameraFaceTracker *>(handle);
}

/** jobjectArray<String> 转 std::vector<std::string>。 */
std::vector<std::string> toStringVector(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto *s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (s == nullptr) continue;
        const char *c = env->GetStringUTFChars(s, nullptr);
        if (c != nullptr) {
            out.emplace_back(c);
        }
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return out;
}

} // namespace

extern "C" {

/**
 * 创建多级联检测跟踪器并加载级联模型。
 * @param faceCascadePaths    人脸模型路径（正脸 default/alt2 + 侧脸）
 * @param featureCascadePaths 面部特征模型路径（眼/戴眼镜眼/鼻/嘴）
 * @return native 句柄；加载失败返回 0
 */
JNIEXPORT jlong JNICALL
Java_com_wangyao_camerarecognition_jni_CameraFaceJni_nativeCreate(
        JNIEnv *env, jobject, jobjectArray faceCascadePaths,
        jobjectArray featureCascadePaths) {
    auto *tracker = new CameraFaceTracker();
    bool ok = false;
    // JNI 边界兜底：初始化全程任何异常都视为创建失败返回 0，
    // 由 Kotlin 侧提示初始化错误，绝不向 JVM 传播导致崩溃
    try {
        ok = tracker->init(toStringVector(env, faceCascadePaths),
                           toStringVector(env, featureCascadePaths));
    } catch (const cv::Exception &e) {
        LOGE("tracker init exception: %s", e.what());
        ok = false;
    } catch (const std::exception &e) {
        LOGE("tracker init std exception: %s", e.what());
        ok = false;
    }
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
