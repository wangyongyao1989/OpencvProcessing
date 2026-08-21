package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentMainLayoutBinding

/**
 * 主入口 Fragment：展示功能图标按钮，点击后通过 FFViewModel 路由。
 */
class MainFragment : BaseFragment() {

    private lateinit var binding: FragmentMainLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMainLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        // 点击「图像灰度变换」卡片跳转到 ImageGrayTransformFragment
        binding.btnGrayTransform.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_GRAY_TRANSFORM)
        }
    }
}
