package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentQualityEvalLayoutBinding

/**
 * 二级菜单：图片与视频质量评价（《数字图像与视频处理》第 5~7、9 章）。
 * 提供两个子功能入口：
 * - 图像质量的客观评价（9.2~9.4 节）→ ImageQualityFragment
 * - 视频质量的客观评价（9.5 节 + 第 6 章率失真）→ VideoQualityFragment
 */
class QualityEvalFragment : BaseFragment() {

    private lateinit var binding: FragmentQualityEvalLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentQualityEvalLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MAIN)
        }

        binding.btnQeImage.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.QE_IMAGE)
        }

        binding.btnQeVideo.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.QE_VIDEO)
        }
    }
}
