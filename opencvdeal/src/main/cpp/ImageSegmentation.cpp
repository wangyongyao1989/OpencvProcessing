#include "include/ImageSegmentation.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <climits>
#include <cmath>
#include <deque>
#include <vector>

namespace ImageSegmentation {

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

    /** 大图按最长边限制降采样（重计算型算法使用）。 */
    static cv::Mat downscaleMax(const cv::Mat &gray, int maxDim) {
        double scale = std::min(1.0, (double) maxDim / std::max(gray.cols, gray.rows));
        if (scale >= 1.0) return gray.clone();
        cv::Mat small;
        cv::resize(gray, small, cv::Size(), scale, scale, cv::INTER_AREA);
        return small;
    }

    /** 灰度叠加可视化：mask 区域上色（RGB 中 r,g,b 指定），背景压暗。 */
    static cv::Mat overlayMask(const cv::Mat &gray, const cv::Mat &mask,
                               uchar r, uchar g, uchar b, double dim = 0.45) {
        cv::Mat rgb(gray.size(), CV_8UC3);
        for (int y = 0; y < gray.rows; ++y) {
            const uchar *gr = gray.ptr<uchar>(y);
            const uchar *mr = mask.ptr<uchar>(y);
            cv::Vec3b *orow = rgb.ptr<cv::Vec3b>(y);
            for (int x = 0; x < gray.cols; ++x) {
                if (mr[x]) {
                    orow[x] = cv::Vec3b(r, g, b);
                } else {
                    uchar v = cv::saturate_cast<uchar>(gr[x] * dim);
                    orow[x] = cv::Vec3b(v, v, v);
                }
            }
        }
        return rgb;   // 直接构造 RGB 顺序，与 JniHelper 3 通道路径匹配
    }

    // =========================================================================
    // 4.2 基于灰度阈值化的图像分割
    // =========================================================================

