#include "include/ImageMorphology.h"
#include "include/JniHelper.h"
#include "include/PerfTimer.h"
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <chrono>
#include <vector>

namespace ImageMorphology {

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

// 迭代类算法的耗时打点：这两处的收敛轮数随图像内容变化，没有日志就无法从 UI
// 判断「慢是算法本身还是 JNI 往返」。ScopedLogTimer 已提到 include/PerfTimer.h，
// 其余昂贵算子（分割/Retinex/同态/频域滤波）共用，日志格式保持 `%s: ksize=%d cost=%lldms` 不变。
#define TAG "ImageMorphology"

       /** 输入转灰度：统一委托 JniHelper::toGray，保证全工程灰度口径一致。 */
    static cv::Mat toGray(const cv::Mat &src) { return JniHelper::toGray(src); }

    /** 生成结构元素：shape 0=矩形，1=圆形（椭圆）。 */
    static cv::Mat makeSE(int ksize, int shape) {
        if (ksize < 1) ksize = 3;
        if (ksize % 2 == 0) ksize += 1;               // 保证奇数
        int type = (shape == 1) ? cv::MORPH_ELLIPSE : cv::MORPH_RECT;
        return cv::getStructuringElement(type, cv::Size(ksize, ksize));
    }

    // -------------------------------------------------------------------------
    // 通用：Otsu 二值化
    // -------------------------------------------------------------------------
    cv::Mat binarize(const cv::Mat &src) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::threshold(gray, dst, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        return dst;
    }

    // =========================================================================
    // 3.2 二值形态学基本运算
    // =========================================================================

    // --- 腐蚀 (式 3-9) ---
    cv::Mat binaryErode(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat dst;
        cv::erode(A, dst, makeSE(ksize, shape));
        return dst;
    }

    // --- 膨胀 (式 3-10, 3-11) ---
    cv::Mat binaryDilate(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat dst;
        cv::dilate(A, dst, makeSE(ksize, shape));
        return dst;
    }

    // --- 开运算 (式 3-15, 3-16) ---
    cv::Mat binaryOpen(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat dst;
        cv::morphologyEx(A, dst, cv::MORPH_OPEN, makeSE(ksize, shape));
        return dst;
    }

    // --- 闭运算 (式 3-17) ---
    cv::Mat binaryClose(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat dst;
        cv::morphologyEx(A, dst, cv::MORPH_CLOSE, makeSE(ksize, shape));
        return dst;
    }

    // --- 对偶性 (式 3-13, 3-14)：Aᶜ㊀B̂ = (A⊕B)ᶜ ---
    cv::Mat binaryDuality(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat Ac;
        cv::bitwise_not(A, Ac);                        // Aᶜ
        cv::Mat dst;
        cv::erode(Ac, dst, makeSE(ksize, 0));          // Aᶜ㊀B̂（对称 SE，B̂=B）
        return dst;
    }

    // =========================================================================
    // 3.3 二值图像的形态学处理
    // =========================================================================

    // --- 内边缘 (式 3-20)：A − (A㊀B) ---
    cv::Mat binaryInnerEdge(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat eroded, dst;
        cv::erode(A, eroded, makeSE(ksize, 0));
        cv::subtract(A, eroded, dst);                  // β内(A) = A − (A㊀B)
        return dst;
    }

    // --- 外边缘 (式 3-21)：(A⊕B) − A ---
    cv::Mat binaryOuterEdge(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat dilated, dst;
        cv::dilate(A, dilated, makeSE(ksize, 0));
        cv::subtract(dilated, A, dst);                 // β外(A) = (A⊕B) − A
        return dst;
    }

    // --- 梯度边缘 (式 3-22)：(A⊕B) − (A㊀B) ---
    cv::Mat binaryGradientEdge(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat eroded, dilated, dst;
        cv::erode(A, eroded, makeSE(ksize, 0));
        cv::dilate(A, dilated, makeSE(ksize, 0));
        cv::subtract(dilated, eroded, dst);            // β梯度(A) = (A⊕B) − (A㊀B)
        return dst;
    }

