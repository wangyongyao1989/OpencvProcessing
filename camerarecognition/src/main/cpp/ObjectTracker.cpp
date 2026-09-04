//
// ObjectTracker.cpp
// 相机识别·框选实物实时检测跟踪 JNI 实现。
//
// 以 videorecognition 模块「以该帧检索视频」的检索代码流程为基础，
// 将「查询帧 → 特征提取 → 综合相似度匹配」的离线检索流程改造为
// 相机预览下的实时目标跟踪：
//
// 1. 目标获取（手势框选 = 检索中的「查询」）：用户在预览画面按住
//    拖动框选实物 ROI，松手后下一帧即以该 ROI 作为查询目标提取
//    特征建立「目标模板」——与以该帧检索视频中「捕获当前画面作为
//    查询帧」同源；
// 2. 特征提取（与视频检索同一特征空间，教材第 10 章）：
//    - 颜色特征：64 维亮度直方图（L1 归一化）；
//    - 纹理特征：Sobel 梯度方向直方图（18 bins、幅值加权、仅统计
//      显著边缘、方向折叠到 [0°,180°)，L1 归一化）；
//    - 另提取 HSV 色调直方图（32 bins，屏蔽低饱和/低亮度像素），
//      供 CamShift 反向投影定位候选区域；
// 3. 候选生成（CamShift 连续自适应均值漂移）：以模板色调直方图对
//    整帧做反向投影得到概率图，CamShift 在上一帧窗口邻域迭代收敛
//    到概率密度峰值，自动适应目标位置与尺寸变化；
// 4. 综合相似度验证（检索的相似性度量）：
//    Sim = 0.6·直方图相交(颜色) + 0.4·余弦(纹理) ∈ [0,1]，
//    与 VideoKeyframeIndexer 的综合相似度完全一致——达标确认同一
//    目标并输出绿框与实时相似度；连续 15 帧低于 0.35 判定丢失；
// 5. 自适应模板：跟踪稳定（相似度 > 0.8）时每 5 帧以 0.2 权重融合
//    当前目标特征更新模板，适应光照渐变与姿态变化；
// 6. 数据流水线：相机 NV21 帧 → RGBA → 方向校正（前置镜像）→
//    灰度/HSV → CamShift 跟踪 + 特征验证 → 绿框与相似度绘制 →
//    ANativeWindow 渲染，全程在预览回调线程完成，保证实时性。
//
// 状态机：IDLE（无模板）→ ARMED（待下一帧取模板）→ TRACKING
// （绿框跟踪中）→ LOST（相似度连续过低，清除模板等待重新框选）。
//

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "CR_ObjectTracker"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---- 与 videorecognition「以该帧检索视频」一致的特征定义 ----

/** 亮度直方图维数（KeyframeFeatures.GRAY_BINS）。 */
constexpr int GRAY_BINS = 64;

/** 梯度方向量化级数（KeyframeFeatures.ORI_BINS，每 10° 一 bin）。 */
constexpr int ORI_BINS = 18;

/** 综合相似度颜色权重 w_c（KeyframeFeatures.WEIGHT_COLOR）。 */
constexpr float WEIGHT_COLOR = 0.6f;

/** 综合相似度纹理权重 w_t（KeyframeFeatures.WEIGHT_TEXTURE）。 */
constexpr float WEIGHT_TEXTURE = 0.4f;

/** CamShift 色调直方图 bins。 */
constexpr int HUE_BINS = 32;

/** 框选 ROI 最小边长（过小的目标特征不稳定）。 */
constexpr int MIN_ROI_SIZE = 24;

/** 判定丢失的相似度阈值。 */
constexpr float SIM_LOST = 0.35f;

/** 连续低于阈值判定丢失的帧数。 */
constexpr int LOST_FRAMES = 15;

/** 自适应模板更新的相似度门槛。 */
constexpr float SIM_ADAPT = 0.80f;

/** 自适应模板更新的周期（帧）与融合权重。 */
constexpr int ADAPT_INTERVAL = 5;
constexpr float ADAPT_ALPHA = 0.2f;

