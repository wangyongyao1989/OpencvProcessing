#ifndef OPENCVDEAL_IMAGERETINEX_H
#define OPENCVDEAL_IMAGERETINEX_H

#include <opencv2/core.hpp>
#include <vector>

/**
 * 基于 Retinex 理论的图像增强
 *
 * 基于《数字图像与视频处理》第二章 2.6 节实现：
 * Land 的 Retinex 理论（式 2-88）：图像 = 光照分量 × 反射分量，
 * 通过对数域相减估计反射分量，消除光照不均、实现"动态范围压缩
 * 与颜色恒常"的增强。包括 SSR（单尺度）、MSR（多尺度）、
 * MSRCR（带颜色恢复的多尺度）。
 */
namespace ImageRetinex {

    /**
     * 光照分量 L(x,y) 估计 (式 2-88, 式 2-92)
     * 式 2-88：f(x,y) = i(x,y)·r(x,y)（Retinex 成像模型）
     * 式 2-92：L(x,y) = I(x,y) * G(x,y)（高斯环绕卷积估计光照）
     * 用大 σ 高斯低通（降采样优化）估计光滑的光照场，
     * 直接显示（彩色），可观察阴影、亮度渐变等缓慢变化。
     *
     * @param sigma 高斯环绕尺度 σ（σ 越大光照估计越平滑）
     */
    cv::Mat illuminationEstimate(const cv::Mat &src, double sigma);

    /**
     * 反射分量 r 可视化（灰度）(式 2-89, 式 2-90, 式 2-91)
     * 式 2-89：ln f = ln i + ln r（对数域加性分解）
     * 式 2-90：l(x,y) = ln i(x,y)，r(x,y) = ln f − l（分解表示）
     * 式 2-91：ri(x,y) = ln Ii(x,y) − ln[Ii(x,y)*G(x,y)]（SSR 反射估计）
     * 将反射分量归一化后以灰度图显示：亮处代表反射强（细节/边缘）。
     */
    cv::Mat reflectanceGray(const cv::Mat &src, double sigma);

    /**
     * SSR 单尺度 Retinex (式 2-91, 式 2-92)
     * 式 2-91：ri(x,y) = ln Ii(x,y) − ln[Ii(x,y)*G(x,y)]，i = R,G,B 三通道独立
     * 式 2-92：G 为高斯环绕函数（中心/环绕比）
     * σ 的取值产生两种效果（图 2-46）：
     * - 小 σ：动态范围压缩强、细节突出，但颜色易失真；
     * - 大 σ：颜色保真度高、整体自然，但动态范围压缩不足。
     * 经典折中 σ = 80。输出经全局归一化的彩色增强结果。
     */
    cv::Mat ssr(const cv::Mat &src, double sigma);

    /**
     * MSR 多尺度 Retinex (式 2-93, 式 2-94)
     * 式 2-93：R_MSR_i = Σk ωk·{ln Ii − ln[Ii*Gk]}（多尺度加权融合）
     * 式 2-94：ωk = 1/N（各尺度等权）
     * 同时兼顾小尺度（细节）与大尺度（颜色保真）的优点。
     * 常用 σ 组合：{30, 80, 200} 或五尺度 {15, 40, 80, 150, 250}。
     */
    cv::Mat msr(const cv::Mat &src, const std::vector<double> &sigmas);

    /**
     * MSRCR 带颜色恢复的多尺度 Retinex (式 2-95, 式 2-96)
     * 式 2-95：Ci(x,y) = f[Ii(x,y)/Σj Ij(x,y)]（颜色恢复系数，
     *          f 为变换函数，通常取线性或对数函数）
     * 式 2-96：R_MSRCR_i = Σk Ci·ωk·{ln Ii − ln[Ii*Gk]}
     * MSR 各通道独立增强易导致颜色失真，颜色恢复因子 Ci 按
     * 通道相对亮度调节各通道的增强强度，改善整体色彩保真度。
     * 本实现 f 取线性函数：Ci = Ii / Σ Ii。
     */
    cv::Mat msrcr(const cv::Mat &src, const std::vector<double> &sigmas);
}

#endif //OPENCVDEAL_IMAGERETINEX_H
