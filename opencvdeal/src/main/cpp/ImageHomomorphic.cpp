#include "include/ImageHomomorphic.h"
#include <opencv2/imgproc.hpp>
#include <cmath>

namespace ImageHomomorphic {

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    /** 输入转灰度（RGBA/RGB 均可）。 */
    static cv::Mat toGray(const cv::Mat &src) {
        cv::Mat gray;
        if (src.channels() == 4) cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);
        else if (src.channels() == 3) cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        else gray = src.clone();
        return gray;
    }

    /** 输入转 RGB 三通道。 */
    static cv::Mat toRgb(const cv::Mat &src) {
        cv::Mat rgb;
        if (src.channels() == 4) cv::cvtColor(src, rgb, cv::COLOR_RGBA2RGB);
        else if (src.channels() == 3) rgb = src.clone();
        else cv::cvtColor(src, rgb, cv::COLOR_GRAY2RGB);
        return rgb;
    }

    /**
     * 大 σ 高斯低通（降采样优化：降采样 → 模糊 → 升采样）。
     * σ 较大时直接卷积核过长，等价降采样实现可大幅加速。
     */
    static cv::Mat gaussianLowPass(const cv::Mat &f32, double sigma) {
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

    // -------------------------------------------------------------------------
    // 同态滤波单通道核心 (式 2-82 ~ 式 2-87)
    // -------------------------------------------------------------------------
    static cv::Mat homomorphicChannel(const cv::Mat &f8u, double d0, double c,
                                      double hl, double hh) {
        // 式 2-82：z = ln f（+1 避免 ln 0）
        cv::Mat f32, z;
        f8u.convertTo(f32, CV_32F);
        f32 += 1.0f;
        cv::log(f32, z);

        // 补零到最优 DFT 尺寸（取偶数），并乘 (-1)^(x+y) 中心化
        int m = cv::getOptimalDFTSize(z.rows);
        if (m % 2) m++;
        int n = cv::getOptimalDFTSize(z.cols);
        if (n % 2) n++;
        cv::Mat padded;
        cv::copyMakeBorder(z, padded, 0, m - z.rows, 0, n - z.cols,
                           cv::BORDER_CONSTANT, cv::Scalar::all(0));
        for (int y = 0; y < padded.rows; ++y) {
            float *row = padded.ptr<float>(y);
            for (int x = 0; x < padded.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }

        // 式 2-83：Z(u,v) = F{z}；式 2-84：Z = I + R（照度/反射谱分离）
        cv::Mat planes[] = {padded, cv::Mat::zeros(padded.size(), CV_32F)};
        cv::Mat complexI;
        cv::merge(planes, 2, complexI);
        cv::dft(complexI, complexI);

        // 传递函数（图 2-42 剖面，例 2-7）：
        // H(u,v) = (HH − HL)·(1 − e^(−c·D²/D0²)) + HL，D 为到中心距离
        cv::Mat H(m, n, CV_32F);
        const int cu = m / 2, cvn = n / 2;
        for (int u = 0; u < m; ++u) {
            float *hRow = H.ptr<float>(u);
            for (int v = 0; v < n; ++v) {
                double D2 = (u - cu) * (u - cu) + (v - cvn) * (v - cvn);
                hRow[v] = static_cast<float>(
                        (hh - hl) * (1.0 - std::exp(-c * D2 / (d0 * d0))) + hl);
            }
        }

        // 式 2-85：S(u,v) = H(u,v)·Z(u,v)
        cv::split(complexI, planes);
        cv::multiply(planes[0], H, planes[0]);
        cv::multiply(planes[1], H, planes[1]);
        cv::merge(planes, 2, complexI);

        // 式 2-86：s(x,y) = F⁻¹[S]
        cv::idft(complexI, complexI, cv::DFT_SCALE);
        cv::split(complexI, planes);
        cv::Mat s = planes[0](cv::Rect(0, 0, z.cols, z.rows)).clone();

        // 再乘 (-1)^(x+y) 去中心化
        for (int y = 0; y < s.rows; ++y) {
            float *row = s.ptr<float>(y);
            for (int x = 0; x < s.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }

        // 式 2-87：g = e^s（取指数还原），返回浮点由调用方统一归一化
        cv::Mat g;
        cv::exp(s, g);
        return g;
    }

    /** 三通道浮点结果统一全局归一化到 8U 彩色图（保持通道间色彩关系）。 */
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
    // 同态滤波全流程（彩色，逐通道）
    // -------------------------------------------------------------------------
    cv::Mat homomorphicFilter(const cv::Mat &src, double d0, double c,
                              double hl, double hh) {
        if (src.empty()) return src;
        cv::Mat rgb = toRgb(src);

        // 逐通道执行 式 2-82 ~ 2-87
        std::vector<cv::Mat> chans;
        cv::split(rgb, chans);
        std::vector<cv::Mat> results(3);
        for (int i = 0; i < 3; ++i) {
            results[i] = homomorphicChannel(chans[i], d0, c, hl, hh);
        }
        return normalize3To8U(results);
    }

    // -------------------------------------------------------------------------
    // 对数域可视化 (式 2-82)
    // -------------------------------------------------------------------------
    cv::Mat logDomainImage(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat f32, z, dst;
        gray.convertTo(f32, CV_32F);
        f32 += 1.0f;
        cv::log(f32, z);                       // z = ln f
        cv::normalize(z, dst, 0, 255, cv::NORM_MINMAX);
        dst.convertTo(dst, CV_8U);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 照度分量估计（低频，σ=60 高斯）
    // -------------------------------------------------------------------------
    cv::Mat illuminationComponent(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat f32, L, dst;
        gray.convertTo(f32, CV_32F);
        L = gaussianLowPass(f32, 60.0);        // 低通估计照度场
        cv::normalize(L, dst, 0, 255, cv::NORM_MINMAX);
        dst.convertTo(dst, CV_8U);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 反射分量估计 r = f / (i + ε)（高频细节）
    // -------------------------------------------------------------------------
    cv::Mat reflectanceComponent(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat f32, L, r, dst;
        gray.convertTo(f32, CV_32F);
        L = gaussianLowPass(f32, 60.0);        // 照度估计
        cv::divide(f32 + 1.0f, L + 1.0f, r);   // r = f / (i + 1)
        cv::normalize(r, dst, 0, 255, cv::NORM_MINMAX);
        dst.convertTo(dst, CV_8U);
        return dst;
    }
}