/** 跟踪器状态（返回给 Kotlin 侧）。 */
enum TrackState {
    STATE_IDLE = 0,    // 无模板，等待框选
    STATE_ARMED = 1,   // 已框选，待下一帧提取模板
    STATE_TRACKING = 2,// 跟踪中
    STATE_LOST = 3,    // 目标丢失，等待重新框选
};

/** 一帧的综合特征向量（KeyframeFeatures.Feature 的 C++ 移植）。 */
struct Feature {
    float color[GRAY_BINS] = {0};
    float texture[ORI_BINS] = {0};
};

/** 64 维亮度直方图（L1 归一化，和为 1）。 */
void grayHistogram(const cv::Mat &gray, float *hist) {
    std::memset(hist, 0, sizeof(float) * GRAY_BINS);
    const int n = gray.rows * gray.cols;
    if (n <= 0) return;
    const uint8_t *p = gray.data;
    for (int i = 0; i < n; i++) {
        hist[p[i] * GRAY_BINS / 256]++;
    }
    const float inv = 1.0f / static_cast<float>(n);
    for (int i = 0; i < GRAY_BINS; i++) hist[i] *= inv;
}

/**
 * Sobel 梯度方向直方图（L1 归一化）：幅值加权、仅统计显著边缘
 * （幅值 > 均值）、方向折叠到 [0°,180°)（无极性）。
 */
void textureHistogram(const cv::Mat &gray, float *hist) {
    std::memset(hist, 0, sizeof(float) * ORI_BINS);
    if (gray.cols < 3 || gray.rows < 3) return;
    const int w = gray.cols;
    const int h = gray.rows;
    std::vector<float> mag(static_cast<size_t>(w) * h, 0.f);
    std::vector<float> ori(static_cast<size_t>(w) * h, 0.f);
    double sumMag = 0.0;
    int cnt = 0;
    for (int y = 1; y < h - 1; y++) {
        const uint8_t *row = gray.ptr<uint8_t>(y);
        for (int x = 1; x < w - 1; x++) {
            const int idx = y * w + x;
            const float gx = (row[x - w + 1] + 2 * row[x + 1] + row[x + w + 1]) -
                             (row[x - w - 1] + 2 * row[x - 1] + row[x + w - 1]);
            const float gy = (row[x + w - 1] + 2 * row[x + w] + row[x + w + 1]) -
                             (row[x - w - 1] + 2 * row[x - w] + row[x - w + 1]);
            const float m = std::sqrt(gx * gx + gy * gy);
            mag[idx] = m;
            ori[idx] = std::atan2(gy, gx);
            sumMag += m;
            cnt++;
        }
    }
    const float meanMag = cnt > 0 ? static_cast<float>(sumMag / cnt) : 0.f;
    float total = 0.f;
    for (int i = 0; i < w * h; i++) {
        const float m = mag[i];
        if (m > meanMag && m > 0.f) {
            float deg = ori[i] * 57.29577951f; // rad → deg
            if (deg < 0) deg += 180.f;
            int bin = static_cast<int>(deg / 180.f * ORI_BINS);
            if (bin > ORI_BINS - 1) bin = ORI_BINS - 1;
            hist[bin] += m;
            total += m;
        }
    }
    if (total > 0.f) {
        const float inv = 1.0f / total;
        for (int i = 0; i < ORI_BINS; i++) hist[i] *= inv;
    }
}

/** 直方图相交：Σ min(a(i),b(i)) ∈ [0,1]。 */
float histogramIntersection(const float *a, const float *b, int n) {
    float s = 0.f;
    for (int i = 0; i < n; i++) s += std::min(a[i], b[i]);
    return s;
}

/** 余弦相似度：A·B/(|A||B|)，特征非负时 ∈ [0,1]。 */
float cosine(const float *a, const float *b, int n) {
    float dot = 0.f, na = 0.f, nb = 0.f;
    for (int i = 0; i < n; i++) {
        dot += a[i] * b[i];
        na += a[i] * a[i];
        nb += b[i] * b[i];
    }
    if (na <= 0.f || nb <= 0.f) return 0.f;
    return dot / (std::sqrt(na) * std::sqrt(nb));
}

