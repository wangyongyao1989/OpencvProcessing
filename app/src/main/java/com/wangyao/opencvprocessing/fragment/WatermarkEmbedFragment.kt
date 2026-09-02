package com.wangyao.opencvprocessing.fragment

import android.content.Context
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
import com.wangyao.digitalwatermark.core.WatermarkGenerator
import com.wangyao.digitalwatermark.core.WatermarkMetrics
import com.wangyao.digitalwatermark.video.VideoWatermarkPipeline
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentWmEmbedLayoutBinding
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * 三级页：数字水印的嵌入/提取（《数字图像与视频处理》第 8 章 8.2~8.4 节）。
 *
 * 对 assets/video.mp4 完整视频水印流程：
 * 1. 生成 64×64 二值水印（水印生成 G）；
 * 2. 选择算法（LSB 空间域 / DCT 变换域）后「嵌入水印到视频」：
 *    解码全部视频帧 → 在亮度平面嵌入水印（式 8-1）→ 重新编码为 MP4；
 *    展示中间帧的原图/含水印对比与 PSNR（不可感知性评价）；
 * 3. 「从视频提取水印」：解码含水印视频 → 逐帧盲提取（式 8-4）→
 *    帧间多数投票 → 展示提取水印与 NC/BER（鲁棒性评价）。
 */
class WatermarkEmbedFragment : BaseFragment() {

    private lateinit var binding: FragmentWmEmbedLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    /** 水印算法：true = LSB 空间域，false = DCT 变换域。 */
    private var useLsb = true

    /** 水印位阵（64×64 = 4096 bit）。 */
    private lateinit var watermarkBits: BooleanArray

    /** 源视频（assets 拷贝到私有目录）与含水印视频。 */
    private lateinit var sourceFile: File
    private lateinit var watermarkedFile: File

    private var busy = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWmEmbedLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initData() {
        // 生成水印并预览
        watermarkBits = WatermarkGenerator.generateBits()
        binding.ivWatermark.setImageBitmap(WatermarkGenerator.bitsToBitmap(watermarkBits))

        // assets → 私有目录（MediaExtractor 需要文件路径）
        sourceFile = File(requireContext().filesDir, "wm_source.mp4")
        watermarkedFile = File(requireContext().filesDir, "wm_watermarked.mp4")
        if (!sourceFile.exists()) {
            requireContext().assets.open(ASSET_VIDEO).use { input ->
                sourceFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // 源视频信息
        val info = VideoWatermarkPipeline().probe(sourceFile.absolutePath)
        binding.tvVideoInfo.text = getString(
            R.string.wm_video_info,
            info.width, info.height, info.frameRate, info.frameCount,
            sourceFile.length() / 1024.0 / 1024.0
        )

        binding.tvFormula.text = getString(R.string.wm_embed_formula)
        binding.tvStatus.text = getString(R.string.wm_status_idle)
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.WATERMARK)
        }

        // 算法互斥单选
        val checker = CompoundButton.OnCheckedChangeListener { view, isChecked ->
            if (!isChecked || applyingChecked) return@OnCheckedChangeListener
            applyingChecked = true
            if (view === binding.cbAlgoLsb) {
                binding.cbAlgoDct.isChecked = false
                useLsb = true
            } else {
                binding.cbAlgoLsb.isChecked = false
                useLsb = false
            }
            applyingChecked = false
            updateFormula()
        }
        binding.cbAlgoLsb.setOnCheckedChangeListener(checker)
        binding.cbAlgoDct.setOnCheckedChangeListener(checker)
        binding.cbAlgoLsb.isChecked = true

        binding.btnEmbed.setOnClickListener { if (!busy) runEmbed() }
        binding.btnExtract.setOnClickListener { if (!busy) runExtract() }
    }

    private var applyingChecked = false

    /** 依据当前算法切换公式卡片文案。 */
    private fun updateFormula() {
        binding.tvFormula.text = getString(
            if (useLsb) R.string.wm_embed_formula_lsb else R.string.wm_embed_formula_dct
        )
    }

