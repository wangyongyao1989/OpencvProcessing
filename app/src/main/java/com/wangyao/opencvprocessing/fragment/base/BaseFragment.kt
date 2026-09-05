package com.wangyao.opencvprocessing.fragment.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * Fragment 基类（参考 WyFFmpeg BaseFragment 四阶段初始化流程）。
 *
 * 子类按顺序覆写：
 * 1. [getLayoutBinding] -> inflate ViewBinding
 * 2. [initView] -> findViewById / binding 赋值
 * 3. [initData] -> 初始数据（如加载 assets 图片）
 * 4. [initObserver] -> ViewModel LiveData 订阅
 * 5. [initListener] -> 点击 / 勾选监听
 */
abstract class BaseFragment : Fragment() {

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = getLayoutBinding(inflater, container, savedInstanceState)
        initView()
        initData()
        initObserver()
        initListener()
        return root
    }

    /** 返回此 Fragment 的根 View（来自 ViewBinding）。 */
    abstract fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View

    open fun initView() = Unit

    open fun initData() = Unit

    open fun initObserver() = Unit

    open fun initListener() = Unit
}
