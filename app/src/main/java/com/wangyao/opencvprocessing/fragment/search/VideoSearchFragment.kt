package com.wangyao.opencvprocessing.fragment.search

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentCsVideoLayoutBinding
import com.wangyao.contentsearch.video.VideoSearchEngine
import java.io.File
import java.util.Random
import kotlin.concurrent.thread

/**
 * 三级页：基于内容的视频检索（《数字图像与视频处理》第 10 章 10.5 节）。
 *
 * 对 assets/video.mp4 的完整流程：
 * 1. 关键帧抽取：软件解码全视频，按帧序均匀抽取 12 个关键帧
 *    （关键帧是视频内容代表，将检索规模从「逐帧」降到「镜头级」）；
 * 2. 特征索引：每个关键帧提取「HSV 颜色直方图 + 梯度方向纹理
 *    直方图」，并记录时间戳（µs）；
 * 3. 检索：点击关键帧带选择查询帧（可叠加亮度/噪声/裁剪失真），
 *    与全部关键帧特征计算综合相似度，按相似度降序返回 Top-K，
 *    命中结果显示时间点——即「在视频中定位相似内容」；
 * 4. 鲁棒性验证：失真查询帧仍应命中其来源关键帧（视频检索的
 *    抗失真能力），不同内容的关键帧相似度显著更低。
 */
class VideoSearchFragment : BaseFragment() {

    private lateinit var binding: FragmentCsVideoLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private lateinit var videoFile: File
    private val engine = VideoSearchEngine()

    /** 用户选中的查询关键帧下标（关键帧带中）。 */
    private var selectedKfIndex = -1
    private var queryDistortion = Distortion.NONE
    private var busy = false

