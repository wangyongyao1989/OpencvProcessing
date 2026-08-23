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
        // 点击「图像增强」卡片进入二级菜单（灰度变换 / 平滑去噪 / 锐化 / 同态 / Retinex / 彩色）
        binding.btnImageEnhance.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE)
        }

        // 点击「形态学图像处理」卡片进入二级菜单（二值/灰度形态学）
        binding.btnMorphology.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.MORPHOLOGY)
        }

        // 点击「图像分割」卡片进入二级菜单（阈值/边缘/区域/主动轮廓）
        binding.btnSegmentation.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.SEGMENTATION)
        }

        // 点击「数字水印技术」卡片进入二级菜单（嵌入提取/攻击对策）
        binding.btnWatermark.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.WATERMARK)
        }
    }
}
