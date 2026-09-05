#include "FaceTracker.h"
#include <android/log.h>
#include <opencv2/imgproc.hpp>
#include <algorithm>

#define TAG "CR_FaceTracker"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace camerarecognition {

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

MultiCascadeAdapter::MultiCascadeAdapter(const std::vector<std::string> &facePaths,
                                         const std::vector<std::string> &featurePaths,
                                         bool trackMode)
        : IDetector(), trackMode_(trackMode) {
    bool frontalKept = false;
    for (const std::string &p : facePaths) {
        faces_.emplace_back();
        faces_.back().isProfile = p.find("profileface") != std::string::npos;
        try {
            if (!faces_.back().classifier.load(p)) {
                LOGE("face cascade load failed: %s", p.c_str());
                faces_.pop_back();
                continue;
            }
        } catch (const cv::Exception &e) {
            LOGE("face cascade load exception (%s): %s", e.what(), p.c_str());
            faces_.pop_back();
            continue;
        }
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
                LOGE("feature cascade load exception (%s): %s", e.what(), p.c_str());
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
                delete c;
                continue;
            }
            LOGD("loaded feature cascade: %s", p.c_str());
        }
    }

    setScaleFactor(trackMode_ ? 1.1f : 1.08f);
    setMinNeighbours(3);
}

MultiCascadeAdapter::~MultiCascadeAdapter() {
    delete eye_;
    delete glasses_;
    delete nose_;
    delete mouth_;
}

bool MultiCascadeAdapter::empty() const {
    return faces_.empty();
}

void MultiCascadeAdapter::detect(const cv::Mat &image, std::vector<cv::Rect> &objects) {
    objects.clear();
    if (faces_.empty() || image.empty()) return;
    try {
        detectInternal(image, objects);
    } catch (const cv::Exception &e) {
        LOGE("detect exception: %s", e.what());
        objects.clear();
    }
}

void MultiCascadeAdapter::detectInternal(const cv::Mat &image, std::vector<cv::Rect> &objects) {
    cv::Mat work = image;
    cv::Mat small;
    if (!trackMode_) {
        cv::resize(image, small, cv::Size(),
                   FULL_SCAN_DOWNSCALE, FULL_SCAN_DOWNSCALE,
                   cv::INTER_LINEAR);
        work = small;
    }

    const float scale = trackMode_ ? 1.f : FULL_SCAN_DOWNSCALE;
    const cv::Size minS(cvRound(minObjSize.width * scale),
                        cvRound(minObjSize.height * scale));
    std::vector<RawBox> raw;
    for (FaceCascade &fc : faces_) {
        std::vector<cv::Rect> found;
        fc.classifier.detectMultiScale(
                work, found, scaleFactor, minNeighbours, 0, minS, maxObjSize);
        for (const cv::Rect &r : found) {
            raw.push_back({r, fc.isProfile});
        }
        if (fc.isProfile) {
            cv::Mat flipped;
            cv::flip(work, flipped, 1);
            found.clear();
            fc.classifier.detectMultiScale(
                    flipped, found, scaleFactor, minNeighbours, 0, minS, maxObjSize);
            const int w = work.cols;
            for (cv::Rect r : found) {
                r.x = w - r.x - r.width;
                raw.push_back({r, true});
            }
        }
    }

    if (!trackMode_) {
        for (RawBox &b : raw) {
            b.r.x = cvRound(b.r.x / FULL_SCAN_DOWNSCALE);
            b.r.y = cvRound(b.r.y / FULL_SCAN_DOWNSCALE);
            b.r.width = cvRound(b.r.width / FULL_SCAN_DOWNSCALE);
            b.r.height = cvRound(b.r.height / FULL_SCAN_DOWNSCALE);
        }
    }

    std::sort(raw.begin(), raw.end(), [](const RawBox &a, const RawBox &b) {
        return a.r.area() > b.r.area();
    });
    std::vector<std::vector<RawBox>> clusters;
    std::vector<cv::Rect> reps;
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

    for (size_t i = 0; i < clusters.size(); i++) {
        const cv::Rect box = reps[i];
        bool fromProfile = false;
        for (const RawBox &m : clusters[i]) fromProfile |= m.fromProfile;

        if (!trackMode_ && !fromProfile &&
            box.width >= VERIFY_MIN_SIZE && box.height >= VERIFY_MIN_SIZE &&
            !verifyFace(image, box)) {
            continue;
        }
        objects.push_back(box);
    }
}

