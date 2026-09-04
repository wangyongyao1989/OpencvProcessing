package com.wangyao.opencvprocessing

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.fragment.ImageEnhanceFragment
import com.wangyao.opencvprocessing.fragment.ImageGrayTransformFragment
import com.wangyao.opencvprocessing.fragment.ImageHomomorphicFragment
import com.wangyao.opencvprocessing.fragment.ImageRetinexFragment
import com.wangyao.opencvprocessing.fragment.ImageColorEnhanceFragment
import com.wangyao.opencvprocessing.fragment.ImageSharpenFragment
import com.wangyao.opencvprocessing.fragment.ImageSmoothDenoiseFragment
import com.wangyao.opencvprocessing.fragment.MainFragment
import com.wangyao.opencvprocessing.fragment.MorphOpFragment
import com.wangyao.opencvprocessing.fragment.MorphologyFragment
import com.wangyao.opencvprocessing.fragment.SegOpFragment
import com.wangyao.opencvprocessing.fragment.SegmentationFragment
import com.wangyao.opencvprocessing.fragment.QualityEvalFragment
import com.wangyao.opencvprocessing.fragment.ImageQualityFragment
import com.wangyao.opencvprocessing.fragment.VideoQualityFragment
import com.wangyao.opencvprocessing.fragment.ContentSearchFragment
import com.wangyao.opencvprocessing.fragment.ImageSearchFragment
import com.wangyao.opencvprocessing.fragment.VideoSearchFragment
import com.wangyao.opencvprocessing.fragment.ImageRecognitionFragment
import com.wangyao.opencvprocessing.fragment.VideoRecognitionFragment
import com.wangyao.opencvprocessing.fragment.RecognitionMenuFragment
import com.wangyao.opencvprocessing.fragment.WatermarkFragment
import com.wangyao.opencvprocessing.fragment.WatermarkEmbedFragment
import com.wangyao.opencvprocessing.fragment.WatermarkAttackFragment
import com.wangyao.opencvprocessing.fragment.FaceVideoFragment
import com.wangyao.opencvprocessing.fragment.CameraRecognitionFragment
import com.wangyao.opencvprocessing.fragment.CameraMenuFragment
import com.wangyao.opencvprocessing.fragment.ObjectTrackFragment

