#ifndef OPENCVPROCESSING_OBJECTTRACKERLOGIC_H
#define OPENCVPROCESSING_OBJECTTRACKERLOGIC_H

#include <opencv2/core.hpp>
#include <android/native_window.h>
#include <mutex>
#include <vector>

namespace camerarecognition {

/** 跟踪器状态。 */
enum TrackState {
    STATE_IDLE = 0,
    STATE_ARMED = 1,
    STATE_TRACKING = 2,
    STATE_LOST = 3,
};

/** 一帧的综合特征向量。 */
struct Feature {
    static constexpr int GRAY_BINS = 64;
    static constexpr int ORI_BINS = 18;
    static constexpr int LBP_BINS = 256;

    float color[GRAY_BINS] = {0};
    float texture[ORI_BINS] = {0};
    float lbp[LBP_BINS] = {0};
};

/**
 * 框选实物实时跟踪器逻辑实现。
 */
class ObjectTrackerLogic {
public:
    ObjectTrackerLogic();
    ~ObjectTrackerLogic();

    void setWindow(ANativeWindow *window);
    void resetTracking();
    void selectObject(int x, int y, int w, int h);
    int postFrame(const uint8_t *data, int w, int h,
                  int rotation, bool mirror,
                  float outBox[4], float *outSim);

    bool getTemplateThumb(std::vector<uint8_t> &out);
    bool getTrackedThumb(std::vector<uint8_t> &out);

private:
    cv::Mat decodeFrame(const uint8_t *data, int w, int h, int rotation, bool mirror);
    void renderPlain(const uint8_t *data, int w, int h, int rotation, bool mirror);
    int trackLocked(const uint8_t *data, int w, int h, int rotation, bool mirror,
                    float outBox[4], float *outSim);
    void buildTemplate(const cv::Mat &hue, const cv::Mat &mask, const cv::Mat &gray, const cv::Rect &roi);
    void adaptTemplate(const Feature &candFeat, const cv::Mat &hue, const cv::Mat &mask, const cv::Rect &roi);
    void drawToWindow(const cv::Mat &src);
    static void packThumb(const cv::Mat &m, std::vector<uint8_t> &out);

    bool armed_ = false;
    bool hasTemplate_ = false;
    int state_ = STATE_IDLE;
    cv::Rect pendingRoi_;
    cv::Rect trackWindow_;
    cv::Mat hueHist_;
    Feature template_;
    int lostCount_ = 0;
    int frameIdx_ = 0;
    cv::Mat templateThumb_;
    cv::Mat trackedThumb_;

    int lastSrcW_ = -1;
    int lastSrcH_ = -1;
    int lastRot_ = -1;
    bool lastMirror_ = false;
    const float hueRanges_[2] = {0.f, 180.f};

    std::mutex stateMutex_;
    std::mutex windowMutex_;
    ANativeWindow *window_ = nullptr;
};

} // namespace camerarecognition

#endif //OPENCVPROCESSING_OBJECTTRACKERLOGIC_H