    /** 查询帧失真类型。 */
    enum class Distortion { NONE, BRIGHT, NOISE, CROP }

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCsVideoLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        videoFile = File(requireContext().filesDir, "cs_video.mp4")
        if (!videoFile.exists()) {
            requireContext().assets.open("video.mp4").use { input ->
                videoFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    override fun onDestroyView() {
        // 防止线程回来后操作已销毁的 View
        super.onDestroyView()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.CONTENT_SEARCH)
        }

        binding.btnExtract.setOnClickListener {
            if (!busy) runExtract()
        }

        // 查询失真单选
        val checks = mapOf(
            binding.cbDistNone to Distortion.NONE,
            binding.cbDistBright to Distortion.BRIGHT,
            binding.cbDistNoise to Distortion.NOISE,
            binding.cbDistCrop to Distortion.CROP
        )
        val listener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                checks.forEach { (cb, _) -> if (cb !== buttonView) cb.isChecked = false }
                checks[buttonView]?.let { queryDistortion = it }
            }
        }
        checks.forEach { (cb, _) -> cb.setOnCheckedChangeListener(listener) }

        binding.btnSearch.setOnClickListener {
            if (!busy && selectedKfIndex >= 0) runSearch()
        }
    }

    // -------------------------------------------------------------------------
    // 第一步：关键帧抽取 + 索引
    // -------------------------------------------------------------------------

    private fun runExtract() {
        busy = true
        binding.btnExtract.isEnabled = false
        binding.btnSearch.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress = 0
        binding.tvStatus.text = getString(R.string.cs_status_extracting)
        binding.layoutKeyframes.removeAllViews()
        binding.layoutResults.removeAllViews()
        binding.tvResultTitle.visibility = View.GONE
        selectedKfIndex = -1

        thread(start = true) {
            try {
                // 抽取 12 个关键帧并建立特征索引
                val kfs = engine.extractKeyframes(videoFile.absolutePath, KEYFRAME_COUNT)
                engine.keyframes.clear()
                engine.keyframes.addAll(kfs)

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progress.visibility = View.GONE
                    binding.btnExtract.isEnabled = true
                    busy = false
                    buildKeyframeStrip(kfs)
                    binding.btnSearch.isEnabled = kfs.isNotEmpty()
                    binding.tvStatus.text = getString(R.string.cs_status_extracted, kfs.size)
                }
            } catch (e: Exception) {
                android.util.Log.e("CS_VideoFragment", "keyframe extraction failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progress.visibility = View.GONE
                    binding.btnExtract.isEnabled = true
                    busy = false
                    binding.tvStatus.text = getString(R.string.cs_status_error, err)
                }
            }
        }
    }

    /** 构建关键帧带（点击选中查询帧）。 */
    private fun buildKeyframeStrip(kfs: List<VideoSearchEngine.Keyframe>) {
        binding.layoutKeyframes.removeAllViews()
        kfs.forEachIndexed { idx, kf ->
            val ctx = requireContext()
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, 4, 4, 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // 高亮当前选中帧
                updateStripSelection(this, idx == selectedKfIndex)
                setOnClickListener {
                    selectedKfIndex = idx
                    refreshStripSelection()
                }
            }
            val iv = ImageView(ctx).apply {
                setImageBitmap(
                    VideoSearchEngine.lumaToBitmap(kf.luma, kf.width, kf.height, 160)
                )
                layoutParams = LinearLayout.LayoutParams(148, 92)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.cs_keyframe_desc, idx + 1)
            }
            val tv = TextView(ctx).apply {
                text = getString(R.string.cs_keyframe_time, (kf.timestampUs / 1000) / 1000f)
                textSize = 10f
                setTextColor(0xFF333333.toInt())
                gravity = Gravity.CENTER
            }
            cell.addView(iv)
            cell.addView(tv)
            binding.layoutKeyframes.addView(cell)
        }
    }

    /** 刷新关键帧带选中态。 */
    private fun refreshStripSelection() {
        for (i in 0 until binding.layoutKeyframes.childCount) {
            updateStripSelection(
                binding.layoutKeyframes.getChildAt(i) as LinearLayout,
                i == selectedKfIndex
            )
        }
    }

    private fun updateStripSelection(cell: LinearLayout, selected: Boolean) {
        cell.setBackgroundResource(
            if (selected) R.color.cs_selected
            else android.R.color.transparent
        )
    }

    // -------------------------------------------------------------------------
    // 第二步：检索
    // -------------------------------------------------------------------------

    private fun runSearch() {
        val kfs = engine.keyframes
        if (selectedKfIndex < 0 || selectedKfIndex >= kfs.size) return
        val src = kfs[selectedKfIndex]

        busy = true
        binding.btnSearch.isEnabled = false
        binding.tvStatus.text = getString(R.string.cs_status_searching_video)

        val dist = queryDistortion
        thread(start = true) {
            try {
                // 生成失真查询帧（验证视频检索的抗失真能力）
                val qLuma = applyDistortion(src.luma, src.width, src.height, dist)

                // 检索 Top-K 关键帧
                val topK = engine.search(qLuma, src.width, src.height, k = 5)

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.btnSearch.isEnabled = true
                    busy = false
                    binding.tvResultTitle.visibility = View.VISIBLE
                    binding.layoutResults.removeAllViews()
                    for ((rank, m) in topK.withIndex()) {
                        binding.layoutResults.addView(buildResultRow(rank + 1, m))
                    }
                    val top = topK.firstOrNull()
                    binding.tvStatus.text = if (top != null) {
                        getString(
                            R.string.cs_status_video_hit,
                            (top.keyframe.timestampUs / 1000) / 1000f,
                            top.score
                        )
                    } else {
                        getString(R.string.cs_status_done, 0)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CS_VideoFragment", "video search failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.btnSearch.isEnabled = true
                    busy = false
                    binding.tvStatus.text = getString(R.string.cs_status_error, err)
                }
            }
        }
    }

    /** 对查询帧施加失真（亮度+40 / 高斯噪声 / 中心裁剪 85%）。 */
    private fun applyDistortion(
        luma: ByteArray, width: Int, height: Int, dist: Distortion
    ): ByteArray {
        val out = luma.copyOf()
        when (dist) {
            Distortion.NONE -> Unit
            Distortion.BRIGHT -> {
                for (i in out.indices) {
                    out[i] = ((out[i].toInt() and 0xFF) + 40).coerceIn(0, 255).toByte()
                }
            }
            Distortion.NOISE -> {
                val rnd = Random(42)
                for (i in out.indices) {
                    val n = (rnd.nextGaussian() * 20).toInt()
                    out[i] = ((out[i].toInt() and 0xFF) + n).coerceIn(0, 255).toByte()
                }
            }
            Distortion.CROP -> {
                // 中心裁剪 85% 再拉伸回原尺寸（模拟局部遮挡）
                val cw = (width * 0.85f).toInt().coerceAtLeast(1)
                val ch = (height * 0.85f).toInt().coerceAtLeast(1)
                val x0 = (width - cw) / 2
                val y0 = (height - ch) / 2
                val tmp = ByteArray(width * height)
                for (y in 0 until height) {
                    val sy = (y0 + y * ch / height).coerceAtLeast(0).coerceAtMost(height - 1)
                    for (x in 0 until width) {
                        val sx = (x0 + x * cw / width).coerceAtLeast(0).coerceAtMost(width - 1)
                        tmp[y * width + x] = out[sy * width + sx]
                    }
                }
                return tmp
            }
        }
        return out
    }

    /** 结果行：排名 + 关键帧缩略图 + 时间点 + 相似度。 */
    private fun buildResultRow(rank: Int, m: VideoSearchEngine.Match): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundResource(android.R.color.white)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
        }

        val iv = ImageView(ctx).apply {
            setImageBitmap(
                VideoSearchEngine.lumaToBitmap(m.keyframe.luma, m.keyframe.width, m.keyframe.height, 160)
            )
            layoutParams = LinearLayout.LayoutParams(148, 92)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.cs_keyframe_desc, rank)
        }

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val timeSec = (m.keyframe.timestampUs / 1000) / 1000f
            text = getString(
                R.string.cs_video_result_row,
                rank, timeSec, m.keyframe.frameIndex,
                m.score, m.colorScore, m.textureScore
            )
            textSize = 12f
            setTextColor(0xFF333333.toInt())
        }

        row.addView(iv)
        row.addView(text)
        return row
    }

    companion object {
        /** 关键帧抽取数量。 */
        private const val KEYFRAME_COUNT = 12
    }
}
