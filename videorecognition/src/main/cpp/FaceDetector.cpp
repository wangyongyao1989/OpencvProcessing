//
// FaceDetector.cpp
// 视频识别模块：多级联融合人脸检测 JNI 实现
// （《视频识别及物体人脸识别需求文档》3.3.1 节功能 F-01/F-05，
//  并按第九章 Haar 级联模型库扩展为多级联融合检测，提升识别率）。
//
// 原理（Viola-Jones 框架，教材第 11 章）：
// 1. 积分图：任意矩形区域像素和 O(1) 查表，Haar 特征计算极快；
// 2. AdaBoost 特征选择：从海量 Haar 特征中筛选最具判别力的
//    少量特征构建弱分类器，加权组合为强分类器；
// 3. 级联结构：前级少量特征快速排除非人脸窗口，后级精细判别，
//    整体检测速度满足实时要求。
//
// 多级联融合策略（需求文档「第九章 Haar级联模型文件」全部人脸
// 相关预训练 XML 模型，与 camerarecognition 模块同源）：
// 1. 正脸双模型并集：haarcascade_frontalface_default +
//    haarcascade_frontalface_alt2 —— 两个模型训练侧重不同，
//    任一命中即保留（并集直接提升召回率）；
// 2. 侧脸检测：haarcascade_profileface 级联 + 水平镜像各扫一次
//    （覆盖左右侧脸）—— 战争片中大量侧脸/转头镜头是单正脸模型
//    漏检的主因；
// 3. 面部特征验证（降误检）：较大候选框在上部检出眼睛
//    （haarcascade_eye），或同时检出鼻子（haarcascade_nose）与
//    嘴巴（haarcascade_mouth）才确认为人脸；侧脸候选跳过验证。
//    注：第九章的 mcs_nose/mcs_mouth 为 OpenCV 2 时代旧格式
//    （特征矩形超出训练窗口），OpenCV 4 加载即抛异常，已替换为
//    验证兼容的等价模型；所有模型加载均带异常防御，坏模型只会
//    被跳过并记日志，不会导致进程崩溃；
// 4. NMS 融合去重：多模型结果按 IoU 聚类，簇内取平均框、
//    置信度取簇内最大值；
// 5. 检测参数面向识别率：scaleFactor=1.08（更细的多尺度扫描）、
//    minNeighbors=3（原 5 过严），误检由 NMS + 特征验证 +
//    Kotlin 侧时间平滑共同抑制；
// 6. 光照适应：CLAHE 局部自适应直方图均衡（替代原全局
//    equalizeHist），对战争片逆光/侧光/阴影等局部光照不均更鲁棒。
//
// 与 camerarecognition 的差异：视频为离线逐帧分析（非实时预览），
// 不使用 DetectionBasedTracker（时间平滑由 Kotlin 侧
// FaceTrackMath 按 IoU 关联 + 滑动平均完成），且不做内部降采样
// （工作分辨率 640 宽已由分析器从 1920 降采样，再降会使最小人脸
// 40px 低于级联 24px 训练窗口而漏检远小脸）。
//

#include <jni.h>
#include <android/log.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>

#include <algorithm>
#include <string>
#include <vector>

#define TAG "VR_FaceDetector"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/** NMS 聚类 IoU 阈值：不同模型对同一张脸的框 IoU 通常 > 0.4。 */
constexpr float MERGE_IOU = 0.30f;

/** 候选框参加面部特征验证的最小边长（更小的脸特征太弱，跳过验证）。 */
constexpr int VERIFY_MIN_SIZE = 70;

