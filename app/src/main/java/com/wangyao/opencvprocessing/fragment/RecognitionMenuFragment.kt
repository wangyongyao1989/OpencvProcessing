package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentIrMenuLayoutBinding

/**
 * 二级菜单：图像识别（《数字图像与视频处理》第 11 章）。
 * 两个子功能入口：
 * - 图像的识别处理（模板匹配 + Hu 矩形状分类）→ ImageRecognitionFragment
 * - 视频的图像识别处理（模板跟踪 + 运动检测）→ VideoRecognitionFragment
 */
class RecognitionMenuFragment : BaseFragment() {

    private lateinit var binding: FragmentIrMenuLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentIrMenuLayoutBinding.inflate(inflater, container, false)
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

        binding.btnIrImage.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IR_IMAGE)
        }

        binding.btnIrVideo.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IR_VIDEO)
        }
    }
}
