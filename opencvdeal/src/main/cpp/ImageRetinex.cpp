#include "include/ImageRetinex.h"
#include <opencv2/imgproc.hpp>
#include <cmath>
#include <algorithm>

namespace ImageRetinex {

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    /** 输入转 RGB 三通道。 */
    static cv::Mat toRgb(const cv::Mat &src) {
        cv::Mat rgb;
        if (src.channels() == 4) cv::cvtColor(src, rgb, cv::COLOR_RGBA2RGB);
        else if (src.channels() == 3) rgb = src.clone();
        else cv::cvtColor(src, rgb, cv::COLOR_GRAY2RGB);
        return rgb;
    }

    /** 输入转灰度。 */
    static cv::Mat toGray(const cv::Mat &src) {
        cv::Mat gray;
        if (src.channels() == 4) cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
        else if (src.channels() == 3) cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        else gray = src.clone();
        return gray;
    }

    /**
     * 高斯环绕卷积 I*G (式 2-92)：大 σ 时降采样加速
     * （降采样 → 小核高斯模糊 → 升采样，等价于大 σ 高斯低通）。
     */
    static cv::Mat gaussianSurround(const cv::Mat &f32, double sigma) {
        cv::Mat L;
        if (sigma <= 30.0) {
            cv::GaussianBlur(f32, L, cv::Size(0, 0), sigma);
        } else {
            int factor = std::max(2, (int) std::floor(sigma / 30.0));
            cv::Mat small, smallBlur;
            cv::resize(f32, small,
                       cv::Size(std::max(1, f32.cols / factor),
                                std::max(1, f32.rows / factor)),
                       0, 0, cv::INTER_AREA);
            cv::GaussianBlur(small, smallBlur, cv::Size(0, 0), sigma / factor);
            cv::resize(smallBlur, L, f32.size(), 0, 0, cv::INTER_LINEAR);
        }
        return L;
    }