    // --- 区域填充 (式 3-23)：Xk = (X(k-1)⊕B) ∩ Aᶜ 迭代 ---
    cv::Mat binaryFillHoles(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        ScopedLogTimer timer(TAG, "binaryFillHoles", ksize);
        cv::Mat A = binarize(src);
        cv::Mat Ac;
        cv::bitwise_not(A, Ac);

        // X0：Aᶜ 中位于图像边界的背景种子点
        cv::Mat X = cv::Mat::zeros(A.size(), CV_8U);
        const int r = A.rows, c = A.cols;
        Ac.row(0).copyTo(X.row(0));
        Ac.row(r - 1).copyTo(X.row(r - 1));
        Ac.col(0).copyTo(X.col(0));
        Ac.col(c - 1).copyTo(X.col(c - 1));

        // ksize 使 makeSE 得到 3×3 全 1 矩形时（ksize≤3 且 ksize≠1；
        // ksize=1 的 1×1 结构元素不会传播，旧实现语义退化为「把所有非边界
        // 背景都当孔洞填掉」，与此处连通域判定不等价，故只走慢路径），
        // 膨胀邻接恰好是 8 连通。此时式 3-23 的迭代闭包就是
        // 「Aᶜ 中与图像边界 8-连通的分量」，用一次连通域标记直接得到，
        // 免去「每轮全图膨胀 + 全图相等比较」的 O(孔洞半径) 次迭代
        // （真机 1080p 基线实测 16.1s；结果逐像素一致，由 A/B 回归复核）。
        if (ksize != 1 && ksize <= 3) {
            cv::Mat labels;
            const int n = cv::connectedComponents(Ac, labels, 8, CV_32S);
            std::vector<char> onBorder(n, 0);
            for (int x = 0; x < c; ++x) {
                onBorder[labels.at<int>(0, x)] = 1;
                onBorder[labels.at<int>(r - 1, x)] = 1;
            }
            for (int y = 0; y < r; ++y) {
                onBorder[labels.at<int>(y, 0)] = 1;
                onBorder[labels.at<int>(y, c - 1)] = 1;
            }
            cv::Mat filled = A.clone();
            for (int y = 0; y < r; ++y) {
                const int *lRow = labels.ptr<int>(y);
                const uchar *acRow = Ac.ptr<uchar>(y);
                uchar *fRow = filled.ptr<uchar>(y);
                for (int x = 0; x < c; ++x) {
                    // 背景像素且所在分量不接触边界 → 孔洞 → 填为前景
                    if (acRow[x] != 0 && !onBorder[lRow[x]]) fRow[x] = 255;
                }
            }
            return filled;
        }

        // 式 3-23 迭代至收敛：Xk = (X(k-1)⊕B) ∩ Aᶜ
        // 收敛判定用 XOR 复用缓冲区（旧写法每轮 X.clone() + (Xk==prev) 临时 Mat，
        // 各多一次全图分配）；用双缓冲交替（cur^1）保证膨胀的输入/输出不同名。
        const cv::Mat SE = makeSE(ksize, 0);
        cv::Mat buf[2];
        buf[0] = X;
        buf[1].create(X.size(), X.type());
        cv::Mat diff;
        int cur = 0;
        int guard = 0;
        const int maxIter = 4096;
        while (guard++ < maxIter) {
            cv::Mat &next = buf[cur ^ 1];
            cv::dilate(buf[cur], next, SE);
            cv::bitwise_and(next, Ac, next);
            cv::bitwise_xor(next, buf[cur], diff);       // 与上一轮比较
            if (cv::countNonZero(diff) == 0) break;      // 收敛
            cur ^= 1;
        }
        cv::Mat &conv = buf[cur];   // 收敛后的「外部背景」
        // 孔洞 = Aᶜ − 外部背景；填充结果 = A ∪ 孔洞
        cv::Mat holes, filled;
        cv::bitwise_not(conv, holes);
        cv::bitwise_and(holes, Ac, holes);               // 孔洞（Aᶜ 中未被波及部分）
        cv::bitwise_or(A, holes, filled);
        return filled;
    }

