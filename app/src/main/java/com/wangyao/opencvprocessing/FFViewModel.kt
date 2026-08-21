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
        MAIN,
        IMAGE_GRAY_TRANSFORM,
    }

    val switchFragment: MutableLiveData<FRAGMENT_STATUS> = MutableLiveData()
}
