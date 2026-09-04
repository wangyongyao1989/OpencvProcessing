package com.wangyao.opencvprocessing.fragment.image

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentSegmentationLayoutBinding

/**
 * 二级菜单：图像分割（《数字图像与视频处理》第 4 章）。
 * 提供四个子功能入口：
 * - 基于灰度阈值化的图像分割（4.2 节）→ SegOpFragment(SEG_THRESHOLD)
 * - 基于边缘检测的图像分割（4.3 节）→ SegOpFragment(SEG_EDGE)
 * - 基于区域的图像分割（4.4 节）→ SegOpFragment(SEG_REGION)
 * - 基于主动轮廓模型的图像分割（4.5 节）→ SegOpFragment(SEG_CONTOUR)
 */
class SegmentationFragment : BaseFragment() {

    private lateinit var binding: FragmentSegmentationLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSegmentationLayoutBinding.inflate(inflater, container, false)
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

        binding.btnSegThreshold.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD)
        }

        binding.btnSegEdge.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.SEG_EDGE)
        }

        binding.btnSegRegion.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.SEG_REGION)
        }

        binding.btnSegContour.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.SEG_CONTOUR)
        }
    }
}
