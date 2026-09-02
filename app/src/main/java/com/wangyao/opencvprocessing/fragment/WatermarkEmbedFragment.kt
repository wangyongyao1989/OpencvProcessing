package com.wangyao.opencvprocessing.fragment

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
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

/**
 * 三级页：数字水印的嵌入/提取（《数字图像与视频处理》第 8 章 8.2~8.4 节）。
 *
 * 对 assets/video.mp4 完整视频水印流程：
 * 1. 生成 64×64 二值水印（水印生成 G）；
 * 2. 选择算法（LSB 空间域 / DCT 变换域）后「嵌入水印到视频」：
 *    解码全部视频帧 → 在亮度平面嵌入水印（式 8-1）→ 重新编码为 MP4；
 *    左右两个 TextureView 同步循环播放「原视频 / 含水印视频」，
 *    直观对比嵌入前后肉眼几乎无差别（不可感知性），并给出 PSNR；
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

    /** 左右两路对比播放器（TextureView + MediaPlayer，显式生命周期管理）。 */
    private var playerOriginal: ComparePlayer? = null
    private var playerWatermarked: ComparePlayer? = null

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWmEmbedLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        playerOriginal = ComparePlayer(binding.videoOriginal)
        playerWatermarked = ComparePlayer(binding.videoWatermarked)
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

        // 上次已生成含水印视频：重建页面时直接恢复前后对比播放
        if (watermarkedFile.exists() && watermarkedFile.length() > 0L) {
            binding.tvFrameCompareTitle.visibility = View.VISIBLE
            binding.layoutFrameCompare.visibility = View.VISIBLE
            binding.tvCompareHint.visibility = View.VISIBLE
            playCompareVideos()
        }
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

        // 停止之前的播放，释放文件句柄
        stopCompareVideos()

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
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (watermarkedFile.exists()) watermarkedFile.delete()
                    val success = tempFile.renameTo(watermarkedFile)
                    if (!success) {
                        throw java.io.IOException("Failed to rename temp file to $watermarkedFile")
                    }
                } else {
                    throw java.io.IOException("Transcoding failed: output file is empty or missing")
                }

                val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                val psnr = if (originalFrame != null && watermarkedFrame != null)
                    WatermarkMetrics.psnr(originalFrame!!, watermarkedFrame!!) else 0.0

                activity?.runOnUiThread {
                    binding.tvFrameCompareTitle.visibility = View.VISIBLE
                    binding.layoutFrameCompare.visibility = View.VISIBLE
                    binding.tvCompareHint.visibility = View.VISIBLE
                    playCompareVideos()
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

    /**
     * 嵌入完成后左右同步播放「原视频 / 含水印视频」（静音 + 循环）。
     *
     * 播放基于 TextureView + MediaPlayer 显式驱动（不再用 VideoView）：
     * - TextureView 不依赖 SurfaceHolder 创建时序，容器 GONE→VISIBLE、
     *   Fragment hide→show 均能稳定创建 SurfaceTexture；
     * - 每路独立 prepared 即 start，两路都启动后 seekTo(0) 对齐帧级同步；
     * - 任何一路出错都显示到状态栏，绝不静默黑屏。
     */
    private fun playCompareVideos() {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return
        if (!watermarkedFile.exists() || watermarkedFile.length() == 0L) return

        var startedCount = 0
        val alignBoth: () -> Unit = {
            if (++startedCount == 2) {
                playerOriginal?.seekToStart()
                playerWatermarked?.seekToStart()
            }
        }
        playerOriginal?.onStarted = alignBoth
        playerWatermarked?.onStarted = alignBoth
        playerOriginal?.play(sourceFile.absolutePath)
        playerWatermarked?.play(watermarkedFile.absolutePath)
    }

    /** 停止并释放两路播放（重新嵌入 / 离开页面前调用）。 */
    private fun stopCompareVideos() {
        playerOriginal?.release()
        playerWatermarked?.release()
    }

    override fun onDestroyView() {
        stopCompareVideos()
        playerOriginal = null
        playerWatermarked = null
        super.onDestroyView()
    }

    // -------------------------------------------------------------------------
    // 单路对比播放器：TextureView + MediaPlayer，显式管理 Surface 生命周期
    // -------------------------------------------------------------------------

    /**
     * 播放状态机：
     * 1. [play] 时若 SurfaceTexture 已就绪 → 立即创建 MediaPlayer 异步准备；
     *    若未就绪（容器刚从 GONE 变 VISIBLE）→ 记住路径，
     *    等 onSurfaceTextureAvailable 回调后再打开；
     * 2. Fragment 被 hide（MainActivity 用 hide/show 切页）时 SurfaceTexture
     *    销毁 → 释放 MediaPlayer；show 回来后自动从 0 重新播放；
     * 3. onVideoSizeChanged 里做宽高比适配（fit-center），避免画面拉伸。
     */
    private inner class ComparePlayer(private val view: TextureView) {

        /** 该路视频成功 start 后回调（用于两路对齐）。 */
        var onStarted: (() -> Unit)? = null

        private var player: MediaPlayer? = null

        /** 当前应播放的文件路径（surface 未就绪时挂起，销毁重建后续播）。 */
        private var currentPath: String? = null

        init {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: SurfaceTexture, width: Int, height: Int
                ) {
                    currentPath?.let { open(it) }
                }

                override fun onSurfaceTextureSizeChanged(
                    st: SurfaceTexture, width: Int, height: Int
                ) {
                    player?.let { applyAspect(it.videoWidth, it.videoHeight) }
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    // 页面被隐藏/视图分离：释放播放器，SurfaceTexture 由系统回收
                    releasePlayer()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }

        fun play(path: String) {
            currentPath = path
            releasePlayer()
            if (view.isAvailable) open(path)
            // surface 未就绪时：等 onSurfaceTextureAvailable 再打开
        }

        fun seekToStart() {
            player?.seekTo(0)
        }

        fun release() {
            currentPath = null
            releasePlayer()
        }

        private fun releasePlayer() {
            player?.run {
                runCatching { stop() }
                runCatching { release() }
            }
            player = null
        }

        private fun open(path: String) {
            try {
                player = MediaPlayer().apply {
                    setDataSource(path)
                    setSurface(Surface(view.surfaceTexture))
                    isLooping = true
                    setVolume(0f, 0f)
                    setOnVideoSizeChangedListener { _, w, h ->
                        if (w > 0 && h > 0) applyAspect(w, h)
                    }
                    setOnPreparedListener { mp ->
                        mp.start()
                        onStarted?.invoke()
                    }
                    setOnErrorListener { _, what, extra ->
                        binding.tvStatus.text =
                            getString(R.string.wm_play_error, what, extra)
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.tvStatus.text = getString(R.string.wm_status_error, e.message ?: "")
            }
        }

        /** 按视频宽高比做 fit-center 缩放（TextureView 默认会拉伸填充）。 */
        private fun applyAspect(vw: Int, vh: Int) {
            if (vw <= 0 || vh <= 0 || view.width <= 0 || view.height <= 0) return
            val scale = minOf(
                view.width.toFloat() / vw,
                view.height.toFloat() / vh
            )
            val dx = (view.width - vw * scale) / 2f
            val dy = (view.height - vh * scale) / 2f
            view.setTransform(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                }
            )
        }
    }

    companion object {
        private const val ASSET_VIDEO = "video.mp4"
        private const val PREFS_NAME = "digital_watermark"
        private const val KEY_ALGO = "wm_algo"
    }
}
