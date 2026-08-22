#ifndef OPENCVDEAL_IMAGEHOMOMORPHIC_H
#define OPENCVDEAL_IMAGEHOMOMORPHIC_H

#include <opencv2/core.hpp>

/**
 * 图像的同态滤波
 *
 * 基于《数字图像与视频处理》第二章 2.5 节"图像的同态滤波"实现：
 * 图像成像模型 f(x,y) = i(x,y)·r(x,y)（照度×反射，式 2-81），
 * 照度分量变化缓慢（低频）、反射分量变化剧烈（高频，含边缘细节）。
 * 在对数域用频域滤波器压缩照度动态范围、增强反射细节
 * （传递函数剖面图 2-42：低频增益 HL＜1，高频增益 HH＞1），
 * 处理流程框图 2-43，参考 MATLAB 实例 例 2-7。
 */
namespace ImageHomomorphic {

    /**
     * 同态滤波全流程 (式 2-81 ~ 式 2-87)
     * 式 2-81：f(x,y) = i(x,y)·r(x,y)（图像 = 照度×反射）
     * 式 2-82：z(x,y) = ln f(x,y) = ln i(x,y) + ln r(x,y)（取对数，乘性→加性）
     * 式 2-83：Z(u,v) = F{z(x,y)}（DFT）
     * 式 2-84：Z = I(u,v) + R(u,v)（照度谱与反射谱分离）
     * 式 2-85：S(u,v) = H(u,v)·Z(u,v)（频域滤波）
     * 式 2-86：s(x,y) = F⁻¹[S(u,v)]（IDFT）
     * 式 2-87：g(x,y) = e^s(x,y)（取指数还原）
     * 传递函数（图 2-42 剖面 + 例 2-7）：
     *   H(u,v) = (HH − HL)·(1 − e^(−c·D²(u,v)/D0²)) + HL
     *   其中 HL＜1（压缩低频照度），HH＞1（增强高频反射）。
     * 本实现对彩色图逐通道执行上述流程，输出彩色结果
     * （三通道统一全局归一化以保持色彩关系）。
     *
     * @param src 输入图像（RGBA/RGB/灰度均可）
     * @param d0  截止频率 D0（例 2-7 取 80）
     * @param c   锐利系数 c（控制剖面过渡陡峭程度，例 2-7 取 1.5）
     * @param hl  低频增益 HL（＜1，压缩照度）
     * @param hh  高频增益 HH（＞1，增强反射）
     */
    cv::Mat homomorphicFilter(const cv::Mat &src, double d0, double c, double hl, double hh);

    /**
     * 对数域可视化 (式 2-82)
     * z(x,y) = ln f(x,y)：取对数后乘性成像模型变为加性模型，
     * 照度与反射分量可在频谱上分离。本函数将 ln f 的取值
     * 线性映射到 [0,255] 以灰度图显示（变亮代表对数域值大）。
     */
    cv::Mat logDomainImage(const cv::Mat &src);

    /**
     * 照度分量 i(x,y) 估计（式 2-81 的低频分量）
     * 照度分量取决于光源，随空间缓慢变化（低频）。
     * 工程上用大 σ 高斯低通滤波（σ=60）近似估计照度场，
     * 显示光照的明暗分布（阴影、渐晕等缓慢变化结构）。
     */
    cv::Mat illuminationComponent(const cv::Mat &src);

    /**
     * 反射分量 r(x,y) 估计（式 2-81 的高频分量）
     * 反射分量取决于物体表面性质，变化剧烈（边缘/细节，高频）。
     * 工程近似：r = f / (i + ε)，即原图除以照度估计，
     * 归一化后显示物体的细节与边缘结构。
     */
    cv::Mat reflectanceComponent(const cv::Mat &src);
}

#endif //OPENCVDEAL_IMAGEHOMOMORPHIC_H
