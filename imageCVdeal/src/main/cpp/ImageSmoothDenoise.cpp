#include "include/ImageSmoothDenoise.h"
#include "include/PerfTimer.h"
#include "include/JniHelper.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/photo.hpp>
#include <cmath>
#include <algorithm>
#include <vector>

namespace ImageSmoothDenoise {

// 耗时打点用（PerfTimer.h 的 ScopedLogTimer 需要 tag 参数）
#define TAG "ImageSmoothDenoise"

    // -------------------------------------------------------------------------
    // 内部工具：转灰度（输入可能是 RGBA）
    // -------------------------------------------------------------------------
    /** 输入转灰度：统一委托 JniHelper::toGray，保证全工程灰度口径一致。 */
    static cv::Mat toGray(const cv::Mat &src) { return JniHelper::toGray(src); }

    // -------------------------------------------------------------------------
    // 4-邻域平均法 (式 2-23, 式 2-24, 模板 H1 式 2-25)
    // g(x,y) = (1/4)[f(x-1,y) + f(x,y-1) + f(x,y+1) + f(x+1,y)]
    // 模板 H1 = (1/4)[[0,1,0],[1,0,1],[0,1,0]]（不含中心点）
    // -------------------------------------------------------------------------
    cv::Mat neighborhoodAverage4(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat kernel = (cv::Mat_<float>(3, 3) <<
                                             0.f, 0.25f, 0.f,
                0.25f, 0.f, 0.25f,
                0.f, 0.25f, 0.f);
        cv::Mat dst;
        cv::filter2D(gray, dst, CV_8U, kernel, cv::Point(-1, -1), 0, cv::BORDER_REPLICATE);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 8-邻域平均法 (式 2-26, 模板 H2 式 2-27)
    // g(x,y) = (1/8)Σ_{(i,j)∈N8} f(i,j)
    // 模板 H2 = (1/8)[[1,1,1],[1,0,1],[1,1,1]]（不含中心点）
    // -------------------------------------------------------------------------
    cv::Mat neighborhoodAverage8(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        cv::Mat kernel = (cv::Mat_<float>(3, 3) <<
                                             0.125f, 0.125f, 0.125f,
                0.125f, 0.f, 0.125f,
                0.125f, 0.125f, 0.125f);
        cv::Mat dst;
        cv::filter2D(gray, dst, CV_8U, kernel, cv::Point(-1, -1), 0, cv::BORDER_REPLICATE);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 阈值邻域平均法 (式 2-28)
    //   g(x,y) = (1/M)Σf(i,j),  当 |f(x,y) - (1/M)Σf(i,j)| > T（判为噪声）
    //          = f(x,y),          其他（保留原值，减轻模糊）
    // 邻域均值采用 8-邻域模板（式 2-26）计算。
    // -------------------------------------------------------------------------
    cv::Mat thresholdAverage(const cv::Mat &src, double T) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 8-邻域均值（不含中心），保留浮点精度
        cv::Mat kernel = (cv::Mat_<float>(3, 3) <<
                                             0.125f, 0.125f, 0.125f,
                0.125f, 0.f, 0.125f,
                0.125f, 0.125f, 0.125f);
        cv::Mat avg;
        cv::filter2D(gray, avg, CV_32F, kernel, cv::Point(-1, -1), 0, cv::BORDER_REPLICATE);

        cv::Mat dst = gray.clone();
        const int rows = gray.rows, cols = gray.cols;
        for (int y = 0; y < rows; ++y) {
            const uchar *g = gray.ptr<uchar>(y);
            const float *a = avg.ptr<float>(y);
            uchar *d = dst.ptr<uchar>(y);
            for (int x = 0; x < cols; ++x) {
                double diff = std::fabs(static_cast<double>(g[x]) - static_cast<double>(a[x]));
                if (diff > T) {
                    d[x] = cv::saturate_cast<uchar>(a[x] + 0.5);
                }
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // 3×3 方形窗口中值滤波 (式 2-29, 式 2-30)
    // yij = Med_W{Fij}：窗口内 9 个像素排序取中间值。
    // 采用 cv::medianBlur 实现，对椒盐噪声最有效且保护边缘。
    // -------------------------------------------------------------------------
    cv::Mat medianFilter3x3(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::medianBlur(gray, dst, 3);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 5×5 十字形窗口中值滤波 (式 2-29, 式 2-30；图 2-21, 图 2-23f)
    // 十字窗口共 9 个像素：中心 + 水平(±1,±2) + 垂直(±1,±2)，
    // 排序取中值代替中心像素。边界采用复制（replicate）方式处理。
    // -------------------------------------------------------------------------
    cv::Mat medianFilterCross5x5(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        // dst 的每个像素都会在下面被写入一次，无需先 clone 一份 gray 作为初值
        cv::Mat dst;
        dst.create(gray.size(), gray.type());

        const int rows = gray.rows, cols = gray.cols;

        // 逐行并行：每个输出像素只读原始 gray、只写自己的 dst 像素，行间无依赖；
        // 9 个取样点的顺序（k=0..8 的固定偏移 + replicate 夹紧）与串行版逐位一致，
        // 排序入参序列相同 ⇒ 中值逐像素相同。灰度访问改行指针，省掉 gray.at 的 step 计算。
        cv::parallel_for_(cv::Range(0, rows), [&](const cv::Range &rg) {
            std::vector<uchar> vals(9);
            for (int y = rg.start; y < rg.end; ++y) {
                // 十字窗口 5 个行号：k=0..4 都在中心行，k=5..8 分别是 y−1/y+1/y−2/y+2
                const uchar *rc = gray.ptr<uchar>(y);
                const uchar *rm1 = gray.ptr<uchar>(std::max(y - 1, 0));
                const uchar *rp1 = gray.ptr<uchar>(std::min(y + 1, rows - 1));
                const uchar *rm2 = gray.ptr<uchar>(std::max(y - 2, 0));
                const uchar *rp2 = gray.ptr<uchar>(std::min(y + 2, rows - 1));
                uchar *dRow = dst.ptr<uchar>(y);
                for (int x = 0; x < cols; ++x) {
                    const int a1 = std::max(x - 1, 0);          // 夹紧后的 x−1
                    const int p1 = std::min(x + 1, cols - 1);   // 夹紧后的 x+1
                    const int a2 = std::max(x - 2, 0);          // 夹紧后的 x−2
                    const int p2 = std::min(x + 2, cols - 1);   // 夹紧后的 x+2
                    vals[0] = rc[x];      // (0,0)
                    vals[1] = rc[a1];     // (−1,0)
                    vals[2] = rc[p1];     // (+1,0)
                    vals[3] = rc[a2];     // (−2,0)
                    vals[4] = rc[p2];     // (+2,0)
                    vals[5] = rm1[x];     // (0,−1)
                    vals[6] = rp1[x];     // (0,+1)
                    vals[7] = rm2[x];     // (0,−2)
                    vals[8] = rp2[x];     // (0,+2)
                    std::sort(vals.begin(), vals.end());
                    dRow[x] = vals[4];   // 9 个数的中值为第 5 个
                }
            }
        });
        return dst;
    }

    // -------------------------------------------------------------------------
    // 频率域低通滤波通用实现 (式 2-40 ~ 式 2-47)
    // 流程（图 2-27）：f(x,y) → 乘(-1)^(x+y) 中心化 → 补零 → DFT →
    //                  G(u,v)=H(u,v)F(u,v) → IDFT → 再乘(-1)^(x+y) 复原 → 截断到 [0,255]
    // -------------------------------------------------------------------------
    static cv::Mat frequencyLowPass(const cv::Mat &gray, double D0, bool gaussian) {
        // 1. 转浮点并乘 (-1)^(x+y)，把频谱原点移到中心
        cv::Mat f;
        gray.convertTo(f, CV_32F);
        for (int y = 0; y < f.rows; ++y) {
            float *row = f.ptr<float>(y);
            for (int x = 0; x < f.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }

        // 2. 补零到最优 DFT 尺寸（取偶数，保证 (-1)^(x+y) 中心化精确）
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

        // 4. 构造传递函数 H(u,v)，中心位于 (m/2, n/2)，式 2-42：D(u,v)=√(u²+v²)
        cv::Mat H(m, n, CV_32F);
        const int cu = m / 2, cvn = n / 2;
        // 逐行并行（每个 hRow[v] 只依赖自身坐标）；保留先 sqrt 再平方的写法，
        // 不做 sqrt↔D² 的代数化简，高斯路径的浮点末位与串行版逐位相同。
        cv::parallel_for_(cv::Range(0, m), [&](const cv::Range &rg) {
            for (int u = rg.start; u < rg.end; ++u) {
                float *hRow = H.ptr<float>(u);
                for (int v = 0; v < n; ++v) {
                    double D = std::sqrt(static_cast<double>(
                            (u - cu) * (u - cu) + (v - cvn) * (v - cvn)));
                    // 式 2-41：理想低通 H=1 (D≤D0) / 0 (D>D0)
                    // 式 2-46：高斯低通 H=exp(-D²/(2D0²))
                    hRow[v] = gaussian
                              ? static_cast<float>(std::exp(-D * D / (2.0 * D0 * D0)))
                              : static_cast<float>(D <= D0 ? 1.0 : 0.0);
                }
            }
        });

        // 5. 频域相乘 G(u,v) = H(u,v)·F(u,v)（式 2-40）
        cv::split(complexI, planes);
        cv::multiply(planes[0], H, planes[0]);
        cv::multiply(planes[1], H, planes[1]);
        cv::merge(planes, 2, complexI);

        // 6. IDFT（H 实对称、F 共轭对称，结果为实图像，取实部即可）
        cv::idft(complexI, complexI, cv::DFT_SCALE);
        cv::Mat outPlanes[2];
        cv::split(complexI, outPlanes);
        cv::Mat real = outPlanes[0];

        // 7. 再乘 (-1)^(x+y) 复原、裁剪到原始尺寸、饱和截断到 [0,255]
        // 不再 clone：result 就地取负之后只被 convertTo 读走，父缓冲
        // outPlanes[0]（split 出来的独立缓冲）此后没有任何读者，
        // 且 outPlanes 的生命周期覆盖 result 的全部使用期 ⇒ 像素逐位相同，
        // 省掉一次 padded 尺寸的 float 全图拷贝。
        cv::Mat result = real(cv::Rect(0, 0, gray.cols, gray.rows));
        for (int y = 0; y < result.rows; ++y) {
            float *row = result.ptr<float>(y);
            for (int x = 0; x < result.cols; ++x) {
                if (((x + y) & 1) != 0) row[x] = -row[x];
            }
        }
        cv::Mat dst;
        result.convertTo(dst, CV_8U);
        return dst;
    }

    // -------------------------------------------------------------------------
    // 理想低通滤波 (式 2-40 ~ 式 2-42)
    // H(u,v) = 1 (D≤D0) / 0 (D>D0)；截止频率外分量完全滤除，
    // 去噪彻底但模糊明显，且伴有振铃现象。
    // -------------------------------------------------------------------------
    cv::Mat idealLowPass(const cv::Mat &src, double D0) {
        if (src.empty()) return src;
        return frequencyLowPass(toGray(src), D0, false);
    }

    // -------------------------------------------------------------------------
    // 高斯低通滤波 (式 2-45, 式 2-46)
    // H(u,v) = exp(-D²(u,v)/(2D0²))；高低频平滑过渡，无振铃。
    // -------------------------------------------------------------------------
    cv::Mat gaussianLowPass(const cv::Mat &src, double D0) {
        if (src.empty()) return src;
        return frequencyLowPass(toGray(src), D0, true);
    }

    // -------------------------------------------------------------------------
    // 非局部均值 (NLM) 去噪 (式 2-31, 式 2-32)
    // w(i,j) = exp(-d(i,j)/h²)，f'(i) = Σ w(i,j)f(j) / Σ w(i,j)
    // 采用 OpenCV fastNlMeansDenoising 实现（模板窗口 7×7，搜索窗口 21×21）。
    // -------------------------------------------------------------------------
    cv::Mat nlmDenoise(const cv::Mat &src, double h) {
        ScopedLogTimer timer(TAG, "nlmDenoise");
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::fastNlMeansDenoising(gray, dst, static_cast<float>(h), 7, 21);
        return dst;
    }
}