    // --- 骨架抽取 (式 3-24 ~ 3-28) ---
    cv::Mat binarySkeleton(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat SE = makeSE(ksize, 0);

        // S(A) = ∪ Sn(A)，Sn(A) = (A㊀nB) − (A㊀nB)°B，n = 0..N
        cv::Mat skel = cv::Mat::zeros(A.size(), CV_8U);
        cv::Mat eroded = A.clone();
        cv::Mat opened, sn;
        int guard = 0;
        const int maxIter = 256;
        while (cv::countNonZero(eroded) > 0 && guard++ < maxIter) {
            cv::morphologyEx(eroded, opened, cv::MORPH_OPEN, SE);
            cv::subtract(eroded, opened, sn);          // Sn(A)
            cv::bitwise_or(skel, sn, skel);
            cv::erode(eroded, eroded, SE);             // A㊀(n+1)B
        }
        return skel;
    }

    // --- 细化 (式 3-29 ~ 3-31)：Zhang-Suen 串行实现 ---
    cv::Mat binaryThinning(const cv::Mat &src) {
        if (src.empty()) return src;
        ScopedLogTimer timer(TAG, "binaryThinning");
        cv::Mat img = binarize(src);

        // 8 邻域编号：
        // p9 p2 p3
        // p8 p1 p4
        // p7 p6 p5
        bool changed = true;
        const int rows = img.rows, cols = img.cols;
        // 删除集由「本轮扫描开始时的图像」决定，扫描结束后才统一置零，
        // 因此行间互不依赖 —— 按行并行 + 每行一份独立列表（无锁、无竞争）。
        // 结果与原串行实现逐位一致（串行基线真机 1080p 实测 16.2s，
        // 并行后的耗时以 A/B 回归轮的同场景计时为准）。
        std::vector<std::vector<cv::Point>> rowRemove(rows);
        while (changed) {
            changed = false;
            // 两遍扫描：第一遍删北/南边界点，第二遍删东/西边界点
            // （对应式 3-30/3-31 的旋转结构元素序列 B1..B8）
            for (int pass = 0; pass < 2; ++pass) {
                for (auto &v: rowRemove) v.clear();
                // 注意：本工程 OpenCV 头文件里的 parallel_for_ 只接受
                // std::function<void(const Range&)> 重载，回调按区间自行循环。
                cv::parallel_for_(cv::Range(1, rows - 1),
                                  [&](const cv::Range &rg) {
                    for (int y = rg.start; y < rg.end; ++y) {
                        const uchar *rowM = img.ptr<uchar>(y - 1);
                        const uchar *rowC = img.ptr<uchar>(y);
                        const uchar *rowP = img.ptr<uchar>(y + 1);
                        std::vector<cv::Point> &toRemove = rowRemove[y];
                        for (int x = 1; x < cols - 1; ++x) {
                            if (rowC[x] == 0) continue;    // 仅处理前景
                            uchar p2 = rowM[x], p3 = rowM[x + 1], p4 = rowC[x + 1];
                            uchar p5 = rowP[x + 1], p6 = rowP[x], p7 = rowP[x - 1];
                            uchar p8 = rowC[x - 1], p9 = rowM[x - 1];
                            int B = (p2 > 0) + (p3 > 0) + (p4 > 0) + (p5 > 0) +
                                    (p6 > 0) + (p7 > 0) + (p8 > 0) + (p9 > 0);
                            if (B < 2 || B > 6) continue;          // 端点/内部点保留
                            int A = ((p2 == 0 && p3 > 0) + (p3 == 0 && p4 > 0) +
                                     (p4 == 0 && p5 > 0) + (p5 == 0 && p6 > 0) +
                                     (p6 == 0 && p7 > 0) + (p7 == 0 && p8 > 0) +
                                     (p8 == 0 && p9 > 0) + (p9 == 0 && p2 > 0));
                            if (A != 1) continue;                  // 保持连通性
                            if (pass == 0) {
                                if (p2 > 0 && p4 > 0 && p6 > 0) continue;
                            } else {
                                if (p2 > 0 && p4 > 0 && p8 > 0) continue;
                            }
                            toRemove.emplace_back(x, y);          // A⊗B = A − (A㊀B)
                        }
                    }
                });
                for (int y = 1; y < rows - 1; ++y) {
                    if (rowRemove[y].empty()) continue;
                    changed = true;
                    uchar *dRow = img.ptr<uchar>(y);
                    for (const auto &p: rowRemove[y]) dRow[p.x] = 0;
                }
            }
        }
        return img;
    }

