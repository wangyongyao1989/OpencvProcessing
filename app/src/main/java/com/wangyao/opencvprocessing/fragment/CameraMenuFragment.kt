package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.databinding.FragmentCrMenuLayoutBinding

/**
 * 二级菜单：相机识别。
 * 两个子功能入口：
 * - 相机识别·实时人脸检测跟踪（多级联融合人脸检测）→ CameraRecognitionFragment
 * - 相机识别·框选实物实时检测跟踪（手势框选 + 检索式特征实时跟踪）→ ObjectTrackFragment
 */
class CameraMenuFragment : BaseFragment() {

    private lateinit var binding: FragmentCrMenuLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCrMenuLayoutBinding.inflate(inflater, container, false)
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

        binding.btnCrFace.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.CAMERA_RECOGNITION)
        }

        binding.btnCrObject.setOnClickListener {
            ffViewModel.switchFragment
                .postValue(FFViewModel.FRAGMENT_STATUS.CAMERA_OBJECT_TRACK)
        }
    }
}
