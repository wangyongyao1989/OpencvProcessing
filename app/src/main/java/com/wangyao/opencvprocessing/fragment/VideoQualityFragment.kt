package com.wangyao.opencvprocessing.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentQeVideoLayoutBinding
import com.wangyao.qualityevaluation.video.VideoQualityPipeline
import java.io.File
import kotlin.concurrent.thread

/**
 * 三级页：视频质量的客观评价（《数字图像与视频处理》
 * 第 9 章视频质量评价 + 第 6 章率失真 R-D）。
 *
 * 对 assets/video.mp4 的完整评价流程：
 * 1. 选择目标码率（4Mbps/1Mbps/256kbps）；
 * 2. 转码：按 H.264 编码标准（第 6 章）重新编码为低码率 MP4
 *    （第 7 章文件格式）——码率越低，压缩比越高、失真越大；
 * 3. 评价：双路解码「原始 vs 压缩」视频，逐帧在亮度平面计算
 *    PSNR（全分辨率）与 SSIM（2× 下采样加速），统计平均/最差帧
 *    指标——第 9 章「逐帧全参考度量 + 时间轴聚合」的视频客观
 *    评价方法，直观呈现率失真（码率↓ → PSNR/SSIM↓）关系。
 */
class VideoQualityFragment : BaseFragment() {

    private lateinit var binding: FragmentQeVideoLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    /** 源视频（assets 拷贝到私有目录）与压缩输出视频。 */
    private lateinit var sourceFile: File
    private lateinit var compressedFile: File

    /** 当前选择的目标码率（bps）。 */
    private var targetBitrate = BITRATE_HIGH

    private var busy = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentQeVideoLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        sourceFile = File(requireContext().filesDir, "qe_source.mp4")
        compressedFile = File(requireContext().filesDir, "qe_compressed.mp4")
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            requireContext().assets.open(ASSET_VIDEO).use { input ->
                sourceFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val info = VideoQualityPipeline().probe(sourceFile.absolutePath)
        binding.tvVideoInfo.text = getString(
            R.string.qe_video_info,
            info.width, info.height, info.frameRate, info.frameCount,
            sourceFile.length() / 1024.0 / 1024.0
        )
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.QUALITY_EVAL)
        }

        // 码率单选：选中一项时取消其余
        val checks = mapOf(
            binding.cbBitrateHigh to BITRATE_HIGH,
            binding.cbBitrateMid to BITRATE_MID,
            binding.cbBitrateLow to BITRATE_LOW
        )
        val checkListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                checks.forEach { (cb, _) -> if (cb !== buttonView) cb.isChecked = false }
                checks[buttonView]?.let { targetBitrate = it }
            }
        }
        binding.cbBitrateHigh.setOnCheckedChangeListener(checkListener)
        binding.cbBitrateMid.setOnCheckedChangeListener(checkListener)
        binding.cbBitrateLow.setOnCheckedChangeListener(checkListener)

        binding.btnEvaluate.setOnClickListener {
            if (!busy) runEvaluate()
        }
    }

    // -------------------------------------------------------------------------
    // 评价流程
    // -------------------------------------------------------------------------

    private fun runEvaluate() {
        busy = true
        binding.btnEvaluate.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = false
        binding.progress.progress = 0

        val bitrate = targetBitrate
        val pipeline = VideoQualityPipeline()
        val startMs = System.currentTimeMillis()

        thread(start = true) {
            try {
                // ---- 阶段一：转码（0~60%）----
                activity?.runOnUiThread {
                    binding.tvStatus.text = getString(
                        R.string.qe_status_transcoding, bitrate / 1_000_000.0
                    )
                }
                pipeline.transcode(
                    sourceFile.absolutePath,
                    compressedFile.absolutePath,
                    bitrate
                ) { done, total ->
                    activity?.runOnUiThread {
                        if (isAdded) {
                            binding.progress.progress = (done * 60L / total.coerceAtLeast(1)).toInt()
                        }
                    }
                }

                // ---- 阶段二：双路解码逐帧评价（60~100%）----
                activity?.runOnUiThread {
                    binding.tvStatus.text = getString(R.string.qe_status_evaluating)
                }
                val result = pipeline.evaluate(
                    sourceFile.absolutePath,
                    compressedFile.absolutePath
                ) { done, total ->
                    activity?.runOnUiThread {
                        if (isAdded) {
                            binding.progress.progress =
                                (60 + done * 40L / total.coerceAtLeast(1)).toInt()
                        }
                    }
                }

                // ---- 结果展示 ----
                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                val srcMb = sourceFile.length() / 1024.0 / 1024.0
                val cmpMb = compressedFile.length() / 1024.0 / 1024.0
                val ratio = sourceFile.length().toDouble() /
                        compressedFile.length().coerceAtLeast(1)

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progress.visibility = View.GONE
                    binding.btnEvaluate.isEnabled = true
                    busy = false

                    binding.tvResultTitle.visibility = View.VISIBLE
                    binding.layoutCompare.visibility = View.VISIBLE
                    result.midOriginalLuma?.let {
                        binding.ivFrameOriginal.setImageBitmap(
                            VideoQualityPipeline.lumaToBitmap(
                                it, result.width, result.height
                            )
                        )
                    }
                    result.midProcessedLuma?.let {
                        binding.ivFrameCompressed.setImageBitmap(
                            VideoQualityPipeline.lumaToBitmap(
                                it, result.width, result.height
                            )
                        )
                    }

                    binding.tvMetrics.visibility = View.VISIBLE
                    binding.tvMetrics.text = getString(
                        R.string.qe_video_metrics,
                        bitrate / 1_000_000.0,
                        result.avgPsnr,
                        result.minPsnr,
                        result.minPsnrFrame + 1,
                        result.avgSsim,
                        result.minSsim,
                        srcMb,
                        cmpMb,
                        ratio,
                        elapsed,
                        result.frames
                    )
                    binding.tvStatus.text = getString(R.string.qe_status_done)
                }
            } catch (e: Exception) {
                // 关键诊断信息：完整堆栈进 logcat，界面显示「异常类名: 消息」
                // （CodecException 等的 getMessage() 可能为 null，只有类名也能定位）
                android.util.Log.e("QE_VideoFragment", "evaluate pipeline failed", e)
                val errText = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progress.visibility = View.GONE
                    binding.btnEvaluate.isEnabled = true
                    busy = false
                    binding.tvStatus.text = getString(R.string.qe_status_error, errText)
                }
            }
        }
    }

    companion object {
        private const val ASSET_VIDEO = "video.mp4"
        private const val BITRATE_HIGH = 4_000_000   // 4 Mbps（中等压缩）
        private const val BITRATE_MID = 1_000_000    // 1 Mbps（强压缩）
        private const val BITRATE_LOW = 256_000      // 256 kbps（极强压缩）
    }
}
