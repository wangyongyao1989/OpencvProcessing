#include "ObjectTrackerLogic.h"
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <opencv2/video/tracking.hpp>
#include <algorithm>
#include <cmath>
#include <cstring>

#define TAG "CR_ObjectTrackerLogic"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace camerarecognition {

/** 综合相似度权重。 */
constexpr float WEIGHT_COLOR = 0.4f;
constexpr float WEIGHT_SOBEL = 0.3f;
constexpr float WEIGHT_LBP = 0.3f;
constexpr int HUE_BINS = 32;
constexpr int MIN_ROI_SIZE = 24;
constexpr float SIM_LOST = 0.35f;
constexpr int LOST_FRAMES = 15;
constexpr float SIM_ADAPT = 0.80f;
constexpr int ADAPT_INTERVAL = 5;
constexpr float ADAPT_ALPHA = 0.2f;
constexpr int THUMB_INTERVAL = 5;
constexpr int LOG_INTERVAL = 15;

// ---- 特征提取辅助函数 ----

void grayHistogram(const cv::Mat &gray, float *hist) {
    std::memset(hist, 0, sizeof(float) * Feature::GRAY_BINS);
    if (gray.empty()) return;
    // 【正确性修复】调用方传进来的几乎都是 gray(roi) 这种子视图，其 step 等于
    // 整帧宽度而不等于 ROI 宽度。原写法 `const uint8_t *p = gray.data;` 后线性读
    // rows*cols 个字节，统计到的其实是「从 ROI 左上角起的一维内存段」：
    // 每行行尾把 ROI 之外的像素算了进来，而 ROI 最后几行的右侧像素被漏掉，
    // 只有 ROI 恰好贴满整行时才碰巧正确。改为逐行取 ptr，直方图才真正对应
    // 这块矩形区域（读的范围仍在父缓冲内，不会越界，是「值错」不是「崩」）。
    for (int y = 0; y < gray.rows; y++) {
        const uint8_t *row = gray.ptr<uint8_t>(y);
        for (int x = 0; x < gray.cols; x++) {
            hist[row[x] * Feature::GRAY_BINS / 256]++;
        }
    }
    const int n = gray.rows * gray.cols;
    const float inv = 1.0f / static_cast<float>(n);
    for (int i = 0; i < Feature::GRAY_BINS; i++) hist[i] *= inv;
}

void textureHistogram(const cv::Mat &gray, float *hist) {
    std::memset(hist, 0, sizeof(float) * Feature::ORI_BINS);
    if (gray.cols < 3 || gray.rows < 3) return;
    const int w = gray.cols;
    const int h = gray.rows;
    // 【正确性修复】同上：`row[x ± w]` 把「上一行/下一行」当成了 ±w 字节，
    // 但子视图的行间距是 step（= 整帧宽），不是 ROI 宽。原实现在非贴边 ROI 上
    // 取到的是错误位置的邻域。用 stride 索引后，ptr(y) 本身按 step 定位行首，
    // 邻居偏移改为 ±stride ⇒ 统计的是真正的 3×3 邻域。
    // mag/ori 两个暂存数组仍按 w 行优先存放，第二趟的累加次序不变。
    const int stride = static_cast<int>(gray.step);
    std::vector<float> mag(static_cast<size_t>(w) * h, 0.f);
    std::vector<float> ori(static_cast<size_t>(w) * h, 0.f);
    double sumMag = 0.0;
    int cnt = 0;
    for (int y = 1; y < h - 1; y++) {
        const uint8_t *row = gray.ptr<uint8_t>(y);
        for (int x = 1; x < w - 1; x++) {
            const int idx = y * w + x;
            const float gx = (row[x - stride + 1] + 2 * row[x + 1] + row[x + stride + 1]) -
                             (row[x - stride - 1] + 2 * row[x - 1] + row[x + stride - 1]);
            const float gy = (row[x + stride - 1] + 2 * row[x + stride] + row[x + stride + 1]) -
                             (row[x - stride - 1] + 2 * row[x - stride] + row[x - stride + 1]);
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
            float deg = ori[i] * 57.29577951f;
            if (deg < 0) deg += 180.f;
            int bin = static_cast<int>(deg / 180.f * Feature::ORI_BINS);
            if (bin > Feature::ORI_BINS - 1) bin = Feature::ORI_BINS - 1;
            hist[bin] += m;
            total += m;
        }
    }
    if (total > 0.f) {
        const float inv = 1.0f / total;
        for (int i = 0; i < Feature::ORI_BINS; i++) hist[i] *= inv;
    }
}

