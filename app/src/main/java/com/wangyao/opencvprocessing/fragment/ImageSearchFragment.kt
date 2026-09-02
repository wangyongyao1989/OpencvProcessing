package com.wangyao.opencvprocessing.fragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.wangyao.opencvprocessing.databinding.FragmentCsImageLayoutBinding
import com.wangyao.contentsearch.core.ImageSearchEngine
import kotlin.concurrent.thread

/**
 * 三级页：基于内容的图像检索 CBIR（《数字图像与视频处理》
 * 第 10 章 10.2~10.4 节）。
 *
 * 对 assets/IMG_20260821_190758.jpg 的完整 CBIR 流程：
 * 1. 建库：由原图生成光度/几何变换副本 + 合成干扰图（12 幅）；
 * 2. 索引：对库内全部图像提取「HSV 颜色直方图 + 梯度方向纹理
 *    直方图」特征（式 10-1）；
 * 3. 检索：选择查询类型（原图或其变换副本），提取查询特征，
 *    与库内特征逐一计算综合相似度（式 10-2~10-4 加权融合），
 *    按相似度降序返回 Top-K；
 * 4. 展示：结果列表含缩略图、名称、综合/颜色/纹理分量相似度，
 *    直观验证「同内容图像排在最前、干扰图靠后」以及特征对
 *    旋转/镜像/光照变化的不变性。
 */
class ImageSearchFragment : BaseFragment() {

    private lateinit var binding: FragmentCsImageLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var queryType = ImageSearchEngine.QueryType.ORIGINAL
    private var busy = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCsImageLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        // 降采样解码素材图（最长边 ≤ 1024，控制建库耗时）
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
        }
        // 预览查询图（默认原图）
        originalBitmap?.let { binding.ivQuery.setImageBitmap(it) }
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.CONTENT_SEARCH)
        }

        // 查询类型单选：选中一项时取消其余，并刷新查询预览
        val checks = mapOf(
            binding.cbOriginal to ImageSearchEngine.QueryType.ORIGINAL,
            binding.cbBright to ImageSearchEngine.QueryType.BRIGHT,
            binding.cbFlip to ImageSearchEngine.QueryType.FLIP,
            binding.cbRotate to ImageSearchEngine.QueryType.ROTATE,
            binding.cbNoise to ImageSearchEngine.QueryType.NOISE,
            binding.cbScale to ImageSearchEngine.QueryType.SCALE
        )
        val checkListener = CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                checks.forEach { (cb, _) -> if (cb !== buttonView) cb.isChecked = false }
                checks[buttonView]?.let { queryType = it }
                updateQueryPreview()
            }
        }
        checks.forEach { (cb, _) -> cb.setOnCheckedChangeListener(checkListener) }

        binding.btnSearch.setOnClickListener {
            if (!busy) runSearch()
        }
    }

    /** 刷新查询图预览。 */
    private fun updateQueryPreview() {
        val src = originalBitmap ?: return
        val engine = ImageSearchEngine()
        binding.ivQuery.setImageBitmap(engine.buildQuery(src, queryType))
    }

    // -------------------------------------------------------------------------
    // 检索流程
    // -------------------------------------------------------------------------

    private fun runSearch() {
        val src = originalBitmap ?: return
        busy = true
        binding.btnSearch.isEnabled = false
        binding.tvStatus.text = getString(R.string.cs_status_indexing)
        binding.layoutResults.removeAllViews()
        binding.tvResultTitle.visibility = View.GONE

        val type = queryType
        thread(start = true) {
            val engine = ImageSearchEngine()

            // 1. 建库 + 索引（离线阶段，式 10-1）
            val dbSize = engine.buildDatabase(src)
            activity?.runOnUiThread {
                if (isAdded) binding.tvStatus.text =
                    getString(R.string.cs_status_searching, dbSize)
            }

            // 2. 生成查询图 + 检索（在线阶段，式 10-2~10-4）
            val query = engine.buildQuery(src, type)
            val topK = engine.search(query, k = 6)

            // 3. 结果展示
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.btnSearch.isEnabled = true
                busy = false
                binding.tvResultTitle.visibility = View.VISIBLE
                binding.layoutResults.removeAllViews()
                for ((rank, m) in topK.withIndex()) {
                    binding.layoutResults.addView(
                        buildResultRow(rank + 1, m)
                    )
                }
                binding.tvStatus.text = getString(R.string.cs_status_done, topK.size)
            }
        }
    }

    /** 构造单条结果行：缩略图 + 名称 + 相似度。 */
    private fun buildResultRow(
        rank: Int,
        m: ImageSearchEngine.Match
    ): View {
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

        val thumb = ImageView(ctx).apply {
            m.entry.bitmap.let { bmp ->
                // 缩略图统一宽度
                setImageBitmap(
                    Bitmap.createScaledBitmap(bmp, 96, 96 * bmp.height / bmp.width, true)
                )
            }
            adjustViewBounds = false
            scaleX = 1f
            layoutParams = LinearLayout.LayoutParams(110, 78).apply {
                marginEnd = 12
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = getString(
                R.string.cs_result_row,
                rank,
                m.entry.label,
                m.score,
                m.colorScore,
                m.textureScore
            )
            textSize = 12f
            setTextColor(0xFF333333.toInt())
        }

        row.addView(thumb)
        row.addView(text)
        return row
    }

    companion object {
        private const val ASSET_IMAGE = "IMG_20260821_190758.jpg"
        private const val MAX_DIM = 1024
    }
}
