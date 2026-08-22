#ifndef OPENCVDEAL_IMAGESEGMENTATION_H
#define OPENCVDEAL_IMAGESEGMENTATION_H

#include <opencv2/core.hpp>

/**
 * 图像分割（《数字图像与视频处理》第 4 章）
 *
 * 四大类分割方法：
 * 1. 基于灰度阈值化的图像分割（4.2 节，式 4-1 ~ 4-10）
 * 2. 基于边缘检测的图像分割（4.3 节，式 4-11 ~ 4-39 及轮廓跟踪）
 * 3. 基于区域的图像分割（4.4 节：区域生长 / 区域分裂与合并）
 * 4. 基于主动轮廓模型的图像分割（4.5 节，式 4-40 ~ 4-47）
 */
namespace ImageSegmentation {

    // =========================================================================
    // 4.2 基于灰度阈值化的图像分割
    // =========================================================================

    /** 固定阈值二值化分割（式 4-1）。 */
    cv::Mat segFixedThreshold(const cv::Mat &src, double thresh);

    /** 迭代式全局阈值分割（式 4-3：T = (μ1+μ2)/2 反复迭代至收敛）。 */
    cv::Mat segIterativeThreshold(const cv::Mat &src);

    /** Otsu 最大类间方差阈值分割（式 4-4 ~ 4-10）。 */
    cv::Mat segOtsu(const cv::Mat &src);

    /** 局部自适应阈值分割（式 4-2 的局部化：T 依赖邻域均值）。 */
    cv::Mat segAdaptiveThreshold(const cv::Mat &src, int blockSize, double C);

    /** 多级阈值分割（式 4-1 的多类推广：双重 Otsu 分为三类）。 */
    cv::Mat segMultiLevelThreshold(const cv::Mat &src);

    /** Otsu 分割结果与原图叠加可视化（分割区域红色高亮）。 */
    cv::Mat segThresholdOverlay(const cv::Mat &src);

    // =========================================================================
    // 4.3 基于边缘检测的图像分割
    // =========================================================================

    /** Roberts 交叉差分边缘检测（式 4-18, 4-19）。 */
    cv::Mat segRoberts(const cv::Mat &src);

    /** Sobel 算子边缘检测（式 4-20）。 */
    cv::Mat segSobel(const cv::Mat &src, int ksize);

    /** Prewitt 算子边缘检测（式 4-21, 4-22）。 */
    cv::Mat segPrewitt(const cv::Mat &src);

    /** Laplacian 二阶导数边缘检测（式 4-26 ~ 4-28）。 */
    cv::Mat segLaplacianEdge(const cv::Mat &src);

    /** LoG（Marr-Hildreth）边缘检测，过零点判定（式 4-29 ~ 4-33）。 */
    cv::Mat segLoG(const cv::Mat &src, int ksize, double sigma);

    /** Canny 多级边缘检测（式 4-34 ~ 4-39：高斯平滑/梯度/NMS/双阈值）。 */
    cv::Mat segCanny(const cv::Mat &src, double t1, double t2);

    /** 轮廓跟踪：Canny 边缘连接为闭合轮廓（图 4-9, 4-10）。 */
    cv::Mat segContourTrace(const cv::Mat &src);

    // =========================================================================
    // 4.4 基于区域的图像分割
    // =========================================================================

    /** 区域生长法：图像中心为种子，灰度差准则（4.4.1 节）。 */
    cv::Mat segRegionGrowCenter(const cv::Mat &src, int th);

    /** 区域生长法：直方图峰值聚类重心自动选种（4.4.1 节种子原则 1)）。 */
    cv::Mat segRegionGrowAutoSeed(const cv::Mat &src, int th);

    /** 区域分裂与合并：四叉树 P(Ri) 谓词分裂 + 相邻合并（4.4.2 节，图 4-11）。 */
    cv::Mat segSplitMerge(const cv::Mat &src);

    /** 连通区域标记伪彩色可视化（区域分割结果表达）。 */
    cv::Mat segConnectedComponents(const cv::Mat &src);

    /** 标记控制的分水岭分割（区域分割的 OpenCV 扩展实现）。 */
    cv::Mat segWatershed(const cv::Mat &src);

    // =========================================================================
    // 4.5 基于主动轮廓模型的图像分割
    // =========================================================================

    /** 基本贪心 Snake 主动轮廓（式 4-40, 4-41 ~ 4-45）。 */
    cv::Mat segSnake(const cv::Mat &src, int iterations);

    /** 内部能量 α/β 强弱对比：弱正则(红) vs 强正则(绿)（式 4-41）。 */
    cv::Mat segSnakeSmoothCompare(const cv::Mat &src);

    /** 气球力 Snake：外力推动轮廓膨胀逼近目标（式 4-40 + 气球力扩展）。 */
    cv::Mat segBalloonSnake(const cv::Mat &src, int iterations);

    /** 测地线主动轮廓的离散曲线演化近似（式 4-46）。 */
    cv::Mat segGeodesicContour(const cv::Mat &src, int iterations);

    /** 区域型主动轮廓（Chan-Vese 简化迭代，式 4-47）。 */
    cv::Mat segChanVese(const cv::Mat &src, int iterations);
}

#endif // OPENCVDEAL_IMAGESEGMENTATION_H