void lbpHistogram(const cv::Mat &gray, float *hist) {
    std::memset(hist, 0, sizeof(float) * Feature::LBP_BINS);
    if (gray.cols < 3 || gray.rows < 3) return;
    for (int y = 1; y < gray.rows - 1; y++) {
        const uint8_t *prev = gray.ptr<uint8_t>(y - 1);
        const uint8_t *curr = gray.ptr<uint8_t>(y);
        const uint8_t *next = gray.ptr<uint8_t>(y + 1);
        for (int x = 1; x < gray.cols - 1; x++) {
            uint8_t center = curr[x];
            uint8_t code = 0;
            if (prev[x - 1] >= center) code |= 1;
            if (prev[x] >= center) code |= 2;
            if (prev[x + 1] >= center) code |= 4;
            if (curr[x + 1] >= center) code |= 8;
            if (next[x + 1] >= center) code |= 16;
            if (next[x] >= center) code |= 32;
            if (next[x - 1] >= center) code |= 64;
            if (curr[x - 1] >= center) code |= 128;
            hist[code]++;
        }
    }
    float total = static_cast<float>((gray.cols - 2) * (gray.rows - 2));
    if (total > 0.f) {
        float inv = 1.0f / total;
        for (int i = 0; i < Feature::LBP_BINS; i++) hist[i] *= inv;
    }
}

float histogramIntersection(const float *a, const float *b, int n) {
    float s = 0.f;
    for (int i = 0; i < n; i++) s += std::min(a[i], b[i]);
    return s;
}

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

float comprehensiveSimilarity(const Feature &a, const Feature &b) {
    return WEIGHT_COLOR * histogramIntersection(a.color, b.color, Feature::GRAY_BINS) +
           WEIGHT_SOBEL * cosine(a.texture, b.texture, Feature::ORI_BINS) +
           WEIGHT_LBP * histogramIntersection(a.lbp, b.lbp, Feature::LBP_BINS);
}

// ---- ObjectTrackerLogic 实现 ----

ObjectTrackerLogic::ObjectTrackerLogic() {}

ObjectTrackerLogic::~ObjectTrackerLogic() {
    setWindow(nullptr);
}

void ObjectTrackerLogic::setWindow(ANativeWindow *window) {
    std::lock_guard<std::mutex> lock(windowMutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    if (window != nullptr) {
        window_ = window;
    }
}

void ObjectTrackerLogic::resetTracking() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    hasTemplate_ = false;
    armed_ = false;
    state_ = STATE_IDLE;
    lostCount_ = 0;
    frameIdx_ = 0;
    templateThumb_.release();
    trackedThumb_.release();
}

void ObjectTrackerLogic::selectObject(int x, int y, int w, int h) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (w < MIN_ROI_SIZE || h < MIN_ROI_SIZE) return;
    pendingRoi_ = cv::Rect(x, y, w, h);
    armed_ = true;
    state_ = STATE_ARMED;
}

int ObjectTrackerLogic::postFrame(const uint8_t *data, int w, int h,
                                  int rotation, bool mirror,
                                  float outBox[4], float *outSim) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    outBox[0] = outBox[1] = outBox[2] = outBox[3] = 0.f;
    *outSim = 0.f;
    if (state_ == STATE_IDLE) {
        renderPlain(data, w, h, rotation, mirror);
        return STATE_IDLE;
    }
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

bool ObjectTrackerLogic::getTemplateThumb(std::vector<uint8_t> &out) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (templateThumb_.empty()) return false;
    packThumb(templateThumb_, out);
    return true;
}