    /** 把当前算法写入 SharedPreferences（攻击页须用同一算法提取）。 */
    private fun saveAlgoPref() {
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALGO, if (useLsb) "lsb" else "dct")
            .apply()
    }

    // -------------------------------------------------------------------------
    // 嵌入：解码 → 亮度平面嵌入水印 → 编码
    // -------------------------------------------------------------------------

    private fun runEmbed() {
        busy = true
        saveAlgoPref()
        setButtonsEnabled(false)
        binding.progress.isIndeterminate = false
        binding.progress.progress = 0
        binding.tvStatus.text = getString(R.string.wm_status_preparing)

        val startMs = System.currentTimeMillis()
        thread(start = true) {
            val tempFile = File(requireContext().filesDir, "wm_watermarked.mp4.tmp")
            try {
                val pipeline = VideoWatermarkPipeline()
                val total = pipeline.probe(sourceFile.absolutePath).frameCount
                val midIdx = total / 2
                var originalFrame: ByteArray? = null
                var watermarkedFrame: ByteArray? = null

                val frames = pipeline.transcode(
                    sourceFile.absolutePath,
                    tempFile.absolutePath,
                    onFrame = { luma, w, h, idx ->
                        // 采集中间帧做对比：嵌入前（原图）/ 嵌入后（含水印）
                        if (idx == midIdx) originalFrame = luma.copyOf()
                        if (useLsb) LsbWatermark.embed(luma, w, h, watermarkBits)
                        else DctWatermark.embed(luma, w, h, watermarkBits)
                        if (idx == midIdx) watermarkedFrame = luma.copyOf()
                        true
                    },
                    onProgress = { done, t ->
                        activity?.runOnUiThread {
                            binding.progress.progress = (done * 100L / t.coerceAtLeast(1)).toInt()
                            binding.tvStatus.text = getString(R.string.wm_status_embedding, done, t)
                        }
                    }
                )

                // 成功后才替换正式文件
                if (tempFile.exists()) {
                    if (watermarkedFile.exists()) watermarkedFile.delete()
                    tempFile.renameTo(watermarkedFile)
                }

                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                val psnr = if (originalFrame != null && watermarkedFrame != null)
                    WatermarkMetrics.psnr(originalFrame!!, watermarkedFrame!!) else 0.0

                activity?.runOnUiThread {
                    binding.tvFrameCompareTitle.visibility = View.VISIBLE
                    binding.layoutFrameCompare.visibility = View.VISIBLE
                    binding.ivFrameOriginal.setImageBitmap(lumaToBitmap(originalFrame))
                    binding.ivFrameWatermarked.setImageBitmap(lumaToBitmap(watermarkedFrame))
                    binding.tvEmbedMetrics.visibility = View.VISIBLE
                    binding.tvEmbedMetrics.text = getString(
                        R.string.wm_embed_metrics,
                        frames, elapsed, psnr,
                        watermarkedFile.length() / 1024.0 / 1024.0,
                        if (useLsb) getString(R.string.wm_algo_lsb)
                        else getString(R.string.wm_algo_dct)
                    )
                    binding.tvStatus.text = getString(R.string.wm_status_embed_done, frames)
                    busy = false
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (tempFile.exists()) tempFile.delete()
                activity?.runOnUiThread {
                    binding.tvStatus.text = getString(R.string.wm_status_error, e.message ?: "")
                    busy = false
                    setButtonsEnabled(true)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 提取：解码含水印视频 → 逐帧盲提取 → 帧间多数投票
    // -------------------------------------------------------------------------

    private fun runExtract() {
        if (!watermarkedFile.exists() || watermarkedFile.length() == 0L) {
            Toast.makeText(requireContext(), R.string.wm_need_embed_first, Toast.LENGTH_SHORT)
                .show()
            return
        }
        busy = true
        setButtonsEnabled(false)
        binding.progress.progress = 0
        binding.tvStatus.text = getString(R.string.wm_status_preparing)

        val startMs = System.currentTimeMillis()
        thread(start = true) {
            try {
                val pipeline = VideoWatermarkPipeline()
                val votes = IntArray(WatermarkGenerator.BIT_COUNT)
                var frames = 0

                pipeline.decodeOnly(
                    watermarkedFile.absolutePath,
                    onFrame = { luma, w, h, _ ->
                        val v = if (useLsb) LsbWatermark.extractVotes(luma, w, h)
                        else DctWatermark.extractVotes(luma, w, h)
                        for (i in votes.indices) votes[i] += v[i]
                        frames++
                    },
                    onProgress = { done, t ->
                        activity?.runOnUiThread {
                            binding.progress.progress = (done * 100L / t.coerceAtLeast(1)).toInt()
                            binding.tvStatus.text = getString(R.string.wm_status_extracting, done, t)
                        }
                    }
                )

                // 帧间多数投票 → 水印位阵
                val extracted = BooleanArray(votes.size) { votes[it] > 0 }
                val nc = WatermarkMetrics.nc(watermarkBits, extracted)
                val ber = WatermarkMetrics.ber(watermarkBits, extracted)
                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0

                activity?.runOnUiThread {
                    binding.tvExtractTitle.visibility = View.VISIBLE
                    binding.layoutExtractResult.visibility = View.VISIBLE
                    binding.ivWatermarkExtracted.setImageBitmap(
                        WatermarkGenerator.bitsToBitmap(extracted)
                    )
                    binding.tvExtractMetrics.text = getString(
                        R.string.wm_extract_metrics,
                        frames, elapsed, nc, ber
                    )
                    binding.tvStatus.text = getString(R.string.wm_status_extract_done, frames)
                    busy = false
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity?.runOnUiThread {
                    binding.tvStatus.text = getString(R.string.wm_status_error, e.message ?: "")
                    busy = false
                    setButtonsEnabled(true)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnEmbed.isEnabled = enabled
        binding.btnExtract.isEnabled = enabled
        binding.cbAlgoLsb.isEnabled = enabled
        binding.cbAlgoDct.isEnabled = enabled
    }

    /** 亮度平面 → 灰度 Bitmap（帧预览用，降采样到 480 宽）。 */
    private fun lumaToBitmap(luma: ByteArray?): Bitmap? {
        if (luma == null) return null
        val info = VideoWatermarkPipeline().probe(sourceFile.absolutePath)
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
        private const val ASSET_VIDEO = "video.mp4"
        private const val PREFS_NAME = "digital_watermark"
        private const val KEY_ALGO = "wm_algo"
    }
}
