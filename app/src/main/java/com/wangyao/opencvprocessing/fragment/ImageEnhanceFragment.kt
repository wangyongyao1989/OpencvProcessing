package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentImageEnhanceLayoutBinding

/**
 * 二级菜单：图像增强（《数字图像与视频处理》第 2 章）。
 * 提供三个子功能入口：
 * - 图像灰度变换（2.2 节）→ ImageGrayTransformFragment
 * - 图像平滑与去噪（2.3 节）→ ImageSmoothDenoiseFragment
 * - 图像锐化（2.4 节）→ ImageSharpenFragment
 */
class ImageEnhanceFragment : BaseFragment() {

    private lateinit var binding: FragmentImageEnhanceLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageEnhanceLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        // 返回主界面（一级）
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MAIN)
        }

        // 三级页：图像的灰度变换
        binding.btnGrayTransform.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_GRAY_TRANSFORM)
        }

        // 三级页：图像平滑与去噪
        binding.btnSmoothDenoise.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_SMOOTH_DENOISE)
        }

        // 三级页：图像锐化
        binding.btnSharpen.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_SHARPEN)
        }
    }
}
