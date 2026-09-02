//
// FaceDetector.cpp
// 视频识别模块：基于 Haar 级联分类器的人脸检测 JNI 实现
//（《视频识别及物体人脸识别需求文档》3.3.1 节，功能 F-01/F-05）。
//
// 原理（Viola-Jones 框架，教材第 11 章）：
// 1. 积分图：任意矩形区域像素和 O(1) 查表，Haar 特征计算极快；
// 2. AdaBoost 特征选择：从海量 Haar 特征中筛选最具判别力的
//    少量特征构建弱分类器，加权组合为强分类器；
// 3. 级联结构：前级少量特征快速排除非人脸窗口，后级精细判别，
//    整体检测速度满足实时要求。
//
// detectMultiScale 参数（需求文档 3.3.1）：
//   scaleFactor=1.1    多尺度缩放步进（1.1~1.3）
//   minNeighbors=5     NMS 去重邻域阈值（越大越严格）
//   minSize            最小人脸尺寸（由调用方按视频分辨率设定）
//

#include <jni.h>
#include <android/log.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>

#include <string>
#include <vector>

#define TAG "VR_FaceDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/**
 * 人脸检测器：持有一个 Haar 级联分类器。
 * 检测流程：灰度图 → 直方图均衡化（适应战争片明暗剧烈的场景光照）
 * → detectMultiScale 多尺度扫描 → levelWeights 转置信度。
 */
class FaceDetector {
public:
    bool load(const std::string &cascadePath) {
        if (!cascade_.load(cascadePath)) {
            LOGE("cascade load failed: %s", cascadePath.c_str());
            return false;
        }
        LOGD("cascade loaded: %s", cascadePath.c_str());
        return true;
    }

    bool ready() const { return !cascade_.empty(); }

    /**
     * 检测人脸。
     * @param gray     灰度图（行紧凑、无 padding 的 yPlane）
     * @param w/h      宽高
     * @param minFace  最小人脸边长（像素）
     * @return 检测框列表（x, y, w, h, 置信度 0~1）
     */
    std::vector<cv::Rect> detect(const cv::Mat &gray, int minFace,
                                 std::vector<double> &confidences) {
        std::vector<cv::Rect> faces;
        std::vector<int> rejectLevels;
        std::vector<double> levelWeights;

        cv::Mat eq;
        cv::equalizeHist(gray, eq);

        // levelWeights 输出版本可拿到每个检测框的级联末级权重，
        // 权重越大越像人脸；映射到 [0,1] 作为显示置信度
        cascade_.detectMultiScale(
                eq, faces, rejectLevels, levelWeights,
                1.1,   // scaleFactor
                5,     // minNeighbors（NMS 去重）
                0,     // flags
                cv::Size(minFace, minFace),
                cv::Size(),
                true); // outputRejectLevels = true，明确告知 OpenCV 填充权重以规避 clipObjects 断言错误

        confidences.clear();
        confidences.reserve(faces.size());
        for (size_t i = 0; i < faces.size(); i++) {
            double w = levelWeights.size() > i ? levelWeights[i] : 5.0;
            // 权重典型范围 [0,15]，线性映射到 (0,1]
            double conf = w / 15.0;
            if (conf > 1.0) conf = 1.0;
            if (conf < 0.05) conf = 0.05;
            confidences.push_back(conf);
        }
        return faces;
    }

private:
    cv::CascadeClassifier cascade_;
};

inline FaceDetector *asDetector(jlong handle) {
    return reinterpret_cast<FaceDetector *>(handle);
}

} // namespace

extern "C" {

/**
 * 创建检测器并加载级联模型。
 * @return native 句柄；加载失败返回 0
 */
JNIEXPORT jlong JNICALL
Java_com_wangyao_videorecognition_jni_FaceJni_nativeCreate(
        JNIEnv *env, jobject, jstring cascadePath) {
    const char *path = env->GetStringUTFChars(cascadePath, nullptr);
    auto *detector = new FaceDetector();
    bool ok = detector->load(std::string(path));
    env->ReleaseStringUTFChars(cascadePath, path);
    if (!ok) {
        delete detector;
        return 0;
    }
    return reinterpret_cast<jlong>(detector);
}

/** 释放检测器。 */
JNIEXPORT void JNICALL
Java_com_wangyao_videorecognition_jni_FaceJni_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    delete asDetector(handle);
}

/**
 * 对灰度帧执行人脸检测。
 *
 * @param gray     行紧凑灰度像素（长度 = w*h）
 * @param minFace  最小人脸边长
 * @return 扁平数组 [n, x1,y1,w1,h1,c1*1000, x2,...]，无人脸时 [0]
 */
JNIEXPORT jintArray JNICALL
Java_com_wangyao_videorecognition_jni_FaceJni_nativeDetect(
        JNIEnv *env, jobject, jlong handle,
        jbyteArray gray, jint w, jint h, jint minFace) {
    auto *detector = asDetector(handle);
    if (detector == nullptr || !detector->ready()) {
        jint none[1] = {0};
        jintArray out = env->NewIntArray(1);
        env->SetIntArrayRegion(out, 0, 1, none);
        return out;
    }

    jsize len = env->GetArrayLength(gray);
    if (len < w * h) {
        jint none[1] = {0};
        jintArray out = env->NewIntArray(1);
        env->SetIntArrayRegion(out, 0, 1, none);
        return out;
    }

    jbyte *buf = env->GetByteArrayElements(gray, nullptr);
    // 包装为 Mat（不拷贝，检测只读）
    cv::Mat mat(h, w, CV_8UC1, buf);
    std::vector<double> confs;
    std::vector<cv::Rect> faces = detector->detect(mat, minFace, confs);
    env->ReleaseByteArrayElements(gray, buf, JNI_ABORT);

    jintArray out = env->NewIntArray(1 + faces.size() * 6);
    std::vector<jint> flat;
    flat.reserve(1 + faces.size() * 6);
    flat.push_back(static_cast<jint>(faces.size()));
    for (size_t i = 0; i < faces.size(); i++) {
        flat.push_back(faces[i].x);
        flat.push_back(faces[i].y);
        flat.push_back(faces[i].width);
        flat.push_back(faces[i].height);
        flat.push_back(static_cast<jint>(confs[i] * 1000.0));
        flat.push_back(0); // 保留位（对齐 6 个字段）
    }
    env->SetIntArrayRegion(out, 0, flat.size(), flat.data());
    return out;
}

/** 返回 OpenCV 版本串（诊断用）。 */
JNIEXPORT jstring JNICALL
Java_com_wangyao_videorecognition_jni_FaceJni_nativeOpencvVersion(
        JNIEnv *env, jobject) {
    return env->NewStringUTF(cv::getVersionString().c_str());
}

} // extern "C"