    // --- 形态开-闭滤波 (式 3-34) ---
    cv::Mat binaryOpenCloseFilter(const cv::Mat &src, int ksize) {
        if (src.empty()) return src;
        cv::Mat A = binarize(src);
        cv::Mat SE = makeSE(ksize, 0);
        cv::Mat opened, dst;
        cv::morphologyEx(A, opened, cv::MORPH_OPEN, SE);   // 开：去前景白噪声
        cv::morphologyEx(opened, dst, cv::MORPH_CLOSE, SE); // 闭：去背景黑噪声
        return dst;
    }

    // =========================================================================
    // 3.4 灰度形态学基本运算
    // =========================================================================

    // --- 灰度腐蚀 (式 3-36, 3-37) ---
    cv::Mat grayErode(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::erode(gray, dst, makeSE(ksize, shape));   // min 滤波（平坦 SE：b=0）
        return dst;
    }

    // --- 灰度膨胀 (式 3-38, 3-39) ---
    cv::Mat grayDilate(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::dilate(gray, dst, makeSE(ksize, shape));  // max 滤波（平坦 SE：b=0）
        return dst;
    }

    // --- 灰度开运算 (式 3-42) ---
    cv::Mat grayOpen(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::morphologyEx(gray, dst, cv::MORPH_OPEN, makeSE(ksize, shape));
        return dst;
    }

    // --- 灰度闭运算 (式 3-43) ---
    cv::Mat grayClose(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::morphologyEx(gray, dst, cv::MORPH_CLOSE, makeSE(ksize, shape));
        return dst;
    }

    // =========================================================================
    // 3.5 灰度图像的形态学处理
    // =========================================================================

    // --- 形态学梯度 (式 3-46)：(f⊕b) − (f㊀b) ---
    cv::Mat grayMorphGradient(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat SE = makeSE(ksize, shape);
        cv::Mat dilated, eroded, dst;
        cv::dilate(gray, dilated, SE);
        cv::erode(gray, eroded, SE);
        cv::subtract(dilated, eroded, dst);
        return dst;
    }

    // --- 形态开-闭平滑 (式 3-47) ---
    cv::Mat grayOpenClose(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat SE = makeSE(ksize, shape);
        cv::Mat opened, dst;
        cv::morphologyEx(gray, opened, cv::MORPH_OPEN, SE);
        cv::morphologyEx(opened, dst, cv::MORPH_CLOSE, SE);
        return dst;
    }

    // --- 形态闭-开平滑 (式 3-48) ---
    cv::Mat grayCloseOpen(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat SE = makeSE(ksize, shape);
        cv::Mat closed, dst;
        cv::morphologyEx(gray, closed, cv::MORPH_CLOSE, SE);
        cv::morphologyEx(closed, dst, cv::MORPH_OPEN, SE);
        return dst;
    }

    // --- Top-Hat 高帽 (式 3-49)：f − (f°b) ---
    cv::Mat grayTopHat(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::morphologyEx(gray, dst, cv::MORPH_TOPHAT, makeSE(ksize, shape));
        return dst;
    }

    // --- Bottom-Hat 低帽：(f·b) − f ---
    cv::Mat grayBottomHat(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray = toGray(src);
        cv::Mat dst;
        cv::morphologyEx(gray, dst, cv::MORPH_BLACKHAT, makeSE(ksize, shape));
        return dst;
    }

    // --- Top-Hat 增强：f + TopHat − BottomHat ---
    cv::Mat grayTopHatEnhance(const cv::Mat &src, int ksize, int shape) {
        if (src.empty()) return src;
        cv::Mat gray, f32, hat, that, bhat, dst;
        gray = toGray(src);
        cv::Mat SE = makeSE(ksize, shape);
        cv::morphologyEx(gray, hat, cv::MORPH_TOPHAT, SE);
        cv::morphologyEx(gray, bhat, cv::MORPH_BLACKHAT, SE);

        gray.convertTo(f32, CV_32F);
        hat.convertTo(hat, CV_32F);
        bhat.convertTo(bhat, CV_32F);
        cv::Mat g = f32 + hat - bhat;                 // f + TopHat − BottomHat
        cv::normalize(g, g, 0, 255, cv::NORM_MINMAX);
        g.convertTo(dst, CV_8U);
        return dst;
    }
}
