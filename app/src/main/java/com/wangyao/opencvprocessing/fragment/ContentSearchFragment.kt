package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentContentSearchLayoutBinding

/**
 * 二级菜单：基于内容的图像和视频检索（《数字图像与视频处理》第 10 章）。
 * 提供两个子功能入口：
 * - 基于内容的图像检索（10.2~10.4 节）→ ImageSearchFragment
 * - 基于内容的视频检索（10.5 节：关键帧 + 检索）→ VideoSearchFragment
 */
class ContentSearchFragment : BaseFragment() {

    private lateinit var binding: FragmentContentSearchLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentContentSearchLayoutBinding.inflate(inflater, container, false)
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

        binding.btnCsImage.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.CS_IMAGE)
        }

        binding.btnCsVideo.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.CS_VIDEO)
        }
    }
}
