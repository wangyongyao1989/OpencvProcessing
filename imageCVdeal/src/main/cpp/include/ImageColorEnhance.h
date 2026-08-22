#ifndef OPENCVDEAL_IMAGECOLORENHANCE_H
#define OPENCVDEAL_IMAGECOLORENHANCE_H

#include <opencv2/core.hpp>

/**
 * 彩色增强
 *
 * 基于《数字图像与视频处理》第二章 2.7 节"彩色增强"实现：
 * - 2.7.1 伪彩色增强：把灰度图像映射为彩色图像
 *   · 灰度分层法（图 2-47 两层切割 / 图 2-48 多平面切割）
 *   · 灰度级彩色变换（图 2-49 红/绿/蓝三变换器）
 *   · 频率域滤波法（图 2-50 低通/带通/高通三分量）
 * - 2.7.2 假彩色增强：彩色到彩色的映射
 *   · 自然图像线性映射（式 2-97）
 *   · 多光谱图像变换（式 2-98）
 */
namespace ImageColorEnhance {

    /**
     * 灰度分层法——两层切割 (图 2-47)
     * 在灰度级 l1 处设置平行于 xy 平面的切割平面，将图像切成两个区域：
     * 低于 l1 的像素赋一种颜色（蓝色），高于 l1 的赋另一种颜色（红色），
     * 得到只有两种颜色的伪彩色图像。
     *
     * @param l1 切割灰度级（如 128）
     */
    cv::Mat graySlice2(const cv::Mat &src, int l1);

    /**
     * 灰度分层法——多平面切割 (图 2-48)
     * 用 M 个切割平面把灰度范围切成 M+1 个区域 S1..S(M+1)，
     * 人为给每个区域分配一种颜色（此处按色相均布生成 M+1 种
     * 光谱色），得到具有 M+1 种颜色的伪彩色图像。
     * 优点：简单易行、可扩展用途（如统计某灰度级面积）；
     * 缺点：伪彩色生硬不调和、颜色数目不多。
     *
     * @param m 切割平面数（输出 M+1 种颜色）
     */
    cv::Mat graySliceMulti(const cv::Mat &src, int m);

    /**
     * 灰度级彩色变换 (图 2-49)
     * 根据三基色原理，将灰度 f(x,y) 送入红/绿/蓝三个具有不同变换
     * 特性的变换器（图 2-49b 典型特性），得到 IR/IG/IB 三个基色分量
     * 后合成彩色。受调制的是像素灰度值而非位置，可将灰度图变换为
     * 多种颜色渐变的连续彩色图像。
     * 本实现采用相位错开 1/3 周期的三角波变换特性：
     *   IR = tri(f/255)，IG = tri(f/255 + 1/3)，IB = tri(f/255 + 2/3)
     * 其中 tri(x) = 1 − |2·frac(x) − 1| 为三角波。
     */
    cv::Mat grayLevelColorTransform(const cv::Mat &src);

    /**
     * 频率域滤波法伪彩色 (图 2-50)
     * 灰度图 → DFT → 用低通/带通/高通三个滤波器分离频谱 →
     * 分别 IDFT → 附加处理（直方图均衡化）→ 作为 R/G/B 三基色显示。
     * 伪彩色与灰度级无关而与空间频率成分有关：
     * 高频（边缘）→ 红色通道，中频 → 绿色，低频（背景）→ 蓝色。
     * 滤波器采用高斯型：低通 D0=30、高通 D0=60、带通为两者之差。
     */
    cv::Mat frequencyPseudoColor(const cv::Mat &src);

    /**
     * 假彩色增强——线性映射 (式 2-97)
     * 式 2-97：[gR gG gB]ᵀ = M·[fR fG fB]ᵀ，M 为 3×3 线性映射矩阵。
     * 本实现取通道轮换矩阵（红色细节更显眼）：
     *   gR = fB，gG = fR，gB = fG
     * 目的：变换后比自然本色更引人注目（假彩色增强目的 1）。
     */
    cv::Mat falseColorLinear(const cv::Mat &src);

    /**
     * 假彩色增强——细节赋予绿色 (式 2-97)
     * 人眼对绿色特别灵敏，把细节丰富的信息赋予绿色更易分辨
     * （假彩色增强目的 2）。取线性映射：
     *   gG = f（灰度细节作为绿色通道），gR = gB = 低通模糊背景
     */
    cv::Mat falseColorGreenSensitive(const cv::Mat &src);

    /**
     * 假彩色增强——细节赋予蓝色 (式 2-97)
     * 人眼对蓝色变化的对比灵敏度较高，把细节较丰富的目标赋予
     * 深浅不一的蓝色可改善细节可检测性（目的 2/3）。取线性映射：
     *   gB = f（灰度细节作为蓝色通道），gR = gG = 低通模糊背景
     */
    cv::Mat falseColorBlueDetail(const cv::Mat &src);

    /**
     * 假彩色增强——多光谱合成 (式 2-98)
     * 式 2-98：gR = TR[f1..fn]，gG = TG[f1..fn]，gB = TB[f1..fn]，
     * f1..fn 为 n 个波段图像。本实现把 RGB 三通道当作三个波段，
     * 变换函数取波段差分（光谱差异突出）：
     *   gR = |f1 − f2|，gG = |f2 − f3|，gB = |f3 − f1|
     * 多波段综合可获得更多信息，便于区分某些特征（目的 3）。
     */
    cv::Mat falseColorMultiSpectral(const cv::Mat &src);
}

#endif //OPENCVDEAL_IMAGECOLORENHANCE_H