    /** 三通道浮点结果统一全局归一化到 8U 彩色（保持通道间色彩关系）。 */
    static cv::Mat normalize3To8U(std::vector<cv::Mat> &chans) {
        double gmin = 1e30, gmax = -1e30;
        for (auto &ch: chans) {
            double mn, mx;
            cv::minMaxLoc(ch, &mn, &mx);
            gmin = std::min(gmin, mn);
            gmax = std::max(gmax, mx);
        }
        double range = gmax - gmin;
        if (range < 1e-6) range = 1.0;
        std::vector<cv::Mat> out(3);
        for (int i = 0; i < 3; ++i) {
            cv::Mat tmp = (chans[i] - gmin) * (255.0 / range);
            tmp.convertTo(out[i], CV_8U);
        }
        cv::Mat dst;
        cv::merge(out, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 光照分量估计 (式 2-88, 式 2-92)
    // -------------------------------------------------------------------------
    cv::Mat illuminationEstimate(const cv::Mat &src, double sigma) {
        if (src.empty()) return src;
        cv::Mat rgb = toRgb(src);

        // 逐通道高斯环绕卷积，输出平滑光照场（8U 直接显示）
        std::vector<cv::Mat> chans, outs;
        cv::split(rgb, chans);
        for (int i = 0; i < 3; ++i) {
            cv::Mat f32, L;
            chans[i].convertTo(f32, CV_32F);
            L = gaussianSurround(f32, sigma);
            cv::Mat u8;
            L.convertTo(u8, CV_8U);            // 光照本身在 0~255 内
            outs.push_back(u8);
        }
        cv::Mat dst;
        cv::merge(outs, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 反射分量可视化（灰度）(式 2-89 ~ 式 2-91)
    // -------------------------------------------------------------------------
    cv::Mat reflectanceGray(const cv::Mat &src, double sigma) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat f32, L, lnf, lnL, r, dst;
        gray.convertTo(f32, CV_32F);
        f32 += 1.0f;
        L = gaussianSurround(f32, sigma);       // 光照估计（+1 已含避免 ln 0）
        cv::log(f32, lnf);
        cv::log(L + 1.0f, lnL);
        r = lnf - lnL;                          // r = ln I − ln L
        cv::normalize(r, dst, 0, 255, cv::NORM_MINMAX);
        dst.convertTo(dst, CV_8U);
        return dst;
    }

    // -------------------------------------------------------------------------
    // SSR (式 2-91, 式 2-92)：ri = ln Ii − ln(Ii * G)
    // -------------------------------------------------------------------------
    cv::Mat ssr(const cv::Mat &src, double sigma) {
        if (src.empty()) return src;
        cv::Mat rgb = toRgb(src);

        std::vector<cv::Mat> chans;
        cv::split(rgb, chans);
        std::vector<cv::Mat> results(3);
        for (int i = 0; i < 3; ++i) {
            cv::Mat f32, L, lnf, lnL;
            chans[i].convertTo(f32, CV_32F);
            f32 += 1.0f;                        // 避免 ln 0
            L = gaussianSurround(f32, sigma);   // 式 2-92
            cv::log(f32, lnf);
            cv::log(L + 1.0f, lnL);
            results[i] = lnf - lnL;             // 式 2-91
        }
        return normalize3To8U(results);
    }

    // -------------------------------------------------------------------------
    // MSR (式 2-93, 式 2-94)：R = Σk ωk(ln I − ln(I*Gk))，ωk = 1/N
    // -------------------------------------------------------------------------
    cv::Mat msr(const cv::Mat &src, const std::vector<double> &sigmas) {
        if (src.empty() || sigmas.empty()) return src;
        cv::Mat rgb = toRgb(src);
        const double w = 1.0 / (double) sigmas.size();   // 式 2-94：等权

        std::vector<cv::Mat> chans;
        cv::split(rgb, chans);
        std::vector<cv::Mat> results(3);
        for (int i = 0; i < 3; ++i) {
            cv::Mat f32, lnf;
            chans[i].convertTo(f32, CV_32F);
            f32 += 1.0f;
            cv::log(f32, lnf);

            cv::Mat rsum = cv::Mat::zeros(f32.size(), CV_32F);
            for (double sigma: sigmas) {
                cv::Mat L, lnL;
                L = gaussianSurround(f32, sigma);
                cv::log(L + 1.0f, lnL);
                rsum += (lnf - lnL);            // 式 2-93 各尺度累加
            }
            results[i] = rsum * w;              // ωk 加权
        }
        return normalize3To8U(results);
    }

    // -------------------------------------------------------------------------
    // MSRCR (式 2-95, 式 2-96)：R = Σk Ci·ωk·(ln Ii − ln(Ii*Gk))
    // -------------------------------------------------------------------------
    cv::Mat msrcr(const cv::Mat &src, const std::vector<double> &sigmas) {
        if (src.empty() || sigmas.empty()) return src;
        cv::Mat rgb = toRgb(src);
        const double w = 1.0 / (double) sigmas.size();   // 式 2-94：等权

        std::vector<cv::Mat> chans;
        cv::split(rgb, chans);

        // 先算 MSR 各通道结果与总和（用于颜色恢复系数）
        std::vector<cv::Mat> msrChans(3);
        std::vector<cv::Mat> f32Chans(3);
        for (int i = 0; i < 3; ++i) {
            cv::Mat f32, lnf;
            chans[i].convertTo(f32, CV_32F);
            f32 += 1.0f;
            f32Chans[i] = f32;
            cv::log(f32, lnf);

            cv::Mat rsum = cv::Mat::zeros(f32.size(), CV_32F);
            for (double sigma: sigmas) {
                cv::Mat L, lnL;
                L = gaussianSurround(f32, sigma);
                cv::log(L + 1.0f, lnL);
                rsum += (lnf - lnL);
            }
            msrChans[i] = rsum * w;
        }

        // 式 2-95：Ci = f(Ii/ΣIi)，本实现 f 取线性函数
        std::vector<cv::Mat> results(3);
        cv::Mat sum3 = f32Chans[0] + f32Chans[1] + f32Chans[2];
        for (int i = 0; i < 3; ++i) {
            cv::Mat Ci;
            cv::divide(f32Chans[i], sum3, Ci);           // Ci = Ii / Σ Ii ∈ [0,1]
            results[i] = Ci.mul(msrChans[i]);            // 式 2-96：Ci × MSR_i
        }
        return normalize3To8U(results);
    }
}
