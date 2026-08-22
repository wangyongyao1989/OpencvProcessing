#include "include/ImageSharpen.h"
#include <opencv2/imgproc.hpp>
#include <cmath>
#include <cstdlib>

namespace ImageSharpen {

    // -------------------------------------------------------------------------
    // 内部工具：转灰度（输入可能是 RGBA）
    // -------------------------------------------------------------------------
    static cv::Mat toGray(const cv::Mat &src) {
        cv::Mat gray;
        if (src.channels() >= 3) {
            cv::cvtColor(src, gray, cv::COLOR_RGB2GRAY);
        } else {
            gray = src.clone();
        }
        return gray;
    }

    // -------------------------------------------------------------------------
    // 水平垂直差分法 (式 2-56, 输出式 2-58)
    // G[f(i,j)] = |f(i+1,j) − f(i,j)| + |f(i,j+1) − f(i,j)|
    // 最后一行/列用前一行/列的梯度值近似代替（PDF 注记）。
    // -------------------------------------------------------------------------
    cv::Mat gradientHorizVert(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        const int rows = gray.rows, cols = gray.cols;
        cv::Mat dst = cv::Mat::zeros(gray.size(), CV_8U);
        for (int y = 0; y < rows; ++y) {
            for (int x = 0; x < cols; ++x) {
                // 下邻（最后一行用当前行近似）
                int y1 = (y + 1 < rows) ? y + 1 : y;
                // 右邻（最后一列用当前列近似）
                int x1 = (x + 1 < cols) ? x + 1 : x;
                int gx = std::abs(static_cast<int>(gray.at<uchar>(y1, x))
                                  - static_cast<int>(gray.at<uchar>(y, x)));
                int gy = std::abs(static_cast<int>(gray.at<uchar>(y, x1))
                                  - static_cast<int>(gray.at<uchar>(y, x)));
                // 式 2-58：g(i,j) = G[f(i,j)]
                dst.at<uchar>(y, x) = cv::saturate_cast<uchar>(gx + gy);
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // Roberts 梯度（交叉差分法）(式 2-57, 输出式 2-58)
    // G[f(i,j)] = |f(i+1,j+1) − f(i,j)| + |f(i,j+1) − f(i+1,j)|
    // -------------------------------------------------------------------------
    cv::Mat robertsGradient(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        const int rows = gray.rows, cols = gray.cols;
        cv::Mat dst = cv::Mat::zeros(gray.size(), CV_8U);
        for (int y = 0; y < rows; ++y) {
            for (int x = 0; x < cols; ++x) {
                // 交叉差分的四个采样点（越界用当前点近似）
                int y1 = (y + 1 < rows) ? y + 1 : y;
                int x1 = (x + 1 < cols) ? x + 1 : x;
                int g1 = std::abs(static_cast<int>(gray.at<uchar>(y1, x1))
                                  - static_cast<int>(gray.at<uchar>(y, x)));
                int g2 = std::abs(static_cast<int>(gray.at<uchar>(y, x1))
                                  - static_cast<int>(gray.at<uchar>(y1, x)));
                dst.at<uchar>(y, x) = cv::saturate_cast<uchar>(g1 + g2);
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // Sobel 算子锐化 (式 2-63 ~ 式 2-66)
    // 模板式 2-65：Hx = [[−1,0,1],[−2,0,2],[−1,0,1]]，Hy = [[−1,−2,−1],[0,0,0],[1,2,1]]
    // 简化式（式 2-66 的简化形式）：g(i,j) = |Gx| + |Gy|
    // -------------------------------------------------------------------------
    cv::Mat sobelOperator(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 16S 保留带符号梯度，避免 8U 截断
        cv::Mat gx, gy;
        cv::Sobel(gray, gx, CV_16S, 1, 0, 3);
        cv::Sobel(gray, gy, CV_16S, 0, 1, 3);

        cv::Mat absGx, absGy, dst;
        cv::convertScaleAbs(gx, absGx);   // |Gx|
        cv::convertScaleAbs(gy, absGy);   // |Gy|
        cv::add(absGx, absGy, dst);       // g = |Gx| + |Gy|
        return dst;
    }

    // -------------------------------------------------------------------------
    // 拉普拉斯直接锐化（模板 H1）(式 2-70, 式 2-71)
    // ∇²f = f(i+1,j)+f(i−1,j)+f(i,j+1)+f(i,j−1) − 4f(i,j)
    // H1 = [[0,1,0],[1,−4,1],[0,1,0]]，取 |∇²f| 显示边缘。
    // -------------------------------------------------------------------------
    cv::Mat laplacianDirect(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat kernel = (cv::Mat_<float>(3, 3) <<
                                            0.f, 1.f, 0.f,
                1.f, -4.f, 1.f,
                0.f, 1.f, 0.f);
        cv::Mat lap;
        cv::filter2D(gray, lap, CV_16S, kernel, cv::Point(-1, -1), 0, cv::BORDER_REPLICATE);

        cv::Mat dst;
        cv::convertScaleAbs(lap, dst);    // |∇²f| → 8U
        return dst;
    }

    // -------------------------------------------------------------------------
    // 合成拉普拉斯锐化（模板 H6，k=1）(式 2-72 ~ 式 2-74)
    // g(i,j) = f(i,j) − ∇²f = 5f(i,j) − f(i+1,j) − f(i−1,j) − f(i,j+1) − f(i,j−1)
    // H6 = [[0,−1,0],[−1,5,−1],[0,−1,0]]，保背景的同时增强边缘。
    // -------------------------------------------------------------------------
    cv::Mat laplacianCompositeH6(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat kernel = (cv::Mat_<float>(3, 3) <<
                                            0.f, -1.f, 0.f,
                -1.f, 5.f, -1.f,
                0.f, -1.f, 0.f);
        cv::Mat dst;
        cv::filter2D(gray, dst, CV_8U, kernel, cv::Point(-1, -1), 0, cv::BORDER_REPLICATE);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 合成拉普拉斯锐化（模板 H7，8 邻域）
    // H7 = [[−1,−1,−1],[−1,9,−1],[−1,−1,−1]]（中心 9 = 1 + 8）
    // -------------------------------------------------------------------------
    cv::Mat laplacianCompositeH7(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat kernel = (cv::Mat_<float>(3, 3) <<
                                            -1.f, -1.f, -1.f,
                -1.f, 9.f, -1.f,
                -1.f, -1.f, -1.f);
        cv::Mat dst;
        cv::filter2D(gray, dst, CV_8U, kernel, cv::Point(-1, -1), 0, cv::BORDER_REPLICATE);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 频率域高通滤波：提取高频边缘图 (式 2-40, 式 2-75/2-76, 式 2-79)
    // 流程：f(x,y) → 乘(-1)^(x+y) 中心化 → 补零 → DFT →
    //       G(u,v)=H(u,v)F(u,v) → IDFT → 再乘(-1)^(x+y) 复原 → 截断 [0,255]
    // -------------------------------------------------------------------------
    static cv::Mat frequencyHighPassEdges(const cv::Mat &gray, double D0, bool gaussian) {
        // 1. 转浮点并乘 (-1)^(x+y)，把频谱原点移到中心
        cv::Mat f;
        gray.convertTo(f, CV_32F);
        for (int y = 0; y < f.rows; ++y) {
            float *row = f.ptr<float>(y);
            for (int x = 0; x < f.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }

        // 2. 补零到最优 DFT 尺寸（取偶数）
        int m = cv::getOptimalDFTSize(f.rows);
        if (m % 2) m++;
        int n = cv::getOptimalDFTSize(f.cols);
        if (n % 2) n++;
        cv::Mat padded;
        cv::copyMakeBorder(f, padded, 0, m - f.rows, 0, n - f.cols,
                           cv::BORDER_CONSTANT, cv::Scalar::all(0));

        // 3. DFT
        cv::Mat planes[] = {padded, cv::Mat::zeros(padded.size(), CV_32F)};
        cv::Mat complexI;
        cv::merge(planes, 2, complexI);
        cv::dft(complexI, complexI);

        // 4. 高通传递函数 H(u,v)，中心 (m/2, n/2)，式 2-76：D(u,v)=√(u²+v²)
        //    式 2-75：理想高通 H = 0 (D≤D0) / 1 (D>D0)
        //    式 2-79：高斯高通 H = 1 − e^(−D²/(2D0²))
        cv::Mat H(m, n, CV_32F);
        const int cu = m / 2, cvn = n / 2;
        for (int u = 0; u < m; ++u) {
            float *hRow = H.ptr<float>(u);
            for (int v = 0; v < n; ++v) {
                double D = std::sqrt(static_cast<double>(
                        (u - cu) * (u - cu) + (v - cvn) * (v - cvn)));
                hRow[v] = gaussian
                          ? static_cast<float>(1.0 - std::exp(-D * D / (2.0 * D0 * D0)))
                          : static_cast<float>(D > D0 ? 1.0 : 0.0);
            }
        }

        // 5. 频域相乘 G(u,v) = H(u,v)·F(u,v)（式 2-40）
        cv::split(complexI, planes);
        cv::multiply(planes[0], H, planes[0]);
        cv::multiply(planes[1], H, planes[1]);
        cv::merge(planes, 2, complexI);

        // 6. IDFT 取实部
        cv::idft(complexI, complexI, cv::DFT_SCALE);
        cv::Mat outPlanes[2];
        cv::split(complexI, outPlanes);
        cv::Mat real = outPlanes[0];

        // 7. 再乘 (-1)^(x+y) 复原、裁剪到原始尺寸
        cv::Mat result = real(cv::Rect(0, 0, gray.cols, gray.rows)).clone();
        for (int y = 0; y < result.rows; ++y) {
            float *row = result.ptr<float>(y);
            for (int x = 0; x < result.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }

        cv::Mat edges;
        result.convertTo(edges, CV_8U);   // 高频边缘图（可为负，截断到 [0,255]）
        return edges;
    }

    // -------------------------------------------------------------------------
    // 理想高通滤波锐化 (式 2-75, 式 2-76)
    // 高通提取边缘信息 → 附加到原图：g = f + 高频边缘（饱和截断）。
    // -------------------------------------------------------------------------
    cv::Mat idealHighPassSharpen(const cv::Mat &src, double D0) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat edges = frequencyHighPassEdges(gray, D0, false);

        cv::Mat dst;
        cv::add(gray, edges, dst, cv::noArray(), CV_8U);   // g = f + HP（自动饱和）
        return dst;
    }

    // -------------------------------------------------------------------------
    // 高斯高通滤波锐化 (式 2-79)
    // H(u,v) = 1 − e^(−D²/(2D0²))；同样将高频边缘附加到原图。
    // -------------------------------------------------------------------------
    cv::Mat gaussianHighPassSharpen(const cv::Mat &src, double D0) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat edges = frequencyHighPassEdges(gray, D0, true);

        cv::Mat dst;
        cv::add(gray, edges, dst, cv::noArray(), CV_8U);
        return dst;
    }
}