/** 综合相似度：0.6·直方图相交(颜色) + 0.4·余弦(纹理)。 */
float comprehensiveSimilarity(const Feature &a, const Feature &b) {
    return WEIGHT_COLOR * histogramIntersection(a.color, b.color, GRAY_BINS) +
           WEIGHT_TEXTURE * cosine(a.texture, b.texture, ORI_BINS);
}

/**
 * 框选实物实时跟踪器：模板 = 色调直方图（CamShift 定位）+
 * 综合特征（检索式相似度验证），帧间 CamShift 迭代 + 验证。
 */
class ObjectTracker {
public:
    ~ObjectTracker() {
        setWindow(nullptr);
    }

    /** 绑定/解绑渲染目标 Surface。 */
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

    /** 清除模板与跟踪状态（重新框选 / 切换摄像头后调用）。 */
    void resetTracking() {
        std::lock_guard<std::mutex> lock(stateMutex_);
        hasTemplate_ = false;
        armed_ = false;
        state_ = STATE_IDLE;
        lostCount_ = 0;
        frameIdx_ = 0;
    }

    /**
     * 手势框选目标（检索中的「查询」）：记录 ROI（显示图像坐标系），
     * 下一帧到达时提取模板并开始跟踪。
     */
    void selectObject(int x, int y, int w, int h) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        if (w < MIN_ROI_SIZE || h < MIN_ROI_SIZE) {
            LOGE("select ROI too small: %dx%d", w, h);
            return;
        }
        pendingRoi_ = cv::Rect(x, y, w, h);
        armed_ = true;
        state_ = STATE_ARMED;
        LOGD("object selected: roi=%d,%d %dx%d", x, y, w, h);
    }

    /**
     * 提交一帧 NV21 预览数据：转 RGBA → 方向校正 →（ARMED 时取模板 /
     * TRACKING 时 CamShift + 相似度验证）→ 绿框绘制 → 渲染。
     *
     * @param outBox  输出跟踪框（图像坐标），仅 TRACKING 有效
     * @param outSim  输出综合相似度 ∈ [0,1]
     * @return 跟踪状态（TrackState）
     */
    int postFrame(const uint8_t *data, int w, int h,
                  int rotation, bool mirror,
                  float outBox[4], float *outSim) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        outBox[0] = outBox[1] = outBox[2] = outBox[3] = 0.f;
        *outSim = 0.f;
        if (state_ == STATE_IDLE) {
            renderPlain(data, w, h, rotation, mirror);
            return STATE_IDLE;
        }
        // 整体兜底：任何异常只退回 IDLE，不向 JVM 传播
        try {
            return trackLocked(data, w, h, rotation, mirror, outBox, outSim);
        } catch (const cv::Exception &e) {
            LOGE("track exception: %s", e.what());
            hasTemplate_ = false;
            armed_ = false;
            state_ = STATE_IDLE;
            return STATE_IDLE;
        }
    }