bool ObjectTrackerLogic::getTrackedThumb(std::vector<uint8_t> &out) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (trackedThumb_.empty()) return false;
    packThumb(trackedThumb_, out);
    return true;
}

cv::Mat ObjectTrackerLogic::decodeFrame(const uint8_t *data, int w, int h,
                                        int rotation, bool mirror) {
    cv::Mat src(h + h / 2, w, CV_8UC1, const_cast<uint8_t *>(data));
    // 输出改用成员缓冲：原来 cvtColor/flip/rotate 都写在一个局部 Mat 上，
    // 每帧都要 malloc+free 一次全帧 RGBA（640×480≈1.2 MB），
    // 尺寸一致时复用成员缓冲即可，写入的像素值完全不变。
    cv::cvtColor(src, frameBuf_, cv::COLOR_YUV2RGBA_NV21);
    if (mirror) cv::flip(frameBuf_, frameBuf_, 1);
    switch (rotation) {
        case 90:  cv::rotate(frameBuf_, frameBuf_, cv::ROTATE_90_CLOCKWISE); break;
        case 180: cv::rotate(frameBuf_, frameBuf_, cv::ROTATE_180); break;
        case 270: cv::rotate(frameBuf_, frameBuf_, cv::ROTATE_90_COUNTERCLOCKWISE); break;
        default: break;
    }
    return frameBuf_;   // 头部引用；调用方与本次调用同处 stateMutex_ 临界区
}

void ObjectTrackerLogic::renderPlain(const uint8_t *data, int w, int h,
                                     int rotation, bool mirror) {
    cv::Mat frame = decodeFrame(data, w, h, rotation, mirror);
    std::lock_guard<std::mutex> lock(windowMutex_);
    if (window_ != nullptr) drawToWindow(frame);
}

