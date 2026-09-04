package com.wangyao.opencvprocessing.fragment.watermark

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.wangyao.digitalwatermark.core.DctWatermark
import com.wangyao.digitalwatermark.core.LsbWatermark
import com.wangyao.digitalwatermark.core.WatermarkAttacks
import com.wangyao.digitalwatermark.core.WatermarkGenerator
import com.wangyao.digitalwatermark.core.WatermarkMetrics
import com.wangyao.digitalwatermark.video.VideoWatermarkPipeline
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentWmAttackLayoutBinding
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * 三级页：水印的攻击方法和对策（《数字图像与视频处理》第 8 章 8.5 节）。
 *
 * 对嵌入页生成的含水印视频施加攻击 → 从受攻击视频中提取水印 →
 * 用 NC/BER/PSNR 评价水印鲁棒性，并给出教材对应的对策说明：
 *
 * - 简单攻击（JPEG 压缩 / 噪声 / 滤波）：削弱水印幅度；
 *   对策：增大嵌入强度、冗余嵌入 + 多数投票、纠错编码；
 * - 同步攻击（缩放 / 旋转 / 裁剪）：破坏水印与载体同步性；
 *   对策：同步模板 / 参照物、有源提取反转、低频过滤；
 * - 帧丢失攻击（视频特有）：对策：帧间冗余嵌入。
 */
class WatermarkAttackFragment : BaseFragment() {

    private lateinit var binding: FragmentWmAttackLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    /** 当前选中的攻击类型。 */
    private var attackType = WatermarkAttacks.Type.JPEG

    /** 提取算法须与嵌入算法一致（由嵌入页写入 SharedPreferences）。 */
    private var useLsb = true

    private lateinit var watermarkBits: BooleanArray
    private lateinit var watermarkedFile: File
    private lateinit var attackedFile: File

    private var busy = false
    private var applyingChecked = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWmAttackLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initData() {
        watermarkBits = WatermarkGenerator.generateBits()
        watermarkedFile = File(requireContext().filesDir, "wm_watermarked.mp4")
        attackedFile = File(requireContext().filesDir, "wm_attacked.mp4")

        useLsb = requireContext()
            .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_ALGO, "lsb") == "lsb"