private:
    /** NV21 → RGBA → 方向校正（前置镜像），输出显示坐标系帧。 */
    cv::Mat decodeFrame(const uint8_t *data, int w, int h,
                        int rotation, bool mirror) {
        cv::Mat src(h + h / 2, w, CV_8UC1, const_cast<uint8_t *>(data));
        cvtColor(src, src, cv::COLOR_YUV2RGBA_NV21);
        if (mirror) {
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
                cv::rotate(src, src, cv::ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                break;
        }
        return src;
    }

    /** 无模板时仅渲染预览帧。 */
    void renderPlain(const uint8_t *data, int w, int h,
                     int rotation, bool mirror) {
        cv::Mat frame = decodeFrame(data, w, h, rotation, mirror);
        std::lock_guard<std::mutex> lock(windowMutex_);
        if (window_ != nullptr) {
            drawToWindow(frame);
        }
    }

    /** 有模板/待取模板时的主流程。 */
    int trackLocked(const uint8_t *data, int w, int h,
                    int rotation, bool mirror,
                    float outBox[4], float *outSim) {
        cv::Mat frame = decodeFrame(data, w, h, rotation, mirror);
        cv::Mat gray;
        cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
        // OpenCV 无 RGBA→HSV 直接转换码：先转 RGB 再转 HSV
        cv::Mat rgb;
        cvtColor(frame, rgb, cv::COLOR_RGBA2RGB);
        cv::Mat hsv;
        cvtColor(rgb, hsv, cv::COLOR_RGB2HSV);
        // 屏蔽低饱和/低亮度像素（近灰区域色调噪声大）
        cv::Mat mask;
        cv::inRange(hsv, cv::Scalar(0, 30, 30),
                    cv::Scalar(180, 255, 255), mask);
        std::vector<cv::Mat> planes;
        cv::split(hsv, planes); // planes[0] = H

        // ---- ARMED：本帧提取目标模板（检索中的「查询特征」） ----
        if (armed_) {
            cv::Rect roi = pendingRoi_ &
                           cv::Rect(0, 0, frame.cols, frame.rows);
            if (roi.width < MIN_ROI_SIZE || roi.height < MIN_ROI_SIZE) {
                armed_ = false;
                state_ = STATE_IDLE;
                std::lock_guard<std::mutex> lock(windowMutex_);
                if (window_ != nullptr) drawToWindow(frame);
                return STATE_IDLE;
            }
            buildTemplate(planes[0], mask, gray, roi);
            armed_ = false;
            hasTemplate_ = true;
            state_ = STATE_TRACKING;
            trackWindow_ = roi;
            lostCount_ = 0;
            frameIdx_ = 0;
            LOGD("template built from roi=%d,%d %dx%d",
                 roi.x, roi.y, roi.width, roi.height);
        }

        if (!hasTemplate_) {
            std::lock_guard<std::mutex> lock(windowMutex_);
            if (window_ != nullptr) drawToWindow(frame);
            return state_;
        }

        // ---- CamShift：色调反向投影 + 均值漂移迭代定位候选 ----
        cv::Mat prob;
        const float *ranges[] = {hueRanges_};
        cv::calcBackProject(&planes[0], 1, 0, hueHist_, prob,
                            ranges, 1, true);
        // 概率图转 8U（值域 0-255）后与掩码相与，清零低饱和区域
        prob.convertTo(prob, CV_8U);
        cv::bitwise_and(prob, mask, prob);

        cv::TermCriteria criteria(
                cv::TermCriteria::EPS | cv::TermCriteria::COUNT, 10, 1);
        cv::RotatedRect trackBox = cv::CamShift(prob, trackWindow_, criteria);
        cv::Rect cand = trackBox.boundingRect() &
                        cv::Rect(0, 0, frame.cols, frame.rows);
        if (cand.width < MIN_ROI_SIZE || cand.height < MIN_ROI_SIZE) {
            // 候选退化（目标出画/反投影坍缩）：按低相似度帧处理
            cand = trackWindow_;
        }

        // ---- 综合相似度验证（与视频检索同一度量） ----
        Feature candFeat;
        grayHistogram(gray(cand), candFeat.color);
        textureHistogram(gray(cand), candFeat.texture);
        float sim = comprehensiveSimilarity(template_, candFeat);

        frameIdx_++;
        if (sim < SIM_LOST) {
            if (++lostCount_ > LOST_FRAMES) {
                // 连续过低：判定丢失，清除模板等待重新框选
                hasTemplate_ = false;
                state_ = STATE_LOST;
                *outSim = sim;
                LOGD("object lost (sim=%.2f)", sim);
                std::lock_guard<std::mutex> lock(windowMutex_);
                if (window_ != nullptr) drawToWindow(frame);
                return STATE_LOST;
            }
        } else {
            lostCount_ = 0;
            state_ = STATE_TRACKING;
            // 自适应模板：跟踪稳定时小权重融合当前特征
            if (sim > SIM_ADAPT && frameIdx_ % ADAPT_INTERVAL == 0) {
                adaptTemplate(candFeat, planes[0], mask, cand);
            }
        }

        // ---- 绘制绿框 + 相似度标签，渲染 ----
        cv::rectangle(frame, cand, cv::Scalar(0, 255, 0), 3);
        char label[32];
        std::snprintf(label, sizeof(label), "sim %.0f%%", sim * 100.f);
        cv::putText(frame, label,
                    cv::Point(cand.x, std::max(14, cand.y - 6)),
                    cv::FONT_HERSHEY_SIMPLEX, 0.55,
                    cv::Scalar(0, 255, 0), 2);

        outBox[0] = static_cast<float>(cand.x);
        outBox[1] = static_cast<float>(cand.y);
        outBox[2] = static_cast<float>(cand.width);
        outBox[3] = static_cast<float>(cand.height);
        *outSim = sim;

        std::lock_guard<std::mutex> lock(windowMutex_);
        if (window_ != nullptr) {
            drawToWindow(frame);
        }
        return state_;
    }

    /**
     * 建立目标模板：色调直方图（CamShift 定位用，掩码内有效像素
     * 不足时回退为全 ROI 统计）+ 综合特征（相似度验证用）。
     */
    void buildTemplate(const cv::Mat &hue, const cv::Mat &mask,
                       const cv::Mat &gray, const cv::Rect &roi) {
        cv::Mat roiHue = hue(roi);
        cv::Mat roiMask = mask(roi);
        const int valid = cv::countNonZero(roiMask);
        if (valid < roi.area() / 10) {
            // 近灰目标：色调不可靠，回退为无掩码统计
            roiMask = cv::Mat();
        }
        hueHist_ = cv::Mat();
        const int chans[1] = {0};
        const int bins[1] = {HUE_BINS};
        const float *ranges[] = {hueRanges_};
        cv::calcHist(&roiHue, 1, chans, roiMask, hueHist_, 1, bins,
                     ranges);
        cv::normalize(hueHist_, hueHist_, 0, 255, cv::NORM_MINMAX);

        grayHistogram(gray(roi), template_.color);
        textureHistogram(gray(roi), template_.texture);
    }

    /** 自适应模板：特征与色调直方图按小权重融合当前观测。 */
    void adaptTemplate(const Feature &candFeat, const cv::Mat &hue,
                       const cv::Mat &mask, const cv::Rect &roi) {
        for (int i = 0; i < GRAY_BINS; i++) {
            template_.color[i] =
                    (1.f - ADAPT_ALPHA) * template_.color[i] +
                    ADAPT_ALPHA * candFeat.color[i];
        }
        for (int i = 0; i < ORI_BINS; i++) {
            template_.texture[i] =
                    (1.f - ADAPT_ALPHA) * template_.texture[i] +
                    ADAPT_ALPHA * candFeat.texture[i];
        }
        // 色调直方图同样融合（保持归一化范围）
        cv::Mat cur = cv::Mat();
        const int chans[1] = {0};
        const int bins[1] = {HUE_BINS};
        cv::Mat roiMask = mask(roi);
        if (cv::countNonZero(roiMask) < roi.area() / 10) {
            roiMask = cv::Mat();
        }
        const float *ranges[] = {hueRanges_};
        cv::Mat roiHue = hue(roi);
        cv::calcHist(&roiHue, 1, chans, roiMask, cur, 1, bins, ranges);
        cv::normalize(cur, cur, 0, 255, cv::NORM_MINMAX);
        hueHist_ = (1.f - ADAPT_ALPHA) * hueHist_ + ADAPT_ALPHA * cur;
        cv::normalize(hueHist_, hueHist_, 0, 255, cv::NORM_MINMAX);
    }

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

        const int srcLineSize = src.cols * 4;      // 源行字节数（RGBA）
        const int dstLineSize = buffer.stride * 4; // 目标行字节数（含 stride）
        uint8_t *dstData = static_cast<uint8_t *>(buffer.bits);
        for (int i = 0; i < buffer.height; ++i) {
            memcpy(dstData + i * dstLineSize,
                   src.data + i * srcLineSize, srcLineSize);
        }
        ANativeWindow_unlockAndPost(window_);
    }

    // ---- 状态（stateMutex_ 保护） ----
    bool armed_ = false;          // 待下一帧提取模板
    bool hasTemplate_ = false;    // 模板是否有效
    int state_ = STATE_IDLE;
    cv::Rect pendingRoi_;         // 手势框选 ROI（显示图像坐标）
    cv::Rect trackWindow_;        // CamShift 搜索窗口
    cv::Mat hueHist_;             // 模板色调直方图（32 bins）
    Feature template_;            // 模板综合特征（检索特征空间）
    int lostCount_ = 0;           // 连续低相似度帧计数
    int frameIdx_ = 0;            // 帧计数（自适应模板周期用）
    const float hueRanges_[2] = {0.f, 180.f};

    std::mutex stateMutex_;
    std::mutex windowMutex_;
    ANativeWindow *window_ = nullptr;
};