int ObjectTrackerLogic::trackLocked(const uint8_t *data, int w, int h,
                                    int rotation, bool mirror,
                                    float outBox[4], float *outSim) {
    cv::Mat frame = decodeFrame(data, w, h, rotation, mirror);
    // 中间量一律用成员缓冲（尺寸不变时 OpenCV 不再重新分配），
    // 每帧少 4 次全帧 malloc/free；写入的像素值与原实现逐位相同。
    cv::Mat &gray = grayBuf_;
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    // 每帧省一次全帧拷贝：RGBA 直接转 HSV（OpenCV 对 COLOR_RGB2HSV 注册了
    // 4 通道内核，按前 3 通道计算、alpha 忽略，与先 RGBA2RGB 再 RGB2HSV
    // 结果逐位相同）；H 通道用 extractChannel 单独取出，不再 split 出
    // 用不到的 S/V 两个全帧缓冲。
    cv::Mat &hsv = hsvBuf_;
    cv::cvtColor(frame, hsv, cv::COLOR_RGB2HSV);
    cv::Mat &mask = maskBuf_;
    cv::inRange(hsv, cv::Scalar(0, 30, 30), cv::Scalar(180, 255, 255), mask);
    cv::Mat &hue = hueBuf_;
    cv::extractChannel(hsv, hue, 0);

    if (armed_) {
        cv::Rect roi = pendingRoi_ & cv::Rect(0, 0, frame.cols, frame.rows);
        if (roi.width < MIN_ROI_SIZE || roi.height < MIN_ROI_SIZE) {
            armed_ = false; state_ = STATE_IDLE;
            std::lock_guard<std::mutex> lock(windowMutex_);
            if (window_ != nullptr) drawToWindow(frame);
            return STATE_IDLE;
        }
        buildTemplate(hue, mask, gray, roi);
        armed_ = false; hasTemplate_ = true; state_ = STATE_TRACKING;
        trackWindow_ = roi; lostCount_ = 0; frameIdx_ = 0;
        templateThumb_ = frame(roi).clone();
        trackedThumb_.release();
    }

    if (!hasTemplate_) {
        std::lock_guard<std::mutex> lock(windowMutex_);
        if (window_ != nullptr) drawToWindow(frame);
        return state_;
    }

    cv::Mat &prob = probBuf_;
    const float *ranges[] = {hueRanges_};
    cv::calcBackProject(&hue, 1, 0, hueHist_, prob, ranges, 1, true);
    prob.convertTo(prob, CV_8U);
    cv::bitwise_and(prob, mask, prob);

    const cv::Rect prevWin = trackWindow_;
    if (state_ == STATE_LOST && frameIdx_ % 5 == 0) {
        double maxVal; cv::Point maxLoc;
        cv::minMaxLoc(prob, nullptr, &maxVal, nullptr, &maxLoc);
        if (maxVal > 128) {
            trackWindow_ = cv::Rect(maxLoc.x - prevWin.width / 2, maxLoc.y - prevWin.height / 2, prevWin.width, prevWin.height);
            trackWindow_ &= cv::Rect(0, 0, frame.cols, frame.rows);
        }
    }

    cv::TermCriteria criteria(cv::TermCriteria::EPS | cv::TermCriteria::COUNT, 10, 1);
    cv::RotatedRect trackBox = cv::CamShift(prob, trackWindow_, criteria);
    cv::Rect cand = trackBox.boundingRect() & cv::Rect(0, 0, frame.cols, frame.rows);
    if (cand.width < MIN_ROI_SIZE || cand.height < MIN_ROI_SIZE) cand = trackWindow_;

    Feature candFeat;
    grayHistogram(gray(cand), candFeat.color);
    textureHistogram(gray(cand), candFeat.texture);
    lbpHistogram(gray(cand), candFeat.lbp);
    float sim = comprehensiveSimilarity(template_, candFeat);

    frameIdx_++;
    if (sim < SIM_LOST) {
        if (++lostCount_ > LOST_FRAMES) {
            state_ = STATE_LOST;
            *outSim = sim;
            // frame 是 RGBA（见 decodeFrame 的 COLOR_YUV2RGBA_NV21），
            // 故红框写作 (255,0,0,255)；旧写法 (0,0,255) 在 RGBA 下是蓝色。
            cv::rectangle(frame, cand, cv::Scalar(255, 0, 0, 255), 2);
            std::lock_guard<std::mutex> lock(windowMutex_);
            if (window_ != nullptr) drawToWindow(frame);
            return STATE_LOST;
        }
    } else {
        lostCount_ = 0;
        state_ = STATE_TRACKING;
        if (sim > SIM_ADAPT && frameIdx_ % ADAPT_INTERVAL == 0) {
            adaptTemplate(candFeat, hue, mask, cand);
        }
        if (frameIdx_ % THUMB_INTERVAL == 0) trackedThumb_ = frame(cand).clone();
    }

    // 绿框与标签：G 通道在 BGR/RGBA 下都是绿色，补上显式 alpha 以免误读。
    cv::rectangle(frame, cand, cv::Scalar(0, 255, 0, 255), 3);
    char label[32];
    std::snprintf(label, sizeof(label), "sim %.0f%%", sim * 100.f);
    cv::putText(frame, label, cv::Point(cand.x, std::max(14, cand.y - 6)),
                cv::FONT_HERSHEY_SIMPLEX, 0.55, cv::Scalar(0, 255, 0, 255), 2);

    outBox[0] = static_cast<float>(cand.x);
    outBox[1] = static_cast<float>(cand.y);
    outBox[2] = static_cast<float>(cand.width);
    outBox[3] = static_cast<float>(cand.height);
    *outSim = sim;

    std::lock_guard<std::mutex> lock(windowMutex_);
    if (window_ != nullptr) drawToWindow(frame);
    return state_;
}

