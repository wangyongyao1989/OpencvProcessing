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
    const int n = gray.rows * gray.cols;
    if (n <= 0) return;
    const uint8_t *p = gray.data;
    for (int i = 0; i < n; i++) {
        hist[p[i] * Feature::GRAY_BINS / 256]++;
    }
    const float inv = 1.0f / static_cast<float>(n);
    for (int i = 0; i < Feature::GRAY_BINS; i++) hist[i] *= inv;
}

void textureHistogram(const cv::Mat &gray, float *hist) {
    std::memset(hist, 0, sizeof(float) * Feature::ORI_BINS);
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
    cv::cvtColor(src, src, cv::COLOR_YUV2RGBA_NV21);
    if (mirror) cv::flip(src, src, 1);
    switch (rotation) {
        case 90:  cv::rotate(src, src, cv::ROTATE_90_CLOCKWISE); break;
        case 180: cv::rotate(src, src, cv::ROTATE_180); break;
        case 270: cv::rotate(src, src, cv::ROTATE_90_COUNTERCLOCKWISE); break;
        default: break;
    }
    if (w != lastSrcW_ || h != lastSrcH_ || rotation != lastRot_ || mirror != lastMirror_) {
        lastSrcW_ = w; lastSrcH_ = h; lastRot_ = rotation; lastMirror_ = mirror;
    }
    return src;
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
    cv::Mat gray;
    cv::cvtColor(frame, gray, cv::COLOR_RGBA2GRAY);
    cv::Mat rgb;
    cv::cvtColor(frame, rgb, cv::COLOR_RGBA2RGB);
    cv::Mat hsv;
    cv::cvtColor(rgb, hsv, cv::COLOR_RGB2HSV);
    cv::Mat mask;
    cv::inRange(hsv, cv::Scalar(0, 30, 30), cv::Scalar(180, 255, 255), mask);
    std::vector<cv::Mat> planes;
    cv::split(hsv, planes);

    if (armed_) {
        cv::Rect roi = pendingRoi_ & cv::Rect(0, 0, frame.cols, frame.rows);
        if (roi.width < MIN_ROI_SIZE || roi.height < MIN_ROI_SIZE) {
            armed_ = false; state_ = STATE_IDLE;
            std::lock_guard<std::mutex> lock(windowMutex_);
            if (window_ != nullptr) drawToWindow(frame);
            return STATE_IDLE;
        }
        buildTemplate(planes[0], mask, gray, roi);
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

    cv::Mat prob;
    const float *ranges[] = {hueRanges_};
    cv::calcBackProject(&planes[0], 1, 0, hueHist_, prob, ranges, 1, true);
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
            cv::rectangle(frame, cand, cv::Scalar(0, 0, 255), 2);
            std::lock_guard<std::mutex> lock(windowMutex_);
            if (window_ != nullptr) drawToWindow(frame);
            return STATE_LOST;
        }
    } else {
        lostCount_ = 0;
        state_ = STATE_TRACKING;
        if (sim > SIM_ADAPT && frameIdx_ % ADAPT_INTERVAL == 0) {
            adaptTemplate(candFeat, planes[0], mask, cand);
        }
        if (frameIdx_ % THUMB_INTERVAL == 0) trackedThumb_ = frame(cand).clone();
    }

    cv::rectangle(frame, cand, cv::Scalar(0, 255, 0), 3);
    char label[32];
    std::snprintf(label, sizeof(label), "sim %.0f%%", sim * 100.f);
    cv::putText(frame, label, cv::Point(cand.x, std::max(14, cand.y - 6)),
                cv::FONT_HERSHEY_SIMPLEX, 0.55, cv::Scalar(0, 255, 0), 2);

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
    ANativeWindow_setBuffersGeometry(window_, src.cols, src.rows, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        ANativeWindow_release(window_); window_ = nullptr; return;
    }
    const int srcLineSize = src.cols * 4;
    const int dstLineSize = buffer.stride * 4;
    uint8_t *dstData = static_cast<uint8_t *>(buffer.bits);
    for (int i = 0; i < buffer.height; ++i) {
        memcpy(dstData + i * dstLineSize, src.data + i * srcLineSize, srcLineSize);
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
