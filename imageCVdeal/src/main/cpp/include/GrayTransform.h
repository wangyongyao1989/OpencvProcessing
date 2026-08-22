#ifndef OPENCVDEAL_GRAYTRANSFORM_H
#define OPENCVDEAL_GRAYTRANSFORM_H

#include <opencv2/core.hpp>

/**
 * 图像的灰度变换
 *
 * 基于《数字图像与视频处理》第二章 2.2 节"图像的灰度变换"实现：
 * - 灰度的线性变换 (2.2.1)
 * - 灰度的非线性变换 (2.2.2)
 * - 直方图修正 (2.2.3)
 */
namespace GrayTransform {

    /**
     * 灰度的线性变换 (式 2-1)
     * g(x,y) = [(d-c)/(b-a)] * [f(x,y) - a] + c
     * 其中 f(x,y) 灰度范围为 [a,b]，变换后扩展至 [c,d]。
     *
     * @param src 输入灰度图 (CV_8UC1)
     * @param a   输入灰度下限
     * @param b   输入灰度上限
     * @param c   输出灰度下限
     * @param d   输出灰度上限
     */
    cv::Mat linearTransform(const cv::Mat &src, double a, double b, double c, double d);

    /**
     * 图像的反转变换 (图 2-3，线性变换的特例)
     * g(x,y) = 255 - f(x,y)，黑变白、白变黑。
     */
    cv::Mat invertTransform(const cv::Mat &src);

    /**
     * 三段分段线性变换 / 对比度扩展 (式 2-2, 式 2-3)
     * 式 2-2（压缩式）：
     *   g(x,y) = c,                              0 ≤ f < a
     *          = [(d-c)/(b-a)][f(x,y)-a] + c,    a ≤ f < b
     *          = d,                              b ≤ f < Mf
     *   即将 [0,a) 和 [b,Mf) 压缩为两个常量 c、d。
     *
     * 式 2-3（斜率式，本函数实现）：
     *   g(x,y) = (c/a) * f(x,y),                                 0 ≤ f < a
     *          = [(d-c)/(b-a)][f(x,y)-a] + c,                    a ≤ f < b
     *          = [(Mg-d)/(Mf-b)][f(x,y)-b] + d,                  b ≤ f < Mf
     *   对 [0,a] 和 [b,Mf] 两段压缩，对 [a,b] 区间进行扩展。
     *
     * @param src 输入灰度图
     * @param a   第一拐点（输入灰度值）
     * @param b   第二拐点（输入灰度值）
     * @param c   a 对应的输出值
     * @param d   b 对应的输出值
     */
    cv::Mat piecewiseLinearTransform(const cv::Mat &src, double a, double b,
                                     double c, double d);

    /**
     * 削波处理 (图 2-6，式 2-3 特例)
     * 令式 2-3 中 c=0, d=Mg=255：
     *   g(x,y) = 0,                                     0 ≤ f < a
     *          = [255/(b-a)][f(x,y)-a],                 a ≤ f < b
     *          = 255,                                   b ≤ f < Mf
     * 抑制 [0,a] 和 [b,Mf] 两个灰度区间，扩展 [a,b] 区间像素的动态范围。
     *
     * @param src 输入灰度图
     * @param a   削波下限
     * @param b   削波上限
     */
    cv::Mat clipTransform(const cv::Mat &src, double a, double b);

    /**
     * 阈值化 (图 2-7，式 2-3 特例)
     * 令式 2-3 中 a=b=threshold, c=0, d=Mg=255：
     *   g(x,y) = 0,    f(x,y) <  threshold
     *          = 255,  f(x,y) ≥ threshold
     * 得到只有两个灰度级的二值图像。
     *
     * @param src       输入灰度图
     * @param threshold 阈值
     */
    cv::Mat thresholdTransform(const cv::Mat &src, double threshold);

    /**
     * 对数变换 (式 2-4)
     * PDF 原公式：g(x,y) = a + ln[f(x,y) + 1] / (b · ln c)
     *   参数 a、b、c 用于调整曲线的起始位置和形状。
     * 本函数实现常用简化形式（令 a=0，b=1，使 max(g)=255 反求 c）：
     *   g(x,y) = c * ln(1 + f(x,y))
     * 作用：扩展低灰度范围，压缩高灰度范围，使灰度分布更匹配视觉特性。
     *
     * @param src 输入灰度图
     * @param c   缩放系数（≤0 时自动计算使最大输出映射到 255）
     */
    cv::Mat logTransform(const cv::Mat &src, double c);

    /**
     * 伽马变换 / 幂次变换 (式 2-5 派生)
     * PDF 式 2-5（指数变换）：g(x,y) = b^{c·[f(x,y)-a]} - 1
     *   扩展高灰度区间、压缩低灰度区间。
     * 工程上更常用伽马（幂次）变换（本函数实现）：
     *   g(x,y) = c * f(x,y)^γ    （f ∈ [0,1] 归一化后再映射回 [0,255]）
     *   γ > 1 压缩高灰度、扩展低灰度（使暗部更暗）；γ < 1 反之。
     *
     * @param src   输入灰度图
     * @param c     缩放系数
     * @param gamma 伽马值 γ
     */
    cv::Mat gammaTransform(const cv::Mat &src, double c, double gamma);

    /**
     * 直方图均衡化 (式 2-13, 式 2-14)
     * 式 2-13：离散灰度概率  pr(rk) = nk / n    (k=0,1,...,L-1)
     * 式 2-14：累积分布变换  sk = T(rk) = Σ_{j=0}^{k} pr(rj)
     *   0 ≤ rj ≤ 1，将原始灰度 rk 映射为均衡化后的灰度 sk。
     * 效果：使输出图像灰度分布近似均匀，显著增大对比度、改善层次感。
     *
     * @param src 输入灰度图
     */
    cv::Mat histogramEqualize(const cv::Mat &src);
}

#endif //OPENCVDEAL_GRAYTRANSFORM_H