    // --- 固定阈值分割 (式 4-1) ---
    cv::Mat segFixedThreshold(const cv::Mat &src, double thresh) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        // g(x,y) = 1, f(x,y) > T ; 0, f(x,y) <= T   （工程上 1↔255 白）
        cv::threshold(gray, dst, thresh, 255, cv::THRESH_BINARY);
        return dst;
    }

    // --- 迭代式阈值分割 (式 4-3) ---
    cv::Mat segIterativeThreshold(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // T0 = 图像平均灰度
        double T = cv::mean(gray)[0];
        for (int it = 0; it < 100; ++it) {
            // G1: f > T；G2: f <= T
            double s1 = 0, s2 = 0;
            long long n1 = 0, n2 = 0;
            for (int y = 0; y < gray.rows; ++y) {
                const uchar *row = gray.ptr<uchar>(y);
                for (int x = 0; x < gray.cols; ++x) {
                    if (row[x] > T) { s1 += row[x]; ++n1; }
                    else            { s2 += row[x]; ++n2; }
                }
            }
            if (n1 == 0 || n2 == 0) break;
            // 式 4-3：T = (μ1 + μ2) / 2
            double Tn = (s1 / n1 + s2 / n2) / 2.0;
            if (std::fabs(Tn - T) < 0.5) { T = Tn; break; }   // 收敛
            T = Tn;
        }
        cv::Mat dst;
        cv::threshold(gray, dst, T, 255, cv::THRESH_BINARY);
        return dst;
    }

    // --- Otsu 最大类间方差 (式 4-4 ~ 4-10) ---
    cv::Mat segOtsu(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        // cv::threshold 内部按式 4-4~4-10 遍历 t 使类间方差 σB²(t) 最大
        cv::threshold(gray, dst, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        return dst;
    }

    // --- 局部自适应阈值分割 (式 4-2 局部化) ---
    cv::Mat segAdaptiveThreshold(const cv::Mat &src, int blockSize, double C) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        if (blockSize % 2 == 0) blockSize += 1;
        // T(x,y) = 邻域均值 - C：光照不均时逐像素自适应
        cv::adaptiveThreshold(gray, dst, 255, cv::ADAPTIVE_THRESH_MEAN_C,
                              cv::THRESH_BINARY, blockSize, C);
        return dst;
    }

    // --- 多级阈值分割 (式 4-1 推广) ---
    cv::Mat segMultiLevelThreshold(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 第一级：整图 Otsu 得 T1
        cv::Mat tmp;
        double T1 = cv::threshold(gray, tmp, 0, 255, cv::THRESH_OTSU);

        // 第二级：对 f > T1 的亮部子集手工 Otsu 得 T2
        double sum = 0, sum2 = 0;
        long long n = 0;
        std::vector<int> hist(256, 0);
        for (int y = 0; y < gray.rows; ++y) {
            const uchar *row = gray.ptr<uchar>(y);
            for (int x = 0; x < gray.cols; ++x) {
                if (row[x] > T1) { ++hist[row[x]]; ++n; sum += row[x]; sum2 += (double) row[x] * row[x]; }
            }
        }
        double T2 = (T1 + 255.0) / 2.0;
        if (n > 0) {
            // 亮部子直方图上的 Otsu
            double mu = sum / n;
            double wB = 0, muB = 0;
            double bestVar = -1;
            for (int t = (int) T1; t < 255; ++t) {
                wB += hist[t];
                if (wB == 0) continue;
                double wO = n - wB;
                if (wO == 0) break;
                muB += t * (double) hist[t];
                double mB = muB / wB;
                double mO = (sum - muB) / wO;
                double var = wB * wO * (mB - mO) * (mB - mO);   // σB²(t)
                if (var > bestVar) { bestVar = var; T2 = t; }
            }
            (void) mu; (void) sum2;
        }

        // 三类映射：暗 0 / 中 128 / 亮 255
        cv::Mat dst = cv::Mat::zeros(gray.size(), CV_8U);
        for (int y = 0; y < gray.rows; ++y) {
            const uchar *row = gray.ptr<uchar>(y);
            uchar *drow = dst.ptr<uchar>(y);
            for (int x = 0; x < gray.cols; ++x) {
                if (row[x] > T2) drow[x] = 255;
                else if (row[x] > T1) drow[x] = 128;
            }
        }
        return dst;
    }

    // --- Otsu 分割叠加可视化 ---
    cv::Mat segThresholdOverlay(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat mask;
        cv::threshold(gray, mask, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        return overlayMask(gray, mask, 250, 30, 30);   // 前景红
    }

    // =========================================================================
    // 4.3 基于边缘检测的图像分割
    // =========================================================================

    // --- Roberts 交叉差分 (式 4-18, 4-19) ---
    cv::Mat segRoberts(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        // 式 4-19：Hx = [-1 0; 0 1], Hy = [0 -1; 1 0]
        cv::Mat kx = (cv::Mat_<float>(2, 2) << -1, 0, 0, 1);
        cv::Mat ky = (cv::Mat_<float>(2, 2) << 0, -1, 1, 0);
        cv::Mat gx, gy, f32;
        gray.convertTo(f32, CV_32F);
        cv::filter2D(f32, gx, CV_32F, kx, cv::Point(0, 0));
        cv::filter2D(f32, gy, CV_32F, ky, cv::Point(0, 0));
        // 式 4-14：G ≈ |Gx| + |Gy|
        cv::Mat g = cv::abs(gx) + cv::abs(gy);
        cv::Mat dst;
        cv::normalize(g, g, 0, 255, cv::NORM_MINMAX);
        g.convertTo(dst, CV_8U);
        return dst;
    }

    // --- Sobel (式 4-20) ---
    cv::Mat segSobel(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat gx, gy;
        cv::Sobel(gray, gx, CV_32F, 1, 0, ksize);
        cv::Sobel(gray, gy, CV_32F, 0, 1, ksize);
        cv::Mat g = cv::abs(gx) + cv::abs(gy);          // 式 4-14 近似
        cv::Mat dst;
        cv::normalize(g, g, 0, 255, cv::NORM_MINMAX);
        g.convertTo(dst, CV_8U);
        return dst;
    }

    // --- Prewitt (式 4-21, 4-22) ---
    cv::Mat segPrewitt(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        // Prewitt 模板：在差分方向上取平均以平滑噪声
        cv::Mat kx = (cv::Mat_<float>(3, 3) << -1, 0, 1, -1, 0, 1, -1, 0, 1);
        cv::Mat ky = (cv::Mat_<float>(3, 3) << -1, -1, -1, 0, 0, 0, 1, 1, 1);
        cv::Mat gx, gy, f32;
        gray.convertTo(f32, CV_32F);
        cv::filter2D(f32, gx, CV_32F, kx, cv::Point(-1, -1));
        cv::filter2D(f32, gy, CV_32F, ky, cv::Point(-1, -1));
        cv::Mat g = cv::abs(gx) + cv::abs(gy);
        cv::Mat dst;
        cv::normalize(g, g, 0, 255, cv::NORM_MINMAX);
        g.convertTo(dst, CV_8U);
        return dst;
    }

    // --- Laplacian (式 4-26 ~ 4-28) ---
    cv::Mat segLaplacianEdge(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        // 式 4-26：∇²f = f(i+1,j)+f(i-1,j)+f(i,j+1)+f(i,j-1) − 4f(i,j)
        cv::Laplacian(gray, dst, CV_8U, 3, 1.0, 0, cv::BORDER_DEFAULT);
        return dst;
    }

    // --- LoG / Marr-Hildreth (式 4-29 ~ 4-33) ---
    cv::Mat segLoG(const cv::Mat &src, int ksize, double sigma) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        // 式 4-30：g = f * Gσ
        cv::Mat sm;
        cv::GaussianBlur(gray, sm, cv::Size(ksize, ksize), sigma);
        // 式 4-31：∇²(f*G) = f * ∇²G
        cv::Mat log;
        cv::Laplacian(sm, log, CV_32F, 3);

        // Marr-Hildreth 边缘 = LoG 响应的过零点
        cv::Mat dst = cv::Mat::zeros(gray.size(), CV_8U);
        const float T = 1.0f;      // 过零点强度门限，抑制噪声
        for (int y = 1; y < log.rows - 1; ++y) {
            const float *p = log.ptr<float>(y);
            const float *pu = log.ptr<float>(y - 1);
            const float *pd = log.ptr<float>(y + 1);
            uchar *d = dst.ptr<uchar>(y);
            for (int x = 1; x < log.cols - 1; ++x) {
                // 左右或上下符号相反 → 过零点
                bool zr = (p[x - 1] * p[x + 1] < 0) || (pu[x] * pd[x] < 0);
                float mag = std::max(std::fabs(p[x - 1] - p[x + 1]),
                                     std::fabs(pu[x] - pd[x]));
                if (zr && mag > T) d[x] = 255;
            }
        }
        return dst;
    }

    // --- Canny (式 4-34 ~ 4-39) ---
    cv::Mat segCanny(const cv::Mat &src, double t1, double t2) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::Canny(gray, dst, t1, t2);   // 内含式 4-34~4-39 全流程
        return dst;
    }

    // --- 轮廓跟踪 (图 4-9, 4-10) ---
    cv::Mat segContourTrace(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat edges;
        cv::Canny(gray, edges, 60, 160);

        // 检测准则找到边界像素后，按 8 邻域跟踪准则连接成闭合轮廓
        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(edges, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

        // 在暗背景上绘制跟踪得到的轮廓（闭合边界）
        cv::Mat dst = cv::Mat::zeros(gray.size(), CV_8U);
        for (size_t i = 0; i < contours.size(); ++i) {
            if (contours[i].size() < 8) continue;         // 过滤噪声小轮廓
            cv::drawContours(dst, contours, (int) i, cv::Scalar(255), 1, cv::LINE_AA);
        }
        return dst;
    }

    // =========================================================================
    // 4.4 基于区域的图像分割
    // =========================================================================

    namespace {

        /** 区域生长核心：从 seed 出发 BFS，|p − 区域均值| <= th 合并（8 邻域）。 */
        cv::Mat regionGrowBFS(const cv::Mat &gray, cv::Point seed, int th) {
            const int rows = gray.rows, cols = gray.cols;
            cv::Mat mask = cv::Mat::zeros(gray.size(), CV_8U);

            if (seed.x < 0 || seed.x >= cols || seed.y < 0 || seed.y >= rows)
                return mask;

            std::deque<cv::Point> queue;
            queue.push_back(seed);
            mask.at<uchar>(seed.y, seed.x) = 255;

            long long sum = gray.at<uchar>(seed.y, seed.x);
            long long cnt = 1;

            static const int dx[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
            static const int dy[8] = {-1, -1, -1, 0, 0, 1, 1, 1};

            while (!queue.empty()) {
                cv::Point p = queue.front();
                queue.pop_front();
                double mean = (double) sum / cnt;          // 区域平均灰度
                for (int k = 0; k < 8; ++k) {
                    int nx = p.x + dx[k], ny = p.y + dy[k];
                    if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;
                    if (mask.at<uchar>(ny, nx)) continue;
                    // 相似性准则：与区域均值灰度差
                    if (std::fabs((double) gray.at<uchar>(ny, nx) - mean) <= th) {
                        mask.at<uchar>(ny, nx) = 255;
                        sum += gray.at<uchar>(ny, nx);
                        ++cnt;
                        queue.push_back(cv::Point(nx, ny));
                    }
                }
            }
            return mask;
        }
    } // namespace

    // --- 区域生长：中心种子 (4.4.1) ---
    cv::Mat segRegionGrowCenter(const cv::Mat &src, int th) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Point seed(gray.cols / 2, gray.rows / 2);
        cv::Mat mask = regionGrowBFS(gray, seed, th);
        return overlayMask(gray, mask, 40, 230, 40);      // 区域绿
    }

    // --- 区域生长：直方图峰值自动选种 (4.4.1 种子原则 1)) ---
    cv::Mat segRegionGrowAutoSeed(const cv::Mat &src, int th) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 直方图峰值灰度级 = 像素最多的聚类中心
        cv::Mat hist;
        int histSize = 256;
        float range[] = {0, 256};
        const float *ranges[] = {range};
        cv::calcHist(&gray, 1, 0, cv::Mat(), hist, 1, &histSize, ranges);

        cv::Point maxLoc;
        cv::minMaxLoc(hist, nullptr, nullptr, nullptr, &maxLoc);
        int peakLevel = maxLoc.y;                        // 峰值灰度级

        // 该灰度级像素集合的质心，取其中最靠近质心者为种子
        long long sx = 0, sy = 0, n = 0;
        for (int y = 0; y < gray.rows; ++y)
            for (int x = 0; x < gray.cols; ++x)
                if (gray.at<uchar>(y, x) == peakLevel) { sx += x; sy += y; ++n; }
        cv::Point seed(gray.cols / 2, gray.rows / 2);
        if (n > 0) {
            cv::Point centroid((int) (sx / n), (int) (sy / n));
            long long best = LLONG_MAX;
            for (int y = 0; y < gray.rows; ++y)
                for (int x = 0; x < gray.cols; ++x)
                    if (gray.at<uchar>(y, x) == peakLevel) {
                        long long d = (x - centroid.x) * (x - centroid.x)
                                      + (y - centroid.y) * (y - centroid.y);
                        if (d < best) { best = d; seed = cv::Point(x, y); }
                    }
        }

        cv::Mat mask = regionGrowBFS(gray, seed, th);
        cv::Mat rgb = overlayMask(gray, mask, 40, 230, 40);
        // 标记种子位置（红点）：RGB 直接绘制
        cv::circle(rgb, seed, 8, cv::Scalar(255, 0, 0), -1);
        return rgb;
    }

    // --- 区域分裂与合并 (4.4.2, 图 4-11 四叉树) ---
    namespace {
        struct QuadNode {
            int x, y, w, h;
            double mean;
        };

        /** P(Ri)：区域内最大最小灰度差 <= rangeTh 视为同质。 */
        bool homogeneous(const cv::Mat &gray, int x, int y, int w, int h, int rangeTh) {
            cv::Mat roi = gray(cv::Rect(x, y, w, h));
            double mn, mx;
            cv::minMaxLoc(roi, &mn, &mx);
            return (mx - mn) <= rangeTh;
        }

        void splitQuad(const cv::Mat &gray, int x, int y, int w, int h,
                       int rangeTh, int minSize, std::vector<QuadNode> &leaves) {
            if (homogeneous(gray, x, y, w, h, rangeTh) || w <= minSize || h <= minSize) {
                cv::Mat roi = gray(cv::Rect(x, y, w, h));
                QuadNode n{x, y, w, h, cv::mean(roi)[0]};
                leaves.push_back(n);
                return;
            }
            int w2 = w / 2, h2 = h / 2;
            splitQuad(gray, x,      y,      w2, h2, rangeTh, minSize, leaves);
            splitQuad(gray, x + w2, y,      w - w2, h2, rangeTh, minSize, leaves);
            splitQuad(gray, x,      y + h2, w2, h - h2, rangeTh, minSize, leaves);
            splitQuad(gray, x + w2, y + h2, w - w2, h - h2, rangeTh, minSize, leaves);
        }

        /** 两叶子块是否共享一条边（4 邻接）。 */
        bool adjacent(const QuadNode &a, const QuadNode &b) {
            bool vAdj = (a.x + a.w == b.x || b.x + b.w == a.x) &&
                        (a.y < b.y + b.h && b.y < a.y + a.h);
            bool hAdj = (a.y + a.h == b.y || b.y + b.h == a.y) &&
                        (a.x < b.x + b.w && b.x < a.x + a.w);
            return vAdj || hAdj;
        }
    } // namespace

    cv::Mat segSplitMerge(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 大图降采样：分裂合并为块级运算，分辨率不影响方法本身
        cv::Mat small = downscaleMax(gray, 768);

        // 分裂：四叉树递归直到 P(Ri) = TRUE
        std::vector<QuadNode> leaves;
        const int rangeTh = 22;   // 同质判据：灰度极差
        const int minSize = 8;    // 最小块尺寸
        splitQuad(small, 0, 0, small.cols, small.rows, rangeTh, minSize, leaves);

        // 合并：union-find 把相邻且均值接近的叶子合并
        std::vector<int> parent(leaves.size());
        for (size_t i = 0; i < leaves.size(); ++i) parent[i] = (int) i;
        std::vector<double> sum(leaves.size()), area(leaves.size());
        for (size_t i = 0; i < leaves.size(); ++i) {
            sum[i] = leaves[i].mean * leaves[i].w * leaves[i].h;
            area[i] = leaves[i].w * leaves[i].h;
        }
        auto find = [&](int x) {
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
            return x;
        };
        auto unite = [&](int x, int y) {
            x = find(x); y = find(y);
            if (x == y) return;
            parent[x] = y;
            sum[y] += sum[x];
            area[y] += area[x];
        };
        const int mergeTh = 12;   // 相邻区域合并判据：均值差
        for (size_t i = 0; i < leaves.size(); ++i)
            for (size_t j = i + 1; j < leaves.size(); ++j) {
                if (std::fabs(leaves[i].mean - leaves[j].mean) > mergeTh) continue;
                if (adjacent(leaves[i], leaves[j])) unite((int) i, (int) j);
            }

        // 输出：每个合并区域填充其平均灰度（分段常数的分割表达）
        cv::Mat dstSmall = cv::Mat::zeros(small.size(), CV_8U);
        for (size_t i = 0; i < leaves.size(); ++i) {
            double m = sum[find((int) i)] / area[find((int) i)];
            cv::Rect roi(leaves[i].x, leaves[i].y, leaves[i].w, leaves[i].h);
            dstSmall(roi).setTo(cv::saturate_cast<uchar>(m));
        }

        cv::Mat dst;
        cv::resize(dstSmall, dst, gray.size(), 0, 0, cv::INTER_NEAREST);
        return dst;
    }

    // --- 连通区域标记伪彩色 ---
    cv::Mat segConnectedComponents(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat bin;
        cv::threshold(gray, bin, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);

        cv::Mat labels;
        int n = cv::connectedComponents(bin, labels, 8, CV_32S);

        // 每个连通区域随机着色（RGB）
        cv::Mat rgb(gray.size(), CV_8UC3, cv::Scalar(0, 0, 0));
        cv::RNG rng(12345);
        std::vector<cv::Vec3b> colors(n);
        for (int i = 1; i < n; ++i)
            colors[i] = cv::Vec3b(rng.uniform(40, 256), rng.uniform(40, 256),
                                  rng.uniform(40, 256));
        for (int y = 0; y < labels.rows; ++y)
            for (int x = 0; x < labels.cols; ++x) {
                int l = labels.at<int>(y, x);
                if (l > 0) rgb.at<cv::Vec3b>(y, x) = colors[l];
            }
        return rgb;
    }

    // --- 标记控制的分水岭 ---
    cv::Mat segWatershed(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat small = downscaleMax(gray, 768);   // Vincent-Soille 全像素排序，降采样保速度

        // 1. Otsu 前景 + 形态学开闭去噪
        cv::Mat bin;
        cv::threshold(small, bin, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        cv::Mat se = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5));
        cv::morphologyEx(bin, bin, cv::MORPH_OPEN, se);
        cv::morphologyEx(bin, bin, cv::MORPH_CLOSE, se);

        // 2. 距离变换找确定前景种子
        cv::Mat dist;
        cv::distanceTransform(bin, dist, cv::DIST_L2, 3);
        double maxDist;
        cv::minMaxLoc(dist, nullptr, &maxDist);
        cv::Mat sureFg;
        cv::threshold(dist, sureFg, 0.4 * maxDist, 255, cv::THRESH_BINARY);
        sureFg.convertTo(sureFg, CV_8U);

        // 3. 生成标记：确定前景编号 1..N，背景 = N+1，未知 = 0
        cv::Mat markers;
        int n = cv::connectedComponents(sureFg, markers, 8, CV_32S);
        markers = markers + 1;
        cv::Mat unknown;
        cv::dilate(bin, unknown, se);
        for (int y = 0; y < markers.rows; ++y)
            for (int x = 0; x < markers.cols; ++x)
                if (unknown.at<uchar>(y, x) == 0) markers.at<int>(y, x) = 0;

        // 4. 分水岭演化：边界像素标记为 WSHED(-1)
        cv::Mat color;
        cv::cvtColor(small, color, cv::COLOR_GRAY2BGR);
        cv::watershed(color, markers);

        // 5. 可视化：边界红、各区域伪彩色（RGB 直接构造）
        cv::Mat rgbSmall(small.size(), CV_8UC3, cv::Scalar(0, 0, 0));
        cv::RNG rng(54321);
        std::vector<cv::Vec3b> colors(n + 2);
        for (int i = 2; i <= n + 1; ++i)
            colors[i] = cv::Vec3b(rng.uniform(40, 220), rng.uniform(40, 220),
                                  rng.uniform(40, 220));
        for (int y = 0; y < markers.rows; ++y) {
            const int *row = markers.ptr<int>(y);
            cv::Vec3b *o = rgbSmall.ptr<cv::Vec3b>(y);
            for (int x = 0; x < markers.cols; ++x) {
                int l = row[x];
                if (l == -1) o[x] = cv::Vec3b(255, 0, 0);       // 分水岭边界
                else if (l > 1) o[x] = colors[l];
            }
        }

        cv::Mat rgb;
        cv::resize(rgbSmall, rgb, gray.size(), 0, 0, cv::INTER_NEAREST);
        return rgb;
    }

    // =========================================================================
    // 4.5 基于主动轮廓模型的图像分割
    // =========================================================================

    namespace {

        /** Snake 求解器：Williams-Shah 贪心算法。
         *
         * 能量（式 4-40/4-41）：
         *   E = Σ [ α·Econt + β·Ecurv + γ·Eimg ] (+ k·Eballoon)
         * Econt: 归一化连续项 |d̄ − |p_i − p_{i−1}||；
         * Ecurv: 曲率项 |p_{i−1} − 2p_i + p_{i+1}|²；
         * Eimg:  −|∇I|（式 4-44，梯度经高斯平滑即式 4-45）；
         * Eballoon: 气球外力项，可被测地线因子 g 调制（式 4-46 思想）。
         */
        struct SnakeParams {
            double alpha = 1.0;     // 弹性（连续性）权重
            double beta = 1.0;      // 刚性（曲率）权重
            double gamma = 1.4;     // 图像边缘能量权重
            double balloon = 0.0;   // 气球力权重（0 = 关闭）
            double edgeStop = 0.0;  // 测地线停止因子强度（0 = 恒定外力；>0 = g 调制）
        };

        /** 生成初始椭圆轮廓。 */
        std::vector<cv::Point2f> initEllipse(const cv::Mat &gray, int N,
                                             double rxScale, double ryScale) {
            std::vector<cv::Point2f> pts;
            pts.reserve(N);
            cv::Point2f c(gray.cols / 2.0f, gray.rows / 2.0f);
            float rx = gray.cols * 0.5f * (float) rxScale;
            float ry = gray.rows * 0.5f * (float) ryScale;
            for (int i = 0; i < N; ++i) {
                double a = 2.0 * CV_PI * i / N;
                pts.emplace_back(c.x + rx * std::cos(a), c.y + ry * std::sin(a));
            }
            return pts;
        }

        /** 向量 min-max 归一化到 [0,1]（Williams-Shah 能量归一化）。 */
        static void normalizeTerm(std::vector<double> &v) {
            double mn = v.empty() ? 0 : v[0], mx = mn;
            for (double e : v) { mn = std::min(mn, e); mx = std::max(mx, e); }
            if (mx - mn < 1e-9) { std::fill(v.begin(), v.end(), 0.0); return; }
            for (double &e : v) e = (e - mn) / (mx - mn);
        }

        /** 贪心迭代求解 Snake（逐点 5×5 候选窗口，各项能量归一化后加权）。 */
        void runGreedySnake(const cv::Mat &grad,        // 归一化梯度幅值 [0,1]
                            std::vector<cv::Point2f> &pts,
                            const SnakeParams &prm,
                            int iterations) {
            const int N = (int) pts.size();
            const int rows = grad.rows, cols = grad.cols;
            const int W = 2;                            // 候选窗口 ±2 像素

            for (int it = 0; it < iterations; ++it) {
                // 平均点距（归一化连续项的参考间距）
                double dbar = 0;
                for (int i = 0; i < N; ++i) {
                    cv::Point2f a = pts[i], b = pts[(i + 1) % N];
                    dbar += std::sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y));
                }
                dbar /= N;

                bool moved = false;
                for (int i = 0; i < N; ++i) {
                    cv::Point2f prev = pts[(i - 1 + N) % N];
                    cv::Point2f next = pts[(i + 1) % N];
                    cv::Point2f cur = pts[i];

                    // 外向法线（气球力方向）：切向旋转 90°，背离质心为外
                    cv::Point2f tangent(next.x - prev.x, next.y - prev.y);
                    float tlen = std::sqrt(tangent.x * tangent.x + tangent.y * tangent.y)
                                 + 1e-6f;
                    cv::Point2f normal(-tangent.y / tlen, tangent.x / tlen);
                    cv::Point2f centroid(0, 0);
                    for (int j = 0; j < N; ++j) { centroid.x += pts[j].x; centroid.y += pts[j].y; }
                    centroid.x /= N; centroid.y /= N;
                    if (normal.x * (cur.x - centroid.x) + normal.y * (cur.y - centroid.y) < 0)
                        normal = cv::Point2f(-normal.x, -normal.y);

                    // 1) 收集 5×5 候选的各能量项
                    std::vector<cv::Point2f> cand;
                    std::vector<double> eCont, eCurv, eImg, eBal;
                    for (int dy = -W; dy <= W; ++dy) {
                        for (int dx = -W; dx <= W; ++dx) {
                            cv::Point2f c(cur.x + dx, cur.y + dy);
                            if (c.x < 1 || c.x >= cols - 1 ||
                                c.y < 1 || c.y >= rows - 1) continue;
                            double dc = std::sqrt((c.x - prev.x) * (c.x - prev.x) +
                                                  (c.y - prev.y) * (c.y - prev.y));
                            eCont.push_back(std::fabs(dbar - dc));
                            double cx = prev.x - 2 * c.x + next.x;
                            double cy = prev.y - 2 * c.y + next.y;
                            eCurv.push_back(cx * cx + cy * cy);
                            eImg.push_back(-grad.at<float>((int) c.y, (int) c.x));
                            if (prm.balloon != 0) {
                                // 测地线停止函数 g = 1/(1+20|∇I|)：平坦区全力外推，
                                // 强边缘处外力→0，曲线自动停止（式 4-46 思想）
                                double g = grad.at<float>((int) c.y, (int) c.x);
                                double kEff = prm.balloon;
                                if (prm.edgeStop > 0)
                                    kEff = prm.balloon * prm.edgeStop / (1.0 + 20.0 * g);
                                eBal.push_back(-kEff * (normal.x * (c.x - cur.x) +
                                                        normal.y * (c.y - cur.y)));
                            } else {
                                eBal.push_back(0);
                            }
                            cand.push_back(c);
                        }
                    }
                    if (cand.empty()) continue;

                    // 2) 各项能量归一化（Williams-Shah）
                    normalizeTerm(eCont);
                    normalizeTerm(eCurv);
                    normalizeTerm(eImg);
                    normalizeTerm(eBal);

                    // 3) 加权取最小
                    double bestE = 1e18;
                    size_t bestI = 0;
                    for (size_t k = 0; k < cand.size(); ++k) {
                        double e = prm.alpha * eCont[k] + prm.beta * eCurv[k]
                                   + prm.gamma * eImg[k] + eBal[k];
                        if (e < bestE - 1e-9) { bestE = e; bestI = k; }
                    }
                    if (cand[bestI] != cur) moved = true;
                    pts[i] = cand[bestI];
                }
                if (!moved) break;   // 收敛：轮廓不再变化即停
            }
        }

        cv::Mat makeGradField(const cv::Mat &gray, double blurSigma) {
            cv::Mat sm;
            cv::GaussianBlur(gray, sm, cv::Size(0, 0), blurSigma);
            cv::Mat gx, gy, g;
            cv::Sobel(sm, gx, CV_32F, 1, 0, 3);
            cv::Sobel(sm, gy, CV_32F, 0, 1, 3);
            cv::magnitude(gx, gy, g);
            cv::normalize(g, g, 0, 1, cv::NORM_MINMAX);
            return g;
        }

        /** 灰度图上绘制轮廓（RGB 输出）。 */
        cv::Mat drawSnakeOnGray(const cv::Mat &gray,
                                const std::vector<cv::Point2f> &pts,
                                cv::Scalar rgbColor, int thickness = 4) {
            cv::Mat bgr;
            cv::cvtColor(gray, bgr, cv::COLOR_GRAY2BGR);
            std::vector<cv::Point> ip;
            ip.reserve(pts.size());
            for (auto &p : pts) ip.emplace_back(cvRound(p.x), cvRound(p.y));
            cv::Scalar c(rgbColor[2], rgbColor[1], rgbColor[0]);   // RGB→BGR
            std::vector<std::vector<cv::Point>> cs{ip};
            cv::polylines(bgr, cs, true, c, thickness, cv::LINE_AA);
            for (auto &p : ip) cv::circle(bgr, p, 2, c, -1, cv::LINE_AA);
            cv::Mat rgb;
            cv::cvtColor(bgr, rgb, cv::COLOR_BGR2RGB);
            return rgb;
        }
    } // namespace

    // --- 基本贪心 Snake (式 4-40, 4-42 ~ 4-45) ---
    cv::Mat segSnake(const cv::Mat &src, int iterations) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat grad = makeGradField(gray, 2.0);       // 式 4-45：高斯平滑后梯度

        auto pts = initEllipse(gray, 120, 0.42, 0.42);
        SnakeParams prm;                               // α=1, β=1, γ=1.4
        runGreedySnake(grad, pts, prm, iterations);
        return drawSnakeOnGray(gray, pts, cv::Scalar(255, 40, 40));
    }

    // --- 内部能量 α/β 对比 (式 4-41) ---
    cv::Mat segSnakeSmoothCompare(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat grad = makeGradField(gray, 2.0);

        auto ptsW = initEllipse(gray, 120, 0.42, 0.42);   // 弱正则
        SnakeParams pw; pw.alpha = 0.3; pw.beta = 0.3; pw.gamma = 1.8;
        runGreedySnake(grad, ptsW, pw, 90);

        auto ptsS = initEllipse(gray, 120, 0.42, 0.42);   // 强正则
        SnakeParams ps; ps.alpha = 2.2; ps.beta = 9.0; ps.gamma = 1.0;
        runGreedySnake(grad, ptsS, ps, 90);

        cv::Mat bgr;
        cv::cvtColor(gray, bgr, cv::COLOR_GRAY2BGR);
        auto draw = [&](std::vector<cv::Point2f> &pts, cv::Scalar bgrColor) {
            std::vector<cv::Point> ip;
            for (auto &p : pts) ip.emplace_back(cvRound(p.x), cvRound(p.y));
            std::vector<std::vector<cv::Point>> cs{ip};
            cv::polylines(bgr, cs, true, bgrColor, 4, cv::LINE_AA);
        };
        draw(ptsW, cv::Scalar(40, 40, 255));          // 红：弱 α/β → 贴近边缘但毛糙
        draw(ptsS, cv::Scalar(80, 255, 80));          // 绿：强 α/β → 平滑但欠贴合
        cv::Mat rgb;
        cv::cvtColor(bgr, rgb, cv::COLOR_BGR2RGB);
        return rgb;
    }

    // --- 气球力 Snake ---
    cv::Mat segBalloonSnake(const cv::Mat &src, int iterations) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat grad = makeGradField(gray, 2.0);

        auto pts = initEllipse(gray, 120, 0.22, 0.22);   // 更小的初始轮廓
        SnakeParams prm;
        prm.balloon = 1.0;    // 气球外力推动轮廓向外膨胀
        runGreedySnake(grad, pts, prm, iterations);
        return drawSnakeOnGray(gray, pts, cv::Scalar(255, 40, 40));
    }

    // --- 测地线主动轮廓离散近似 (式 4-46) ---
    cv::Mat segGeodesicContour(const cv::Mat &src, int iterations) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat grad = makeGradField(gray, 2.5);

        auto pts = initEllipse(gray, 120, 0.18, 0.18);
        SnakeParams prm;
        prm.balloon = 1.4;
        prm.edgeStop = 1.0;   // g = |∇I|/(|∇I|+ε) 调制：强边缘处外力→0
        runGreedySnake(grad, pts, prm, iterations);
        return drawSnakeOnGray(gray, pts, cv::Scalar(60, 200, 255));
    }

    // --- 区域型主动轮廓 Chan-Vese 简化 (式 4-47) ---
    cv::Mat segChanVese(const cv::Mat &src, int iterations) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);

        // 降采样迭代保证速度（区域能量对分辨率不敏感）
        cv::Mat smallF;
        cv::resize(gray, smallF, cv::Size(), 
                   std::min(1.0, 512.0 / std::max(gray.cols, gray.rows)),
                   std::min(1.0, 512.0 / std::max(gray.cols, gray.rows)),
                   cv::INTER_AREA);
        smallF.convertTo(smallF, CV_32F, 1.0 / 255.0);

        // φ 初始化：中心圆内 +1 / 圆外 −1
        cv::Mat phi = cv::Mat::ones(smallF.size(), CV_32F);
        cv::Point c(smallF.cols / 2, smallF.rows / 2);
        int r = (int) (0.4 * std::min(smallF.cols, smallF.rows));
        cv::circle(phi, c, r, -1.0, -1);

        for (int it = 0; it < iterations; ++it) {
            // 1) 区域能量最小：c1/c2 = 轮廓内外区域均值（式 4-47）
            cv::Scalar mIn = cv::mean(smallF, phi > 0);
            cv::Scalar mOut = cv::mean(smallF, phi <= 0);
            double c1 = mIn[0], c2 = mOut[0];

            // 2) 像素隶属更新：|I−c1|² < |I−c2|² → 内部
            cv::Mat eIn, eOut, inside;
            cv::absdiff(smallF, c1, eIn); cv::multiply(eIn, eIn, eIn);
            cv::absdiff(smallF, c2, eOut); cv::multiply(eOut, eOut, eOut);
            inside = (eIn < eOut);                        // 8U: 0/255

            // 3) 长度正则：高斯平滑隶属函数近似 μ·Length(C) 惩罚
            cv::Mat newPhi;
            inside.convertTo(newPhi, CV_32F, 2.0 / 255.0, -1.0);  // 0→−1, 255→+1
            cv::GaussianBlur(newPhi, newPhi, cv::Size(9, 9), 2.5);
            phi = (newPhi > 0);                           // 8U: 0/255
            phi.convertTo(phi, CV_32F, 2.0 / 255.0, -1.0);
        }

        // 放大掩膜回原尺寸并叠加可视化
        cv::Mat phiUp, mask;
        cv::resize(phi, phiUp, gray.size(), 0, 0, cv::INTER_NEAREST);
        mask = (phiUp > 0);                               // CV_8U: 0/255
        return overlayMask(gray, mask, 250, 30, 30);
    }
}
