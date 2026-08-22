#include "include/ImageColorEnhance.h"
#include <opencv2/imgproc.hpp>
#include <cmath>
#include <algorithm>

namespace ImageColorEnhance {

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

    /** HSV(H∈[0,360), S,V∈[0,1]) → RGB 8U 三元素。 */
    static void hsvToRgb(float h, float s, float v, uchar rgb[3]) {
        float c = v * s;
        float hp = h / 60.0f;
        float x = c * (1.0f - std::fabs(std::fmod(hp, 2.0f) - 1.0f));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = v - c;
        rgb[0] = cv::saturate_cast<uchar>((r + m) * 255.0f);
        rgb[1] = cv::saturate_cast<uchar>((g + m) * 255.0f);
        rgb[2] = cv::saturate_cast<uchar>((b + m) * 255.0f);
    }

    // -------------------------------------------------------------------------
    // 灰度分层法——两层切割 (图 2-47)
    // -------------------------------------------------------------------------
    cv::Mat graySlice2(const cv::Mat &src, int l1) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat dst(gray.size(), CV_8UC3);
        for (int y = 0; y < gray.rows; ++y) {
            const uchar *gRow = gray.ptr<uchar>(y);
            uchar *dRow = dst.ptr<uchar>(y);
            for (int x = 0; x < gray.cols; ++x) {
                if (gRow[x] < (uchar) l1) {
                    dRow[3 * x] = 0;      // 蓝色
                    dRow[3 * x + 1] = 0;
                    dRow[3 * x + 2] = 255;
                } else {
                    dRow[3 * x] = 255;    // 红色
                    dRow[3 * x + 1] = 0;
                    dRow[3 * x + 2] = 0;
                }
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // 灰度分层法——多平面切割 (图 2-48)：M 个平面 → M+1 种颜色
    // -------------------------------------------------------------------------
    cv::Mat graySliceMulti(const cv::Mat &src, int m) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        const int regions = m + 1;                       // M+1 个区域
        std::vector<uchar> lut(regions * 3);             // 每区域一种光谱色
        for (int i = 0; i < regions; ++i) {
            uchar rgb[3];
            hsvToRgb(360.0f * i / regions, 1.0f, 1.0f, rgb);   // 色相均布
            lut[3 * i] = rgb[0];
            lut[3 * i + 1] = rgb[1];
            lut[3 * i + 2] = rgb[2];
        }

        cv::Mat dst(gray.size(), CV_8UC3);
        const float step = 256.0f / regions;
        for (int y = 0; y < gray.rows; ++y) {
            const uchar *gRow = gray.ptr<uchar>(y);
            uchar *dRow = dst.ptr<uchar>(y);
            for (int x = 0; x < gray.cols; ++x) {
                int idx = std::min(regions - 1,
                                   (int) std::floor(gRow[x] / step));
                dRow[3 * x] = lut[3 * idx];
                dRow[3 * x + 1] = lut[3 * idx + 1];
                dRow[3 * x + 2] = lut[3 * idx + 2];
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // 灰度级彩色变换 (图 2-49)：相位错开 1/3 的三角波变换器
    // -------------------------------------------------------------------------
    cv::Mat grayLevelColorTransform(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // tri(x) = 1 − |2·frac(x) − 1|：峰值在 frac=0.5 的三角波
        auto tri = [](float x) -> float {
            float f = x - std::floor(x);
            return 1.0f - std::fabs(2.0f * f - 1.0f);
        };

        cv::Mat dst(gray.size(), CV_8UC3);
        for (int y = 0; y < gray.rows; ++y) {
            const uchar *gRow = gray.ptr<uchar>(y);
            uchar *dRow = dst.ptr<uchar>(y);
            for (int x = 0; x < gray.cols; ++x) {
                float t = gRow[x] / 255.0f;
                dRow[3 * x] = cv::saturate_cast<uchar>(tri(t) * 255.0f);            // IR
                dRow[3 * x + 1] = cv::saturate_cast<uchar>(tri(t + 1.0f / 3.0f) * 255.0f);  // IG
                dRow[3 * x + 2] = cv::saturate_cast<uchar>(tri(t + 2.0f / 3.0f) * 255.0f);  // IB
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // 频率域滤波法伪彩色 (图 2-50)：低通/带通/高通 → R/G/B
    // -------------------------------------------------------------------------
    cv::Mat frequencyPseudoColor(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 1. 浮点化并乘 (-1)^(x+y) 中心化
        cv::Mat f;
        gray.convertTo(f, CV_32F);
        int m = cv::getOptimalDFTSize(f.rows);
        if (m % 2) m++;
        int n = cv::getOptimalDFTSize(f.cols);
        if (n % 2) n++;
        cv::Mat padded;
        cv::copyMakeBorder(f, padded, 0, m - f.rows, 0, n - f.cols,
                           cv::BORDER_CONSTANT, cv::Scalar::all(0));
        for (int y = 0; y < padded.rows; ++y) {
            float *row = padded.ptr<float>(y);
            for (int x = 0; x < padded.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }

        // 2. DFT
        cv::Mat planes[] = {padded, cv::Mat::zeros(padded.size(), CV_32F)};
        cv::Mat complexI;
        cv::merge(planes, 2, complexI);
        cv::dft(complexI, complexI);

        // 3. 三个滤波器（高斯型，图 2-50）：
        //    低通 D0=30（背景→蓝），带通 = 高斯低(60) − 高斯低(30)（→绿），
        //    高通 D0=60（边缘→红，对应 PDF「边缘成为红色」）
        const int cu = m / 2, cvn = n / 2;
        cv::Mat Hlow(m, n, CV_32F), Hhigh(m, n, CV_32F), Hband(m, n, CV_32F);
        const double dl = 30.0, dh = 60.0;
        for (int u = 0; u < m; ++u) {
            float *lRow = Hlow.ptr<float>(u);
            float *hRow = Hhigh.ptr<float>(u);
            float *bRow = Hband.ptr<float>(u);
            for (int v = 0; v < n; ++v) {
                double D2 = (u - cu) * (u - cu) + (v - cvn) * (v - cvn);
                double gl = std::exp(-D2 / (2.0 * dl * dl));   // 低通 30
                double gh = std::exp(-D2 / (2.0 * dh * dh));   // 低通 60
                lRow[v] = (float) gl;
                hRow[v] = (float) (1.0 - gh);                  // 高通 60
                bRow[v] = (float) (gh - gl);                   // 带通 [30,60]
            }
        }

        // 4. 分别滤波 → IDFT → 取幅值 → 均衡化 → R/G/B
        cv::Mat channels[3];
        cv::Mat filters[3] = {Hhigh, Hband, Hlow};   // R=高通, G=带通, B=低通
        for (int c = 0; c < 3; ++c) {
            cv::Mat sp[2];
            cv::split(complexI, sp);
            cv::multiply(sp[0], filters[c], sp[0]);
            cv::multiply(sp[1], filters[c], sp[1]);
            cv::Mat filtered;
            cv::merge(sp, 2, filtered);
            cv::idft(filtered, filtered, cv::DFT_SCALE);
            cv::Mat outPlanes[2];
            cv::split(filtered, outPlanes);
            cv::Mat real = outPlanes[0](cv::Rect(0, 0, gray.cols, gray.rows)).clone();
            // 去 (-1)^(x+y) 中心化
            for (int y = 0; y < real.rows; ++y) {
                float *row = real.ptr<float>(y);
                for (int x = 0; x < real.cols; ++x) {
                    if (((x + y) & 1) != 0) row[x] = -row[x];
                }
            }
            // 附加处理：归一化 → 直方图均衡化（图 2-50）
            cv::Mat abs8u, eq;
            cv::normalize(real, real, 0, 255, cv::NORM_MINMAX);
            real.convertTo(abs8u, CV_8U);
            cv::equalizeHist(abs8u, eq);
            channels[c] = eq;
        }

        cv::Mat dst;
        cv::merge(channels, 3, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 假彩色——线性映射·通道轮换 (式 2-97)
    // -------------------------------------------------------------------------
    cv::Mat falseColorLinear(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat rgb = toRgb(src);

        // 式 2-97：[gR gG gB]ᵀ = M·[fR fG fB]ᵀ
        // M = [[0,0,1],[1,0,0],[0,1,0]]：gR=fB, gG=fR, gB=fG
        std::vector<cv::Mat> chans;
        cv::split(rgb, chans);
        std::vector<cv::Mat> out = {chans[2], chans[0], chans[1]};
        cv::Mat dst;
        cv::merge(out, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 假彩色——细节赋予绿色 (式 2-97)：gG=f，gR=gB=低通背景
    // -------------------------------------------------------------------------
    cv::Mat falseColorGreenSensitive(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat bg;
        cv::GaussianBlur(gray, bg, cv::Size(0, 0), 5.0);   // 低通背景
        std::vector<cv::Mat> out = {bg, gray, bg};          // 细节→绿色
        cv::Mat dst;
        cv::merge(out, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 假彩色——细节赋予蓝色 (式 2-97)：gB=f，gR=gG=低通背景
    // -------------------------------------------------------------------------
    cv::Mat falseColorBlueDetail(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat bg;
        cv::GaussianBlur(gray, bg, cv::Size(0, 0), 5.0);   // 低通背景
        std::vector<cv::Mat> out = {bg, bg, gray};          // 细节→蓝色
        cv::Mat dst;
        cv::merge(out, dst);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 假彩色——多光谱合成 (式 2-98)：波段差分变换函数
    // -------------------------------------------------------------------------
    cv::Mat falseColorMultiSpectral(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat rgb = toRgb(src);

        std::vector<cv::Mat> f;                             // f1,f2,f3 三波段
        cv::split(rgb, f);

        cv::Mat f32[3], g[3], g8[3];
        for (int i = 0; i < 3; ++i) f[i].convertTo(f32[i], CV_32F);

        // 式 2-98：gR=|f1−f2|，gG=|f2−f3|，gB=|f3−f1|
        cv::absdiff(f32[0], f32[1], g[0]);
        cv::absdiff(f32[1], f32[2], g[1]);
        cv::absdiff(f32[2], f32[0], g[2]);
        for (int i = 0; i < 3; ++i) {
            cv::normalize(g[i], g[i], 0, 255, cv::NORM_MINMAX);
            g[i].convertTo(g8[i], CV_8U);
        }

        cv::Mat dst;
        cv::merge(g8, 3, dst);
        return dst;
    }
}