        binding.tvFormula.text = getString(R.string.wm_attack_formula)
        binding.tvStatus.text = getString(
            R.string.wm_attack_status_idle,
            if (useLsb) getString(R.string.wm_algo_lsb) else getString(R.string.wm_algo_dct)
        )
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.WATERMARK)
        }

        // 攻击方式互斥单选
        val boxes = listOf(
            binding.cbAtkJpeg to WatermarkAttacks.Type.JPEG,
            binding.cbAtkGaussian to WatermarkAttacks.Type.GAUSSIAN,
            binding.cbAtkSalt to WatermarkAttacks.Type.SALT_PEPPER,
            binding.cbAtkMean to WatermarkAttacks.Type.MEAN_FILTER,
            binding.cbAtkMedian to WatermarkAttacks.Type.MEDIAN_FILTER,
            binding.cbAtkScale to WatermarkAttacks.Type.SCALE,
            binding.cbAtkRotate to WatermarkAttacks.Type.ROTATE,
            binding.cbAtkCrop to WatermarkAttacks.Type.CROP,
            binding.cbAtkDrop to WatermarkAttacks.Type.FRAME_DROP,
        )
        val checker = CompoundButton.OnCheckedChangeListener { view, isChecked ->
            if (!isChecked || applyingChecked) return@OnCheckedChangeListener
            applyingChecked = true
            for ((cb, _) in boxes) if (cb !== view) cb.isChecked = false
            applyingChecked = false
            attackType = boxes.first { it.first === view }.second
            updateCountermeasurePreview()
        }
        for ((cb, _) in boxes) cb.setOnCheckedChangeListener(checker)
        binding.cbAtkJpeg.isChecked = true

        binding.btnAttack.setOnClickListener { if (!busy) runAttack() }
    }

    /** 选中攻击方式即预览其对策说明（便于学习对照）。 */
    private fun updateCountermeasurePreview() {
        binding.tvCountermeasureTitle.visibility = View.VISIBLE
        binding.cardCountermeasure.visibility = View.VISIBLE
        binding.tvCountermeasure.text = countermeasureText(attackType)
    }

    private fun countermeasureText(type: WatermarkAttacks.Type): String = getString(
        when (type) {
            WatermarkAttacks.Type.JPEG -> R.string.wm_cm_jpeg
            WatermarkAttacks.Type.GAUSSIAN -> R.string.wm_cm_gaussian
            WatermarkAttacks.Type.SALT_PEPPER -> R.string.wm_cm_salt
            WatermarkAttacks.Type.MEAN_FILTER -> R.string.wm_cm_mean
            WatermarkAttacks.Type.MEDIAN_FILTER -> R.string.wm_cm_median
            WatermarkAttacks.Type.SCALE -> R.string.wm_cm_scale
            WatermarkAttacks.Type.ROTATE -> R.string.wm_cm_rotate
            WatermarkAttacks.Type.CROP -> R.string.wm_cm_crop
            WatermarkAttacks.Type.FRAME_DROP -> R.string.wm_cm_drop
        }
    )

    // -------------------------------------------------------------------------
    // 攻击 → 提取 → 评价
    // -------------------------------------------------------------------------

    private fun runAttack() {
        if (!watermarkedFile.exists()) {
            Toast.makeText(requireContext(), R.string.wm_need_embed_first, Toast.LENGTH_SHORT)
                .show()
            return
        }
        busy = true
        setControlsEnabled(false)
        binding.progress.progress = 0
        binding.tvStatus.text = getString(R.string.wm_status_preparing)

        val startMs = System.currentTimeMillis()
        thread(start = true) {
            try {
                val pipeline = VideoWatermarkPipeline()

                // ---- 阶段一：攻击含水印视频（解码 → 攻击 → 编码）----
                val total = pipeline.probe(watermarkedFile.absolutePath).frameCount
                val midIdx = total / 2
                var beforeFrame: ByteArray? = null
                var afterFrame: ByteArray? = null

                val outFrames = pipeline.transcode(
                    watermarkedFile.absolutePath,
                    attackedFile.absolutePath,
                    onFrame = { luma, w, h, idx ->
                        if (idx == midIdx) beforeFrame = luma.copyOf()
                        applyAttack(luma, w, h, idx)
                        if (idx == midIdx) afterFrame = luma.copyOf()
                        // 帧丢失攻击：丢弃奇数帧，其余攻击保留全部帧
                        attackType != WatermarkAttacks.Type.FRAME_DROP || idx % 2 == 0
                    },
                    onProgress = { done, t ->
                        activity?.runOnUiThread {
                            binding.progress.progress = (done * 50L / t.coerceAtLeast(1)).toInt()
                            binding.tvStatus.text =
                                getString(R.string.wm_status_attacking, done, t)
                        }
                    }
                )

                // ---- 阶段二：从受攻击视频提取水印（盲检测 + 帧间投票）----
                val votes = IntArray(WatermarkGenerator.BIT_COUNT)
                var extFrames = 0
                pipeline.decodeOnly(
                    attackedFile.absolutePath,
                    onFrame = { luma, w, h, _ ->
                        val v = if (useLsb) LsbWatermark.extractVotes(luma, w, h)
                        else DctWatermark.extractVotes(luma, w, h)
                        for (i in votes.indices) votes[i] += v[i]
                        extFrames++
                    },
                    onProgress = { done, t ->
                        activity?.runOnUiThread {
                            binding.progress.progress =
                                50 + (done * 50L / t.coerceAtLeast(1)).toInt()
                            binding.tvStatus.text =
                                getString(R.string.wm_status_extracting, done, t)
                        }
                    }
                )

                val extracted = BooleanArray(votes.size) { votes[it] > 0 }
                val nc = WatermarkMetrics.nc(watermarkBits, extracted)
                val ber = WatermarkMetrics.ber(watermarkBits, extracted)
                val psnr = if (beforeFrame != null && afterFrame != null)
                    WatermarkMetrics.psnr(beforeFrame!!, afterFrame!!) else 0.0
                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0

                activity?.runOnUiThread {
                    binding.tvResultTitle.visibility = View.VISIBLE
                    binding.layoutFrameCompare.visibility = View.VISIBLE
                    binding.ivFrameBefore.setImageBitmap(lumaToBitmap(beforeFrame))
                    binding.ivFrameAfter.setImageBitmap(lumaToBitmap(afterFrame))
                    binding.layoutExtractResult.visibility = View.VISIBLE
                    binding.ivWatermarkExtracted.setImageBitmap(
                        WatermarkGenerator.bitsToBitmap(extracted)
                    )
                    binding.tvMetrics.text = getString(
                        R.string.wm_attack_metrics,
                        nc, ber, psnr, elapsed, outFrames, extFrames
                    )
                    binding.tvCountermeasureTitle.visibility = View.VISIBLE
                    binding.cardCountermeasure.visibility = View.VISIBLE
                    binding.tvCountermeasure.text = countermeasureText(attackType)
                    binding.tvStatus.text = getString(R.string.wm_attack_status_done, outFrames)
                    busy = false
                    setControlsEnabled(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    binding.tvStatus.text = getString(R.string.wm_status_error, e.message ?: "")
                    busy = false
                    setControlsEnabled(true)
                }
            }
        }
    }

    /** 按当前攻击类型对亮度平面施加攻击。 */
    private fun applyAttack(luma: ByteArray, w: Int, h: Int, frameIndex: Int) {
        when (attackType) {
            WatermarkAttacks.Type.JPEG -> WatermarkAttacks.jpeg(luma, w, h, 10)
            WatermarkAttacks.Type.GAUSSIAN -> WatermarkAttacks.gaussianNoise(luma, 15.0)
            WatermarkAttacks.Type.SALT_PEPPER -> WatermarkAttacks.saltPepperNoise(luma, 0.05)
            WatermarkAttacks.Type.MEAN_FILTER -> WatermarkAttacks.meanFilter(luma, w, h)
            WatermarkAttacks.Type.MEDIAN_FILTER -> WatermarkAttacks.medianFilter(luma, w, h)
            WatermarkAttacks.Type.SCALE -> WatermarkAttacks.scale(luma, w, h)
            WatermarkAttacks.Type.ROTATE -> WatermarkAttacks.rotate(luma, w, h, 2.0)
            WatermarkAttacks.Type.CROP -> WatermarkAttacks.crop(luma, w, h, 0.1)
            WatermarkAttacks.Type.FRAME_DROP -> Unit // 帧丢失在 onFrame 返回值中处理
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    private fun setControlsEnabled(enabled: Boolean) {
        binding.btnAttack.isEnabled = enabled
        binding.cbAtkJpeg.isEnabled = enabled
        binding.cbAtkGaussian.isEnabled = enabled
        binding.cbAtkSalt.isEnabled = enabled
        binding.cbAtkMean.isEnabled = enabled
        binding.cbAtkMedian.isEnabled = enabled
        binding.cbAtkScale.isEnabled = enabled
        binding.cbAtkRotate.isEnabled = enabled
        binding.cbAtkCrop.isEnabled = enabled
        binding.cbAtkDrop.isEnabled = enabled
    }

    /** 亮度平面 → 灰度 Bitmap（降采样到 480 宽）。 */
    private fun lumaToBitmap(luma: ByteArray?): Bitmap? {
        if (luma == null) return null
        val info = VideoWatermarkPipeline().probe(watermarkedFile.absolutePath)
        val w = info.width
        val h = info.height
        val scale = maxOf(1, (w / 480.0).roundToInt())
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
        private const val PREFS_NAME = "digital_watermark"
        private const val KEY_ALGO = "wm_algo"
    }
}