bool MultiCascadeAdapter::verifyFace(const cv::Mat &gray, const cv::Rect &r) {
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

FaceTracker::FaceTracker() {}

FaceTracker::~FaceTracker() {
    release();
}

bool FaceTracker::init(const std::vector<std::string> &facePaths,
                       const std::vector<std::string> &featurePaths) {
    cv::Ptr<MultiCascadeAdapter> mainDetector =
            cv::makePtr<MultiCascadeAdapter>(facePaths, featurePaths, false);
    cv::Ptr<MultiCascadeAdapter> trackingDetector =
            cv::makePtr<MultiCascadeAdapter>(facePaths, featurePaths, true);
    if (mainDetector->empty() || trackingDetector->empty()) {
        LOGE("no valid face cascade loaded");
        return false;
    }

    mainDetector->setMinObjectSize(cv::Size(48, 48));
    trackingDetector->setMinObjectSize(cv::Size(48, 48));

    clahe_ = cv::createCLAHE(2.0, cv::Size(8, 8));

    DetectionBasedTracker::Parameters params;
    tracker_ = new DetectionBasedTracker(mainDetector, trackingDetector, params);
    tracker_->run();
    LOGD("DetectionBasedTracker started (multi-cascade: %d face models)",
         static_cast<int>(facePaths.size()));
    return true;
}

void FaceTracker::release() {
    if (tracker_ != nullptr) {
        tracker_->stop();
        delete tracker_;
        tracker_ = nullptr;
    }
    setWindow(nullptr);
}

void FaceTracker::resetTracking() {
    if (tracker_ != nullptr) {
        tracker_->resetTracking();
    }
}

void FaceTracker::setWindow(ANativeWindow *window) {
    std::lock_guard<std::mutex> lock(windowMutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    if (window != nullptr) {
        window_ = window;
    }
}

int FaceTracker::postFrame(const uint8_t *data, int w, int h,
                           int rotation, bool mirror) {
    if (tracker_ == nullptr) return 0;

    cv::Mat src(h + h / 2, w, CV_8UC1, const_cast<uint8_t *>(data));
    cv::cvtColor(src, src, cv::COLOR_YUV2RGBA_NV21);

    if (mirror) cv::flip(src, src, 1);

    switch (rotation) {
        case 90:  cv::rotate(src, src, cv::ROTATE_90_CLOCKWISE); break;
        case 180: cv::rotate(src, src, cv::ROTATE_180); break;
        case 270: cv::rotate(src, src, cv::ROTATE_90_COUNTERCLOCKWISE); break;
        default: break;
    }

    cv::Mat gray;
    cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
    clahe_->apply(gray, gray);

    tracker_->process(gray);
    std::vector<cv::Rect> faces;
    tracker_->getObjects(faces);

    for (const cv::Rect &face : faces) {
        cv::rectangle(src, face, cv::Scalar(0, 0, 255), 3);
    }

    {
        std::lock_guard<std::mutex> lock(windowMutex_);
        if (window_ != nullptr) drawToWindow(src);
    }
    return static_cast<int>(faces.size());
}

void FaceTracker::drawToWindow(const cv::Mat &src) {
    ANativeWindow_setBuffersGeometry(window_, src.cols, src.rows, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        ANativeWindow_release(window_);
        window_ = nullptr;
        return;
    }

    int srcLineSize = src.cols * 4;
    int dstLineSize = buffer.stride * 4;
    uint8_t *dstData = static_cast<uint8_t *>(buffer.bits);
    for (int i = 0; i < buffer.height; ++i) {
        memcpy(dstData + i * dstLineSize, src.data + i * srcLineSize, srcLineSize);
    }
    ANativeWindow_unlockAndPost(window_);
}

} // namespace camerarecognition