void ObjectTrackerLogic::buildTemplate(const cv::Mat &hue, const cv::Mat &mask,
                                       const cv::Mat &gray, const cv::Rect &roi) {
    cv::Mat roiHue = hue(roi);
    cv::Mat roiMask = mask(roi);
    if (cv::countNonZero(roiMask) < roi.area() / 10) roiMask = cv::Mat();
    hueHist_ = cv::Mat();
    const int chans[1] = {0};
    const int bins[1] = {HUE_BINS};
    const float *ranges[] = {hueRanges_};
    cv::calcHist(&roiHue, 1, chans, roiMask, hueHist_, 1, bins, ranges);
    cv::normalize(hueHist_, hueHist_, 0, 255, cv::NORM_MINMAX);
    grayHistogram(gray(roi), template_.color);
    textureHistogram(gray(roi), template_.texture);
    lbpHistogram(gray(roi), template_.lbp);
}

void ObjectTrackerLogic::adaptTemplate(const Feature &candFeat, const cv::Mat &hue,
                                       const cv::Mat &mask, const cv::Rect &roi) {
    for (int i = 0; i < Feature::GRAY_BINS; i++) template_.color[i] = (1.f - ADAPT_ALPHA) * template_.color[i] + ADAPT_ALPHA * candFeat.color[i];
    for (int i = 0; i < Feature::ORI_BINS; i++) template_.texture[i] = (1.f - ADAPT_ALPHA) * template_.texture[i] + ADAPT_ALPHA * candFeat.texture[i];
    for (int i = 0; i < Feature::LBP_BINS; i++) template_.lbp[i] = (1.f - ADAPT_ALPHA) * template_.lbp[i] + ADAPT_ALPHA * candFeat.lbp[i];
    cv::Mat cur = cv::Mat();
    const int chans[1] = {0};
    const int bins[1] = {HUE_BINS};
    cv::Mat roiMask = mask(roi);
    if (cv::countNonZero(roiMask) < roi.area() / 10) roiMask = cv::Mat();
    const float *ranges[] = {hueRanges_};
    cv::Mat roiHue = hue(roi);
    cv::calcHist(&roiHue, 1, chans, roiMask, cur, 1, bins, ranges);
    cv::normalize(cur, cur, 0, 255, cv::NORM_MINMAX);
    hueHist_ = (1.f - ADAPT_ALPHA) * hueHist_ + ADAPT_ALPHA * cur;
    cv::normalize(hueHist_, hueHist_, 0, 255, cv::NORM_MINMAX);
}

void ObjectTrackerLogic::drawToWindow(const cv::Mat &src) {
    if (window_ == nullptr || src.empty()) return;

    if (ANativeWindow_setBuffersGeometry(window_, src.cols, src.rows,
                                         WINDOW_FORMAT_RGBA_8888) != 0) {
        LOGE("drawToWindow: setBuffersGeometry failed, frame skipped");
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        // 与 FaceTracker 同样：lock 失败多为瞬时（无可用 buffer / surface 暂不可用），
        // 旧实现直接 release 掉 window_ 并置空 → 一次失败就永久黑屏，
        // 而且该引用由 setWindow() 持有，这里再释放属于重复 unref。
        LOGD("drawToWindow: lock failed, frame skipped");
        return;
    }

    // 行数与行字节数都取「源」与「目标缓冲」的较小值：
    // buffer.stride 常按硬件对齐大于 src.cols；buffer.height 也可能大于 src.rows，
    // 不截断会越界读 src.data / 越界写 buffer.bits。
    const int srcLineSize = src.cols * 4;
    const int dstLineSize = buffer.stride * 4;
    const int lineBytes = std::min(srcLineSize, dstLineSize);
    const int rows = std::min(buffer.height, src.rows);
    uint8_t *dstData = static_cast<uint8_t *>(buffer.bits);
    for (int i = 0; i < rows; ++i) {
        memcpy(dstData + i * dstLineSize, src.data + i * srcLineSize, lineBytes);
    }
    ANativeWindow_unlockAndPost(window_);
}

void ObjectTrackerLogic::packThumb(const cv::Mat &m, std::vector<uint8_t> &out) {
    out.resize(8 + m.total() * m.elemSize());
    const int32_t w = m.cols;
    const int32_t h = m.rows;
    std::memcpy(out.data(), &w, 4);
    std::memcpy(out.data() + 4, &h, 4);
    std::memcpy(out.data() + 8, m.data, out.size() - 8);
}

} // namespace camerarecognition