/**
 * 应用唯一的 Activity，负责：
 * 1. 初始化 FFViewModel 并订阅 [FFViewModel.switchFragment]；
 * 2. 按枚举值在 `fragment_container` 内切换 Fragment（hide/show + tag 复用）；
 * 3. 统一拦截系统返回键：非主界面则回到 MAIN，主界面则 finish。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ffViewModel: FFViewModel
    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val supportActionBar = getSupportActionBar()
        if (supportActionBar != null) {
            supportActionBar.hide()
        }
        if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN)

        ffViewModel = ViewModelProvider(this)[FFViewModel::class.java]
        ffViewModel.switchFragment.observe(this) { status ->
            if (status != null) selectFragment(status)
        }

        if (savedInstanceState == null) {
            selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
        } else {
            // 恢复被系统重建后 currentFragment 的引用
            currentFragment = supportFragmentManager
                .findFragmentById(R.id.fragment_container)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 三级返回链：
                // 图像增强族三级页 → 二级「图像增强」 → 主界面 → 退出
                // 形态学三级页 → 二级「形态学图像处理」 → 主界面 → 退出
                // 图像分割三级页 → 二级「图像分割」 → 主界面 → 退出
                // 数字水印三级页 → 二级「数字水印技术」 → 主界面 → 退出
                when (currentFragment) {
                    is MainFragment -> finish()
                    is ImageEnhanceFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is MorphologyFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is MorphOpFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MORPHOLOGY)
                    is SegmentationFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is SegOpFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.SEGMENTATION)
                    is WatermarkFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is WatermarkEmbedFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.WATERMARK)
                    is WatermarkAttackFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.WATERMARK)
                    is QualityEvalFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is ImageQualityFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.QUALITY_EVAL)
                    is VideoQualityFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.QUALITY_EVAL)
                    is ContentSearchFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is ImageSearchFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.CONTENT_SEARCH)
                    is VideoSearchFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.CONTENT_SEARCH)
                    is RecognitionMenuFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is ImageRecognitionFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.IMAGE_RECOGNITION)
                    is VideoRecognitionFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.IMAGE_RECOGNITION)
                    is FaceVideoFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is CameraMenuFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.MAIN)
                    is CameraRecognitionFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.CAMERA_MENU)
                    is ObjectTrackFragment ->
                        selectFragment(FFViewModel.FRAGMENT_STATUS.CAMERA_MENU)
                    else -> selectFragment(FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE)
                }
            }
        })
    }

    private fun selectFragment(status: FFViewModel.FRAGMENT_STATUS) {
        val fm: FragmentManager = supportFragmentManager
        val tx: FragmentTransaction = fm.beginTransaction()
        val tag = status.name

        var target: Fragment? = fm.findFragmentByTag(tag)
        if (target == null) {
            target = when (status) {
                FFViewModel.FRAGMENT_STATUS.MAIN -> MainFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE -> ImageEnhanceFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_GRAY_TRANSFORM -> ImageGrayTransformFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_SMOOTH_DENOISE -> ImageSmoothDenoiseFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_SHARPEN -> ImageSharpenFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_HOMOMORPHIC -> ImageHomomorphicFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_RETINEX -> ImageRetinexFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_COLOR_ENHANCE -> ImageColorEnhanceFragment()
                FFViewModel.FRAGMENT_STATUS.MORPHOLOGY -> MorphologyFragment()
                FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC ->
                    MorphOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC)
                FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS ->
                    MorphOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS)
                FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC ->
                    MorphOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC)
                FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_PROCESS ->
                    MorphOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_PROCESS)
                FFViewModel.FRAGMENT_STATUS.SEGMENTATION -> SegmentationFragment()
                FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD ->
                    SegOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD)
                FFViewModel.FRAGMENT_STATUS.SEG_EDGE ->
                    SegOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.SEG_EDGE)
                FFViewModel.FRAGMENT_STATUS.SEG_REGION ->
                    SegOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.SEG_REGION)
                FFViewModel.FRAGMENT_STATUS.SEG_CONTOUR ->
                    SegOpFragment.instantiate(FFViewModel.FRAGMENT_STATUS.SEG_CONTOUR)
                FFViewModel.FRAGMENT_STATUS.WATERMARK -> WatermarkFragment()
                FFViewModel.FRAGMENT_STATUS.WM_EMBED_EXTRACT -> WatermarkEmbedFragment()
                FFViewModel.FRAGMENT_STATUS.WM_ATTACK -> WatermarkAttackFragment()
                FFViewModel.FRAGMENT_STATUS.QUALITY_EVAL -> QualityEvalFragment()
                FFViewModel.FRAGMENT_STATUS.QE_IMAGE -> ImageQualityFragment()
                FFViewModel.FRAGMENT_STATUS.QE_VIDEO -> VideoQualityFragment()
                FFViewModel.FRAGMENT_STATUS.CONTENT_SEARCH -> ContentSearchFragment()
                FFViewModel.FRAGMENT_STATUS.CS_IMAGE -> ImageSearchFragment()
                FFViewModel.FRAGMENT_STATUS.CS_VIDEO -> VideoSearchFragment()
                FFViewModel.FRAGMENT_STATUS.IMAGE_RECOGNITION -> RecognitionMenuFragment()
                FFViewModel.FRAGMENT_STATUS.IR_IMAGE -> ImageRecognitionFragment()
                FFViewModel.FRAGMENT_STATUS.IR_VIDEO -> VideoRecognitionFragment()
                FFViewModel.FRAGMENT_STATUS.VIDEO_RECOGNITION -> FaceVideoFragment()
                FFViewModel.FRAGMENT_STATUS.CAMERA_MENU -> CameraMenuFragment()
                FFViewModel.FRAGMENT_STATUS.CAMERA_RECOGNITION -> CameraRecognitionFragment()
                FFViewModel.FRAGMENT_STATUS.CAMERA_OBJECT_TRACK -> ObjectTrackFragment()
            }
        }

        val cur = currentFragment
        if (cur != null && cur !== target) {
            tx.hide(cur)
        }
        if (!target.isAdded) {
            tx.add(R.id.fragment_container, target, tag)
        } else {
            tx.show(target)
        }
        currentFragment = target
        tx.commit()
    }
}
