package com.wangyao.opencvprocessing

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * 全局 Fragment 切换路由视图模型。
 * 参考 WyFFmpeg 的解耦方式：任何 Fragment 通过 FFViewModel 发送枚举状态，
 * 由 MainActivity 统一负责实际的 Fragment 切换（hide/show/add）。
 */
class FFViewModel : ViewModel() {

    enum class FRAGMENT_STATUS {
        MAIN,                 // 一级：主入口
        IMAGE_ENHANCE,        // 二级：图像增强（灰度/平滑/锐化/同态/Retinex/彩色 菜单）
        IMAGE_GRAY_TRANSFORM, // 三级：图像的灰度变换
        IMAGE_SMOOTH_DENOISE, // 三级：图像平滑与去噪
        IMAGE_SHARPEN,        // 三级：图像锐化
        IMAGE_HOMOMORPHIC,    // 三级：图像的同态滤波
        IMAGE_RETINEX,        // 三级：基于 Retinex 理论的图像增强
        IMAGE_COLOR_ENHANCE,  // 三级：彩色增强
        MORPHOLOGY,           // 二级：形态学图像处理（二值/灰度形态学菜单）
        MORPH_BIN_BASIC,      // 三级：二值形态学基本运算
        MORPH_BIN_PROCESS,    // 三级：二值图像的形态学处理
        MORPH_GRAY_BASIC,     // 三级：灰度形态学基本运算
        MORPH_GRAY_PROCESS,   // 三级：灰度图像的形态学处理
        SEGMENTATION,         // 二级：图像分割（阈值/边缘/区域/主动轮廓菜单）
        SEG_THRESHOLD,        // 三级：基于灰度阈值化的图像分割
        SEG_EDGE,             // 三级：基于边缘检测的图像分割
        SEG_REGION,           // 三级：基于区域的图像分割
        SEG_CONTOUR,          // 三级：基于主动轮廓模型的图像分割
        WATERMARK,            // 二级：数字水印技术（嵌入提取/攻击对策菜单）
        WM_EMBED_EXTRACT,     // 三级：数字水印的嵌入/提取
        WM_ATTACK,            // 三级：水印的攻击方法和对策
    }

    val switchFragment: MutableLiveData<FRAGMENT_STATUS> = MutableLiveData()
}
