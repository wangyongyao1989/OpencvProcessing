package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentWatermarkLayoutBinding

/**
 * 二级菜单：数字水印技术（《数字图像与视频处理》第 5~8 章）。
 * 提供两个子功能入口：
 * - 数字水印的嵌入/提取（8.2/8.3/8.4 节）→ WatermarkEmbedFragment
 * - 水印的攻击方法和对策（8.5 节）→ WatermarkAttackFragment
 */
class WatermarkFragment : BaseFragment() {

    private lateinit var binding: FragmentWatermarkLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWatermarkLayoutBinding.inflate(inflater, container, false)
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

        binding.btnWmEmbed.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.WM_EMBED_EXTRACT)
        }

        binding.btnWmAttack.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.WM_ATTACK)
        }
    }
}
