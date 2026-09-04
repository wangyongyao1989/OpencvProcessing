package com.wangyao.opencvprocessing.fragment.image

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentMorphologyLayoutBinding

/**
 * 二级菜单：形态学图像处理（《数字图像与视频处理》第 3 章）。
 * 提供四个子功能入口：
 * - 二值形态学基本运算（3.2 节）→ MorphOpFragment(MORPH_BIN_BASIC)
 * - 二值图像的形态学处理（3.3 节）→ MorphOpFragment(MORPH_BIN_PROCESS)
 * - 灰度形态学基本运算（3.4 节）→ MorphOpFragment(MORPH_GRAY_BASIC)
 * - 灰度图像的形态学处理（3.5 节）→ MorphOpFragment(MORPH_GRAY_PROCESS)
 */
class MorphologyFragment : BaseFragment() {

    private lateinit var binding: FragmentMorphologyLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMorphologyLayoutBinding.inflate(inflater, container, false)
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

        binding.btnBinBasic.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC)
        }

        binding.btnBinProcess.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS)
        }

        binding.btnGrayBasic.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC)
        }

        binding.btnGrayProcess.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_PROCESS)
        }
    }
}
