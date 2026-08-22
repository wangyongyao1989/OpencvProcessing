#include "include/ImageMorphology.h"
#include <opencv2/imgproc.hpp>
#include <vector>

namespace ImageMorphology {

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
        cv::Mat A = binarize(src);
        cv::Mat SE = makeSE(ksize, 0);
        cv::Mat Ac;
        cv::bitwise_not(A, Ac);

        // X0：Aᶜ 中位于图像边界的背景种子点
        cv::Mat X = cv::Mat::zeros(A.size(), CV_8U);
        const int r = A.rows, c = A.cols;
        Ac.row(0).copyTo(X.row(0));
        Ac.row(r - 1).copyTo(X.row(r - 1));
        Ac.col(0).copyTo(X.col(0));
        Ac.col(c - 1).copyTo(X.col(c - 1));

        // 式 3-23 迭代至收敛：Xk = (X(k-1)⊕B) ∩ Aᶜ
        cv::Mat prev, Xk, kernel;
        int guard = 0;
        const int maxIter = 4096;
        while (guard++ < maxIter) {
            prev = X.clone();
            cv::dilate(X, Xk, SE);
            cv::bitwise_and(Xk, Ac, Xk);
            if (cv::countNonZero(Xk == prev) == Xk.total()) break;   // 收敛
            X = Xk;
        }
        // 收敛后 X 为外部背景；孔洞 = Aᶜ − X；填充结果 = A ∪ 孔洞
        cv::Mat holes, filled;
        cv::bitwise_not(X, holes);
        cv::bitwise_and(holes, Ac, holes);             // 孔洞（Aᶜ 中未被波及部分）
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
        cv::Mat img = binarize(src);

        // 8 邻域编号：
        // p9 p2 p3
        // p8 p1 p4
        // p7 p6 p5
        bool changed = true;
        while (changed) {
            changed = false;
            // 两遍扫描：第一遍删北/南边界点，第二遍删东/西边界点
            // （对应式 3-30/3-31 的旋转结构元素序列 B1..B8）
            for (int pass = 0; pass < 2; ++pass) {
                std::vector<cv::Point> toRemove;
                for (int y = 1; y < img.rows - 1; ++y) {
                    const uchar *rowM = img.ptr<uchar>(y - 1);
                    const uchar *rowC = img.ptr<uchar>(y);
                    const uchar *rowP = img.ptr<uchar>(y + 1);
                    for (int x = 1; x < img.cols - 1; ++x) {
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
                if (!toRemove.empty()) {
                    changed = true;
                    for (const auto &p: toRemove) img.at<uchar>(p.y, p.x) = 0;
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
