#ifndef OPENCVPROCESSING_FACETRACKER_H
#define OPENCVPROCESSING_FACETRACKER_H

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>
#include <opencv2/objdetect/detection_based_tracker.hpp>
#include <android/native_window.h>
#include <mutex>
#include <string>
#include <vector>

namespace camerarecognition {

/**
 * 多级联融合检测适配器：将 Haar 级联模型适配为 DetectionBasedTracker::IDetector 接口。
 */
class MultiCascadeAdapter : public cv::DetectionBasedTracker::IDetector {
public:
    MultiCascadeAdapter(const std::vector<std::string> &facePaths,
                        const std::vector<std::string> &featurePaths,
                        bool trackMode);
    ~MultiCascadeAdapter() override;

    bool empty() const;
    void detect(const cv::Mat &image, std::vector<cv::Rect> &objects) override;

private:
    struct FaceCascade {
        cv::CascadeClassifier classifier;
        bool isProfile;
    };

    void detectInternal(const cv::Mat &image, std::vector<cv::Rect> &objects);
    bool verifyFace(const cv::Mat &gray, const cv::Rect &r);

    std::vector<FaceCascade> faces_;
    cv::CascadeClassifier *eye_ = nullptr;
    cv::CascadeClassifier *glasses_ = nullptr;
    cv::CascadeClassifier *nose_ = nullptr;
    cv::CascadeClassifier *mouth_ = nullptr;
    bool trackMode_;

    // RawBox and boxIou are helpers, maybe keep them in cpp or as private members
};

/**
 * 相机人脸跟踪器：持有 DetectionBasedTracker 与 ANativeWindow 渲染目标。
 */
class FaceTracker {
public:
    FaceTracker();
    ~FaceTracker();

    bool init(const std::vector<std::string> &facePaths,
              const std::vector<std::string> &featurePaths);
    void release();
    void resetTracking();
    void setWindow(ANativeWindow *window);
    int postFrame(const uint8_t *data, int w, int h, int rotation, bool mirror);

private:
    void drawToWindow(const cv::Mat &src);

    cv::DetectionBasedTracker *tracker_ = nullptr;
    ANativeWindow *window_ = nullptr;
    std::mutex windowMutex_;
    cv::Ptr<cv::CLAHE> clahe_;
};

} // namespace camerarecognition

#endif //OPENCVPROCESSING_FACETRACKER_H
