#ifndef OPENCVDEAL_IMAGESMOOTHDENOISE_H
#define OPENCVDEAL_IMAGESMOOTHDENOISE_H

#include <opencv2/core.hpp>

/**
 * 图像平滑与去噪
 *
 * 基于《数字图像与视频处理》第二章 2.3 节"图像平滑与去噪"实现：
 * - 模板操作与邻域平均法 (2.3.2)
 * - 中值滤波 (2.3.3)
 * - 基于非局部相似性的图像去噪 (2.3.4)
 * - 频率域低通滤波 (2.3.5)
 */
namespace ImageSmoothDenoise {

    /**
     * 4-邻域平均法 (式 2-23, 式 2-24, 模板 H1 式 2-25)
     * 式 2-23：g(x,y) = (1/M) Σ_{(i,j)∈N} f(i,j)
     * 式 2-24：g(x,y) = (1/4)[f(x-1,y) + f(x,y-1) + f(x,y+1) + f(x+1,y)]
     * 式 2-25：H1 = (1/4) [[0,1,0],[1,0,1],[0,1,0]]
     * 用不含中心点的十字模板求邻域均值，抑制随机噪声，但会使边缘变模糊。
     *
     * @param src 输入图像（内部先转为灰度）
     */
    cv::Mat neighborhoodAverage4(const cv::Mat &src);

    /**
     * 8-邻域平均法 (式 2-26, 模板 H2 式 2-27)
     * 式 2-26：g(x,y) = (1/8) Σ_{(i,j)∈N8} f(i,j)
     *          = (1/8)[f(x-1,y-1)+f(x,y-1)+f(x+1,y-1)+f(x-1,y)
     *                 +f(x+1,y)+f(x-1,y+1)+f(x,y+1)+f(x+1,y+1)]
     * 式 2-27：H2 = (1/8) [[1,1,1],[1,0,1],[1,1,1]]
     * 平滑作用比 4-邻域更强，图像也随之更模糊。
     *
     * @param src 输入图像（内部先转为灰度）
     */
    cv::Mat neighborhoodAverage8(const cv::Mat &src);

    /**
     * 阈值邻域平均法 (式 2-28)
     *   g(x,y) = (1/M)Σ f(i,j),  当 |f(x,y) - (1/M)Σf(i,j)| > T
     *          = f(x,y),          其他
     * 只有当像素灰度与其邻域均值之差超过阈值 T 时才视为噪声并用邻域均值代替，
     * 否则保留原灰度，从而在去噪的同时减轻邻域平均法带来的模糊。
     *
     * @param src 输入图像（内部先转为灰度）
     * @param T   阈值（灰度差超过 T 判为噪声）
     */
    cv::Mat thresholdAverage(const cv::Mat &src, double T);

    /**
     * 3×3 方形窗口中值滤波 (式 2-29, 式 2-30)
     * 式 2-29：yi = Med{f(i-u),…,f(i),…,f(i+u)}，u=(m-1)/2
     * 式 2-30：yij = Med_W{Fij}（W 为滤波窗口）
     * 窗口内像素按灰度排序取中间值代替中心像素。非线性滤波，
     * 对椒盐（脉冲）噪声最有效，且能较好地保护边缘。
     *
     * @param src 输入图像（内部先转为灰度）
     */
    cv::Mat medianFilter3x3(const cv::Mat &src);

    /**
     * 5×5 十字形窗口中值滤波 (式 2-29, 式 2-30；图 2-21, 图 2-23f)
     * 窗口取"中心 + 水平 ±2 + 垂直 ±2"共 9 个像素（十字形），
     * 对含尖顶角结构的图像较合适（PDF 图 2-23f 采用的即 5×5 十字中值滤波）。
     *
     * @param src 输入图像（内部先转为灰度）
     */
    cv::Mat medianFilterCross5x5(const cv::Mat &src);

    /**
     * 理想低通滤波 (式 2-40 ~ 式 2-42)
     * 式 2-40：G(u,v) = H(u,v)·F(u,v)
     * 式 2-41：H(u,v) = 1,  D(u,v) ≤ D0；  0,  D(u,v) > D0
     * 式 2-42：D(u,v) = √(u² + v²)（频率点到频率平面原点的距离）
     * 截止频率以内分量无损通过、以外完全滤除。
     * D0 越小去噪越彻底但越模糊，并会出现"振铃(Ring)"现象。
     *
     * @param src 输入图像（内部先转为灰度）
     * @param D0  截止频率（频率平面像素距离）
     */
    cv::Mat idealLowPass(const cv::Mat &src, double D0);

    /**
     * 高斯低通滤波 (式 2-45, 式 2-46)
     * 式 2-45：H(u,v) = e^{-D²(u,v)/(2σ²)}
     * 式 2-46：σ = D0 时 H(u,v) = e^{-D²(u,v)/(2D0²)}
     * 高、低频之间平滑过渡，无振铃现象，滤波后图像模糊程度较轻。
     *
     * @param src 输入图像（内部先转为灰度）
     * @param D0  截止频率（此处取 σ = D0）
     */
    cv::Mat gaussianLowPass(const cv::Mat &src, double D0);

    /**
     * 非局部均值 (NLM) 去噪 (式 2-31, 式 2-32)
     * 式 2-31：w(i,j) = exp(-d(i,j)/h²)，d(i,j) = ‖N(i)-N(j)‖²_{2,α}
     *          （图像块间高斯加权欧氏距离度量相似度，α 为高斯核标准差，h 控制衰减）
     * 式 2-32：f'(i) = Σ_{j∈Φ} w(i,j)·f(j) / Σ_{j∈Φ} w(i,j)
     * 利用图像中位置不同但结构相似的图像块加权平均，兼顾去噪与细节保持。
     * BM3D、WNNM(式 2-33~2-39) 均建立在该非局部相似性思想上。
     * 本函数采用 OpenCV fastNlMeansDenoising 实现。
     *
     * @param src 输入图像（内部先转为灰度）
     * @param h   平滑强度（衰减控制系数，越大去噪越强）
     */
    cv::Mat nlmDenoise(const cv::Mat &src, double h);
}

#endif //OPENCVDEAL_IMAGESMOOTHDENOISE_H
