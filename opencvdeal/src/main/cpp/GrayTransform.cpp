#include "include/GrayTransform.h"
#include <opencv2/imgproc.hpp>
#include <cmath>

namespace GrayTransform {

    // -------------------------------------------------------------------------
    // 灰度的线性变换 (式 2-1)
    // g(x,y) = [(d-c)/(b-a)] * [f(x,y) - a] + c
    // 其中 f(x,y) ∈ [a,b]，变换后 g(x,y) ∈ [c,d]
    // -------------------------------------------------------------------------
    cv::Mat linearTransform(const cv::Mat &src, double a, double b, double c, double d) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }

        double denom = (b - a);
        if (std::abs(denom) < 1e-6) denom = 1e-6;
        double alpha = (d - c) / denom;
        double beta = c - alpha * a;

        cv::Mat dst;
        gray.convertTo(dst, CV_8U, alpha, beta);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 图像的反转变换 (图 2-3，线性变换的特例)
    // g(x,y) = 255 - f(x,y)
    // -------------------------------------------------------------------------
    cv::Mat invertTransform(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }
        cv::Mat dst;
        cv::bitwise_not(gray, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 三段分段线性变换 / 对比度扩展 (式 2-2, 式 2-3)
    // 式 2-2（压缩式）：将 [0,a) 和 [b,Mf) 压缩为常量 c、d。
    //   g = c,                          0 ≤ f < a
    //   g = [(d-c)/(b-a)](f-a) + c,     a ≤ f < b
    //   g = d,                          b ≤ f < Mf
    //
    // 式 2-3（斜率式，本函数实现）：
    //   g = (c/a)*f,                            0 ≤ f < a
    //   g = [(d-c)/(b-a)](f-a) + c,             a ≤ f < b
    //   g = [(Mg-d)/(Mf-b)](f-b) + d,           b ≤ f < Mf
    // 压缩 [0,a] 和 [b,Mf]，扩展 [a,b] 区间。
    // -------------------------------------------------------------------------
    cv::Mat piecewiseLinearTransform(const cv::Mat &src, double a, double b,
                                     double c, double d) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }

        // 构建 256 项 LUT
        cv::Mat lut(1, 256, CV_8U);
        uchar *lutPtr = lut.ptr<uchar>();
        const double Mf = 255.0;
        const double Mg = 255.0;

        // 斜率
        double k1 = (a > 0) ? c / a : 0;                     // [0, a] -> [0, c]
        double k2 = (b - a > 0) ? (d - c) / (b - a) : 0;    // [a, b] -> [c, d]
        double k3 = (Mf - b > 0) ? (Mg - d) / (Mf - b) : 0; // [b, Mf] -> [d, Mg]

        for (int i = 0; i < 256; ++i) {
            double f = static_cast<double>(i);
            double g;
            if (f < a) {
                g = k1 * f;
            } else if (f < b) {
                g = k2 * (f - a) + c;
            } else {
                g = k3 * (f - b) + d;
            }
            if (g < 0) g = 0;
            if (g > 255) g = 255;
            lutPtr[i] = static_cast<uchar>(g + 0.5);
        }

        cv::Mat dst;
        cv::LUT(gray, lut, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 削波处理 (图 2-6，式 2-3 特例)
    // 令式 2-3 中 c=0, d=Mg=255：
    //   g = 0,                        0 ≤ f < a
    //   g = [255/(b-a)]*(f-a),        a ≤ f < b
    //   g = 255,                      b ≤ f < Mf
    // 通过 piecewiseLinearTransform(src, a, b, 0, 255) 实现。
    // -------------------------------------------------------------------------
    cv::Mat clipTransform(const cv::Mat &src, double a, double b) {
        return piecewiseLinearTransform(src, a, b, 0.0, 255.0);
    }

    // -------------------------------------------------------------------------
    // 阈值化 (图 2-7，式 2-3 特例)
    // 令式 2-3 中 a=b=threshold, c=0, d=Mg=255：
    //   g = 0,    f <  threshold
    //   g = 255,  f ≥ threshold
    // 产生二值图像。采用 cv::threshold(THRESH_BINARY) 实现。
    // -------------------------------------------------------------------------
    cv::Mat thresholdTransform(const cv::Mat &src, double threshold) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }
        cv::Mat dst;
        cv::threshold(gray, dst, threshold, 255, cv::THRESH_BINARY);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 对数变换 (式 2-4)
    // PDF 原公式：g(x,y) = a + ln[f(x,y) + 1] / (b · ln c)
    // 本函数实现工程上常用的简化形式（令 a=0、b=1，自动缩放）：
    //   g(x,y) = c * ln(1 + f(x,y))
    // 扩展低灰度范围，压缩高灰度范围，使灰度分布更匹配视觉。
    // -------------------------------------------------------------------------
    cv::Mat logTransform(const cv::Mat &src, double c) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }

        // 转为 float，计算 log(1+f)，再缩放回 8U
        cv::Mat f32;
        gray.convertTo(f32, CV_32F);
        cv::Mat logImg;
        cv::log(f32 + 1.0, logImg);
        // c 的默认值使得 max 输出映射到 255
        if (c <= 0) {
            double minV, maxV;
            cv::minMaxLoc(logImg, &minV, &maxV);
            c = (maxV > 0) ? 255.0 / maxV : 1.0;
        }
        logImg = logImg * c;
        cv::Mat dst;
        logImg.convertTo(dst, CV_8U);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 伽马变换 / 幂次变换 (式 2-5 派生)
    // PDF 式 2-5（指数变换）：g(x,y) = b^{c·[f(x,y)-a]} - 1
    // 本函数实现工程上更常用的伽马（幂次）变换：
    //   g(x,y) = c * f(x,y)^γ    （先将 f 归一化到 [0,1]，再映射回 [0,255]）
    // γ > 1 压缩高灰度、扩展低灰度；γ < 1 反之。
    // -------------------------------------------------------------------------
    cv::Mat gammaTransform(const cv::Mat &src, double c, double gamma) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }

        // 归一化到 [0,1]，做幂运算，再映射回 [0,255]
        cv::Mat norm;
        gray.convertTo(norm, CV_64F, 1.0 / 255.0);
        cv::Mat dst;
        cv::pow(norm, gamma, dst);
        dst = dst * c * 255.0;
        cv::Mat result;
        dst.convertTo(result, CV_8U);
        return result;
    }

    // -------------------------------------------------------------------------
    // 直方图均衡化 (式 2-13, 式 2-14)
    // 式 2-13：离散灰度概率  pr(rk) = nk / n    (k = 0,1,...,L-1)
    // 式 2-14：累积分布变换  sk = T(rk) = Σ_{j=0}^{k} pr(rj)
    //          = Σ_{j=0}^{k} nj / n     (0 ≤ rj ≤ 1)
    // 将原始灰度 rk 映射到均衡化灰度 sk，使输出直方图近似均匀，扩大对比度。
    // 采用 cv::equalizeHist 实现。
    // -------------------------------------------------------------------------
    cv::Mat histogramEqualize(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }
        cv::Mat dst;
        cv::equalizeHist(gray, dst);
        return dst;
    }
}
