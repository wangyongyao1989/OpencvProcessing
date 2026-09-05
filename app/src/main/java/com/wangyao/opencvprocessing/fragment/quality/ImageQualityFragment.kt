package com.wangyao.opencvprocessing.fragment.quality

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentQeImageLayoutBinding
import com.wangyao.qualityevaluation.core.ImageDistortions
import com.wangyao.qualityevaluation.core.ImageQualityMetrics
import kotlin.concurrent.thread

/**
 * 三级页：图像质量的客观评价（《数字图像与视频处理》第 9 章 9.2~9.4 节）。
 *
 * 对 assets/IMG_20260821_190758.jpg 的全参考客观评价流程：
 * 1. 解码原始图像并提取灰度（亮度）分量；
 * 2. 选择失真类型（JPEG 压缩/高斯噪声/椒盐噪声/均值模糊/亮度偏移），
 *    生成失真图像；
 * 3. 计算全参考客观指标：MAE/MSE/PSNR（式 9-1~9-3）、
 *    SSIM 结构相似度（式 9-4~9-6）、信息熵；
 * 4. 左右对比展示原始/失真图像（亮度通道），输出指标与质量等级结论。
 */
class ImageQualityFragment : BaseFragment() {

    private lateinit var binding: FragmentQeImageLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    /** 原始图像（降采样解码，最长边 ≤ [MAX_DIM]）。 */
    private var originalBitmap: Bitmap? = null
    private var originalLuma: ByteArray? = null
    private var imgWidth = 0
    private var imgHeight = 0

    /** 当前选择的失真类型（默认 JPEG 压缩）。 */
    private var distortion = ImageDistortions.Type.JPEG

