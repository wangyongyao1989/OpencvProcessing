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
    }

    val switchFragment: MutableLiveData<FRAGMENT_STATUS> = MutableLiveData()
}