/** 带来源标记与置信度的检测框（fromProfile：来自侧脸模型）。 */
struct RawBox {
    cv::Rect r;
    bool fromProfile;
    double conf;
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
 * 多级联融合人脸检测器：第九章的人脸级联模型（正脸 default/alt2 +
 * 侧脸 profileface）并集检测，面部特征模型（眼/鼻/嘴）验证降误检。
 *
 * detect() 流程：CLAHE 灰度增强 → 各模型扫描（侧脸附加镜像补扫，
 * levelWeights 转置信度）→ IoU 聚类取平均框（置信度取簇内最大）→
 * 面部特征验证 → 输出。
 */
class FaceDetector {
public:
    /**
     * @param facePaths    人脸级联模型路径（文件名含 "profileface"
     *                     视为侧脸模型并自动镜像补扫另一侧）
     * @param featurePaths 面部特征模型路径（按文件名识别 eye/nose/mouth）
     * @return 是否加载到至少一个可用的人脸模型
     */
    bool load(const std::vector<std::string> &facePaths,
              const std::vector<std::string> &featurePaths) {
        for (const std::string &p : facePaths) {
            faces_.emplace_back();
            faces_.back().isProfile = p.find("profileface") != std::string::npos;
            // 防御式加载：模型损坏/格式不合法时捕获异常跳过该模型
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
            LOGD("loaded face cascade (profile=%d): %s",
                 faces_.back().isProfile, p.c_str());
        }

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
            if (p.find("eye") != std::string::npos) {
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

        // CLAHE 局部自适应直方图均衡（clipLimit=2.0 保守增强）
        clahe_ = cv::createCLAHE(2.0, cv::Size(8, 8));
        return !faces_.empty();
    }

    bool ready() const { return !faces_.empty(); }

    /**
     * 多级联融合检测人脸。
     * @param gray     灰度图（行紧凑、无 padding 的 yPlane）
     * @param w/h      宽高
     * @param minFace  最小人脸边长（像素）
     * @param confidences 输出：各检测框置信度 0~1
     * @return 检测框列表
     */
    std::vector<cv::Rect> detect(const cv::Mat &gray, int minFace,
                                 std::vector<double> &confidences) {
        std::vector<cv::Rect> faces;
        confidences.clear();
        // 整体兜底：检测过程中任何异常只丢弃本帧结果
        try {
            detectInternal(gray, minFace, faces, confidences);
        } catch (const cv::Exception &e) {
            LOGE("detect exception: %s", e.what());
            faces.clear();
            confidences.clear();
        }
        return faces;
    }

    ~FaceDetector() {
        delete eye_;
        delete nose_;
        delete mouth_;
    }

private:
    struct FaceCascade {
        cv::CascadeClassifier classifier;
        bool isProfile;
    };

    void detectInternal(const cv::Mat &gray, int minFace,
                        std::vector<cv::Rect> &faces,
                        std::vector<double> &confidences) {
        // CLAHE 局部自适应均衡（对逆光/侧光/阴影比全局均衡更鲁棒）
        cv::Mat eq;
        clahe_->apply(gray, eq);

        // ---- 1. 各级联模型扫描（侧脸模型附加水平镜像补扫） ----
        // levelWeights 输出版本可拿到每个检测框的级联末级权重
        std::vector<RawBox> raw;
        for (FaceCascade &fc : faces_) {
            scanOnce(fc.classifier, eq, false, fc.isProfile, minFace, raw);
            if (fc.isProfile) {
                // 侧脸级联只识别朝向一侧的脸，镜像后可覆盖另一侧
                cv::Mat flipped;
                cv::flip(eq, flipped, 1);
                scanOnce(fc.classifier, flipped, true, true, minFace, raw);
            }
        }

        // ---- 2. NMS 融合去重：IoU 聚类，簇内取平均框、最大置信度 ----
        std::sort(raw.begin(), raw.end(), [](const RawBox &a, const RawBox &b) {
            return a.r.area() > b.r.area();
        });
        std::vector<std::vector<RawBox>> clusters;
        std::vector<RawBox> reps; // 各簇当前代表（IoU 比较基准）
        for (const RawBox &b : raw) {
            int best = -1;
            float bestIoU = 0.f;
            for (size_t i = 0; i < clusters.size(); i++) {
                float v = boxIou(b.r, reps[i].r);
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
                reps.push_back(b);
            }
        }

        // ---- 3. 输出（正脸大框做面部特征验证） ----
        faces.reserve(clusters.size());
        confidences.reserve(clusters.size());
        for (size_t i = 0; i < clusters.size(); i++) {
            const cv::Rect box = reps[i].r;
            bool fromProfile = false;
            double conf = 0.0;
            for (const RawBox &m : clusters[i]) {
                fromProfile |= m.fromProfile;
                conf = std::max(conf, m.conf);
            }

            if (!fromProfile &&
                box.width >= VERIFY_MIN_SIZE && box.height >= VERIFY_MIN_SIZE &&
                !verifyFace(eq, box)) {
                continue; // 验证不通过：框内无任何面部特征，判为误检丢弃
            }
            faces.push_back(box);
            confidences.push_back(conf);
        }
    }

    /**
     * 单个级联扫描一次，结果追加进 raw。
     * @param flipped    输入图是否为镜像图（需回投 x 坐标）
     * @param fromProfile 本轮扫描是否来自侧脸模型（验证阶段跳过）
     */
    void scanOnce(cv::CascadeClassifier &cascade, const cv::Mat &img,
                  bool flipped, bool fromProfile,
                  int minFace, std::vector<RawBox> &raw) {
        std::vector<cv::Rect> found;
        std::vector<int> rejectLevels;
        std::vector<double> levelWeights;
        cascade.detectMultiScale(
                img, found, rejectLevels, levelWeights,
                1.08, // scaleFactor（更细的多尺度扫描，提升识别率）
                3,    // minNeighbors（放宽去重，误检由验证+平滑抑制）
                0,    // flags
                cv::Size(minFace, minFace),
                cv::Size(),
                true); // outputRejectLevels = true，规避 clipObjects 断言错误

        const int w = img.cols;
        // 置信度按模型量级校准（midway.mp4 实测 levelWeights 分布）：
        // - 正脸级联（default/alt2）双峰分布：强命中 ~55、弱命中 1~7，
        //   除数 15 → 强命中映射为 1.0、弱命中 < 0.5；
        // - 侧脸级联（profileface）为旧格式级联，权重上限仅 ~2.6，
        //   统一除数会使其永远低于任何门槛，故按其量级取除数 2.8，
        //   仅最强的少数侧脸命中可超过 80% 门槛。
        // Kotlin 侧以 conf > 0.80 作为「相似度 > 80%」的显示判据。
        const double div = fromProfile ? 2.8 : 15.0;
        for (size_t i = 0; i < found.size(); i++) {
            cv::Rect r = found[i];
            if (flipped) {
                r.x = w - r.x - r.width; // 镜像坐标还原
            }
            double lw = levelWeights.size() > i ? levelWeights[i] : 5.0;
            double conf = lw / div;
            if (conf > 1.0) conf = 1.0;
            if (conf < 0.05) conf = 0.05;
            raw.push_back({r, fromProfile, conf});
        }
    }

    /** 簇内平均框（四舍五入）。 */
    static RawBox averageBox(const std::vector<RawBox> &c) {
        double x = 0, y = 0, w = 0, h = 0, conf = 0;
        for (const RawBox &b : c) {
            x += b.r.x; y += b.r.y; w += b.r.width; h += b.r.height;
            conf = std::max(conf, b.conf);
        }
        const double n = static_cast<double>(c.size());
        RawBox out;
        out.r = cv::Rect(cvRound(x / n), cvRound(y / n),
                         cvRound(w / n), cvRound(h / n));
        out.fromProfile = false;
        out.conf = conf;
        return out;
    }

    /**
     * 面部特征验证：候选框内是否检出支持「这是人脸」的特征。
     * 宽松规则（保识别率优先）：
     *   检出眼睛 或 （鼻子 且 嘴巴）→ 通过；
     * 未加载任何特征模型时直接通过。
     */
    bool verifyFace(const cv::Mat &gray, const cv::Rect &r) {
        if (eye_ == nullptr && nose_ == nullptr && mouth_ == nullptr) {
            return true;
        }
        const cv::Rect bounds(0, 0, gray.cols, gray.rows);
        const cv::Rect roi = r & bounds;
        if (roi.empty()) return true;

        std::vector<cv::Rect> hits;
        const cv::Size fmin(std::max(12, roi.width / 6),
                            std::max(12, roi.width / 6));

        // 眼睛：候选框上部 60%
        cv::Rect eyeRoi(roi.x + cvRound(roi.width * 0.1), roi.y,
                        cvRound(roi.width * 0.8), cvRound(roi.height * 0.6));
        eyeRoi &= bounds;
        if (eyeRoi.width > fmin.width && eyeRoi.height > fmin.height) {
            if (eye_ != nullptr) {
                eye_->detectMultiScale(gray(eyeRoi), hits, 1.1, 2, 0, fmin);
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

    // 面部特征模型（候选框验证）
    cv::CascadeClassifier *eye_ = nullptr;
    cv::CascadeClassifier *nose_ = nullptr;
    cv::CascadeClassifier *mouth_ = nullptr;

    cv::Ptr<cv::CLAHE> clahe_;
};

inline FaceDetector *asDetector(jlong handle) {
    return reinterpret_cast<FaceDetector *>(handle);
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
 * 创建多级联检测器并加载级联模型。
 * @param faceCascadePaths    人脸模型路径（正脸 default/alt2 + 侧脸）
 * @param featureCascadePaths 面部特征模型路径（眼/鼻/嘴）
 * @return native 句柄；加载失败返回 0
 */
JNIEXPORT jlong JNICALL
Java_com_wangyao_videorecognition_jni_FaceJni_nativeCreate(
        JNIEnv *env, jobject, jobjectArray faceCascadePaths,
        jobjectArray featureCascadePaths) {
    auto *detector = new FaceDetector();
    bool ok = false;
    // JNI 边界兜底：初始化全程任何异常都视为创建失败返回 0
    try {
        ok = detector->load(toStringVector(env, faceCascadePaths),
                            toStringVector(env, featureCascadePaths));
    } catch (const cv::Exception &e) {
        LOGE("detector init exception: %s", e.what());
        ok = false;
    } catch (const std::exception &e) {
        LOGE("detector init std exception: %s", e.what());
        ok = false;
    }
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
 * 对灰度帧执行多级联融合人脸检测。
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