    private var busy = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentQeImageLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        loadOriginalImage()
        updateDistortionNote()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.QUALITY_EVAL)
        }

        // 失真类型单选：选中一项时取消其余
        val checks = mapOf(
            binding.cbJpeg to ImageDistortions.Type.JPEG,
            binding.cbGaussian to ImageDistortions.Type.GAUSSIAN,
            binding.cbSalt to ImageDistortions.Type.SALT_PEPPER,
            binding.cbBlur to ImageDistortions.Type.BLUR,
            binding.cbBrightness to ImageDistortions.Type.BRIGHTNESS
        )
        val checkListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                checks.forEach { (cb, _) -> if (cb !== buttonView) cb.isChecked = false }
                checks[buttonView]?.let { distortion = it }
                updateDistortionNote()
            }
        }
        binding.cbJpeg.setOnCheckedChangeListener(checkListener)
        binding.cbGaussian.setOnCheckedChangeListener(checkListener)
        binding.cbSalt.setOnCheckedChangeListener(checkListener)
        binding.cbBlur.setOnCheckedChangeListener(checkListener)
        binding.cbBrightness.setOnCheckedChangeListener(checkListener)

        binding.btnEvaluate.setOnClickListener {
            if (!busy) runEvaluate()
        }
    }

    // -------------------------------------------------------------------------
    // 数据加载
    // -------------------------------------------------------------------------

    /** 从 assets 解码原始图像（降采样到最长边 ≤ MAX_DIM，加速评价）。 */
    private fun loadOriginalImage() {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireContext().assets.open(ASSET_IMAGE).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        originalBitmap = requireContext().assets.open(ASSET_IMAGE).use {
            BitmapFactory.decodeStream(it, null, opts)
        }?.also { bmp ->
            imgWidth = bmp.width
            imgHeight = bmp.height
            originalLuma = bitmapToLuma(bmp)
        }
    }

    // -------------------------------------------------------------------------
    // 评价流程
    // -------------------------------------------------------------------------

    private fun runEvaluate() {
        val srcLuma = originalLuma ?: return
        val srcBitmap = originalBitmap ?: return
        busy = true
        binding.btnEvaluate.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true
        binding.tvStatus.text = getString(R.string.qe_status_running)

        val type = distortion
        thread(start = true) {
            // 1. 生成失真图像（亮度通道）
            val distortedLuma: ByteArray
            if (type == ImageDistortions.Type.JPEG) {
                // JPEG 在 Bitmap 上往返（编码→解码），再取亮度
                val q = ImageDistortions.Presets.JPEG_QUALITY
                val jpegBmp = ImageDistortions.jpeg(srcBitmap, q)
                distortedLuma = bitmapToLuma(jpegBmp)
                jpegBmp.recycle()
            } else {
                distortedLuma = srcLuma.copyOf()
                when (type) {
                    ImageDistortions.Type.GAUSSIAN ->
                        ImageDistortions.gaussianNoise(
                            distortedLuma, ImageDistortions.Presets.GAUSSIAN_SIGMA
                        )
                    ImageDistortions.Type.SALT_PEPPER ->
                        ImageDistortions.saltPepperNoise(
                            distortedLuma, ImageDistortions.Presets.SALT_PEPPER_DENSITY
                        )
                    ImageDistortions.Type.BLUR ->
                        ImageDistortions.meanBlur(distortedLuma, imgWidth, imgHeight)
                    ImageDistortions.Type.BRIGHTNESS ->
                        ImageDistortions.brightnessShift(
                            distortedLuma, ImageDistortions.Presets.BRIGHTNESS_DELTA
                        )
                    else -> Unit
                }
            }

            // 2. 全参考客观评价（式 9-1~9-6 + 信息熵）
            val result = ImageQualityMetrics.evaluateAll(
                srcLuma, distortedLuma, imgWidth, imgHeight
            )

            // 3. UI 展示
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.progress.visibility = View.GONE
                binding.btnEvaluate.isEnabled = true
                busy = false

                binding.tvResultTitle.visibility = View.VISIBLE
                binding.layoutCompare.visibility = View.VISIBLE
                binding.ivOriginal.setImageBitmap(lumaToGrayBitmap(srcLuma, imgWidth, imgHeight))
                binding.ivDistorted.setImageBitmap(
                    lumaToGrayBitmap(distortedLuma, imgWidth, imgHeight)
                )

                binding.tvMetrics.visibility = View.VISIBLE
                binding.tvMetrics.text = getString(
                    R.string.qe_image_metrics,
                    distortionName(type),
                    result.mse,
                    result.mae,
                    result.psnr,
                    result.ssim,
                    result.entropyOriginal,
                    result.entropyDistorted,
                    qualityGrade(result.psnr, result.ssim)
                )
                binding.tvStatus.text = getString(R.string.qe_status_done)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    /** 依据当前失真类型切换说明文案。 */
    private fun updateDistortionNote() {
        val res = when (distortion) {
            ImageDistortions.Type.JPEG -> R.string.qe_note_jpeg
            ImageDistortions.Type.GAUSSIAN -> R.string.qe_note_gaussian
            ImageDistortions.Type.SALT_PEPPER -> R.string.qe_note_salt
            ImageDistortions.Type.BLUR -> R.string.qe_note_blur
            ImageDistortions.Type.BRIGHTNESS -> R.string.qe_note_brightness
        }
        binding.tvDistortionNote.text = getString(res)
    }

    /** 失真类型显示名。 */
    private fun distortionName(type: ImageDistortions.Type): String = getString(
        when (type) {
            ImageDistortions.Type.JPEG -> R.string.qe_dist_jpeg
            ImageDistortions.Type.GAUSSIAN -> R.string.qe_dist_gaussian
            ImageDistortions.Type.SALT_PEPPER -> R.string.qe_dist_salt
            ImageDistortions.Type.BLUR -> R.string.qe_dist_blur
            ImageDistortions.Type.BRIGHTNESS -> R.string.qe_dist_brightness
        }
    )

    /** 依据 PSNR/SSIM 给出质量等级结论（教学参考阈值）。 */
    private fun qualityGrade(psnr: Double, ssim: Double): String = when {
        psnr >= 40.0 && ssim >= 0.95 -> getString(R.string.qe_grade_excellent)
        psnr >= 30.0 && ssim >= 0.85 -> getString(R.string.qe_grade_good)
        psnr >= 20.0 && ssim >= 0.60 -> getString(R.string.qe_grade_fair)
        else -> getString(R.string.qe_grade_poor)
    }

    /** Bitmap → 灰度（亮度）数组：Y = (299R+587G+114B)/1000。 */
    private fun bitmapToLuma(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val luma = ByteArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        return luma
    }

    /** 灰度数组 → 灰度 Bitmap（展示用，降采样到 ≤480 宽）。 */
    private fun lumaToGrayBitmap(luma: ByteArray, w: Int, h: Int): Bitmap {
        val scale = maxOf(1, (w + 479) / 480)
        val bw = w / scale
        val bh = h / scale
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val px = IntArray(bw * bh)
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val v = luma[(y * scale) * w + (x * scale)].toInt() and 0xFF
                px[y * bw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
        return bmp
    }

    companion object {
        private const val ASSET_IMAGE = "IMG_20260821_190758.jpg"
        private const val MAX_DIM = 1600
    }
}