inline ObjectTracker *asTracker(jlong handle) {
    return reinterpret_cast<ObjectTracker *>(handle);
}

} // namespace

extern "C" {

/** 创建实物跟踪器（无需级联模型），失败返回 0。 */
JNIEXPORT jlong JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeCreate(
        JNIEnv *, jobject) {
    auto *tracker = new ObjectTracker();
    return reinterpret_cast<jlong>(tracker);
}

/** 释放跟踪器与渲染窗口。 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    delete asTracker(handle);
}

/** 绑定渲染 Surface（surface 为 null 时解绑）。 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeSetSurface(
        JNIEnv *env, jobject, jlong handle, jobject surface) {
    ObjectTracker *tracker = asTracker(handle);
    if (tracker == nullptr) {
        return;
    }
    if (surface != nullptr) {
        tracker->setWindow(ANativeWindow_fromSurface(env, surface));
    } else {
        tracker->setWindow(nullptr);
    }
}

/** 清除目标模板与跟踪状态（重新框选 / 切换摄像头后调用）。 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeResetTracking(
        JNIEnv *, jobject, jlong handle) {
    ObjectTracker *tracker = asTracker(handle);
    if (tracker != nullptr) {
        tracker->resetTracking();
    }
}

/**
 * 手势框选目标：ROI 为显示图像坐标（与渲染帧一致，含方向校正），
 * 下一帧到达时提取模板并开始跟踪。
 */
JNIEXPORT void JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeSelectObject(
        JNIEnv *, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    ObjectTracker *tracker = asTracker(handle);
    if (tracker != nullptr) {
        tracker->selectObject(x, y, w, h);
    }
}

/**
 * 提交一帧 NV21 预览数据（跟踪 + 验证 + 渲染）。
 *
 * @param data      NV21 数据（长度 = w*h*3/2）
 * @param w/h       预览宽高
 * @param rotation  顺时针旋转校正角（0/90/180/270）
 * @param mirror    前置摄像头是否水平镜像
 * @return [状态, x, y, w, h, 相似度]：状态 0=待框选 1=已框选
 *         2=跟踪中（x/y/w/h/相似度有效）3=目标丢失
 */
JNIEXPORT jfloatArray JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativePostFrame(
        JNIEnv *env, jobject, jlong handle,
        jbyteArray data, jint w, jint h, jint rotation, jboolean mirror) {
    jfloatArray out = env->NewFloatArray(6);
    if (out == nullptr) {
        return nullptr;
    }
    jfloat result[6] = {0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
    ObjectTracker *tracker = asTracker(handle);
    if (tracker == nullptr) {
        env->SetFloatArrayRegion(out, 0, 6, result);
        return out;
    }
    jsize len = env->GetArrayLength(data);
    if (len < w * h * 3 / 2) {
        env->SetFloatArrayRegion(out, 0, 6, result);
        return out;
    }

    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    float box[4];
    float sim = 0.f;
    int state = tracker->postFrame(
            reinterpret_cast<const uint8_t *>(buf),
            w, h, rotation, mirror == JNI_TRUE, box, &sim);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);

    result[0] = static_cast<jfloat>(state);
    result[1] = box[0];
    result[2] = box[1];
    result[3] = box[2];
    result[4] = box[3];
    result[5] = sim;
    env->SetFloatArrayRegion(out, 0, 6, result);
    return out;
}

/** 返回 OpenCV 版本串（诊断用）。 */
JNIEXPORT jstring JNICALL
Java_com_wangyao_camerarecognition_jni_ObjectTrackJni_nativeOpencvVersion(
        JNIEnv *env, jobject) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

} // extern "C"
