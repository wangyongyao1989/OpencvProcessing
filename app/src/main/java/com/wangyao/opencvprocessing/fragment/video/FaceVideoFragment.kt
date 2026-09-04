package com.wangyao.opencvprocessing.fragment.video

import com.wangyao.opencvprocessing.fragment.base.BaseFragment
import com.wangyao.opencvprocessing.view.FaceOverlayView

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.tabs.TabLayout
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentFaceVideoLayoutBinding
import com.wangyao.videorecognition.face.FaceTrackMath
import com.wangyao.videorecognition.face.FaceVideoAnalyzer
import com.wangyao.videorecognition.retrieval.VideoKeyframeIndexer
import java.io.File
import kotlin.concurrent.thread

/**
 * 视频识别页（《视频识别及物体人脸识别需求文档》）：
 * 对 assets/midway.mp4 的综合识别验证，右栏三页签：
 *
 * 1. 人脸识别（F-05 视频人脸检测与跟踪）：
 *    分析（后台线程）：软解逐帧 → Haar 级联人脸检测（OpenCV
 *    JNI）→ IoU 关联时间平滑 → 逐帧人脸框与全片统计；
 *    播放（UI 线程）：MediaPlayer 播放视频，人脸框叠加层按
 *    播放进度实时刷新——用户可直观看到框选随人脸移动的跟踪
 *    效果（播放/暂停/拖动进度条均保持同步）；
 *    以该帧检索视频：任意播放时刻捕获当前画面作为查询帧，
 *    复用关键帧索引按综合相似度检索相似时刻（结果附该时刻
 *    检出人脸数），点击跳转播放以检查各时刻的人脸识别效果。
 *
 * 2. 关键帧检索（基于内容的视频检索，第 10 章 10.5 节）：
 *    抽取关键帧并建立索引 → 点击关键帧作为查询帧 → 以综合
 *    相似度（0.6 颜色 + 0.4 纹理）检索 Top-5 → 点击结果跳转
 *    播放验证「相似内容定位」。
 *
 * 3. 跟踪评价（跟踪命中率）：人脸分析完成后按 IoU 关联评价
 *    逐帧跟踪质量——跟踪命中率、命中/丢失帧对、最长连续跟踪
 *    段、平均关联 IoU。
 */
class FaceVideoFragment : BaseFragment() {

    private lateinit var binding: FragmentFaceVideoLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private lateinit var videoFile: File

    /** 人脸级联模型（需求文档第九章：正脸双模型并集 + 侧脸模型）。 */
    private val faceModels = listOf(
        "haarcascade_frontalface_default.xml",
        "haarcascade_frontalface_alt2.xml",
        "haarcascade_profileface.xml"
    )

    /** 面部特征级联模型（眼/鼻/嘴，候选框验证降误检）。 */
    private val featureModels = listOf(
        "haarcascade_eye.xml",
        "haarcascade_nose.xml",
        "haarcascade_mouth.xml"
    )

    /** 分析结果（hide/show 切页后保留，无需重新分析）。 */
    private var analysis: FaceVideoAnalyzer.Result? = null
    private var analyzing = false

    // ---- 关键帧检索 ----
    private val indexer = VideoKeyframeIndexer()
    /** 用户选中的查询关键帧下标（关键帧带中），-1 未选。 */
    private var selectedKfIndex = -1
    private var kfBusy = false

    // ---- 人脸识别页签：以该帧检索视频 ----
    private var faceSearchBusy = false

    // ---- 播放器 ----
    private var player: MediaPlayer? = null
    private var playerPrepared = false
    private var wantPlay = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 播放同步循环：按当前进度刷新人脸框与进度条。 */
    private val syncRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && playerPrepared) {
                val pos = p.currentPosition
                if (!seekDragging) {
                    binding.seekBar.progress = pos
                }
                binding.tvTimeCurrent.text = formatTime(pos)
                updateFacesFor(pos)
            }
            mainHandler.postDelayed(this, 33)
        }
    }

    private var seekDragging = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentFaceVideoLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        val ctx = requireContext()
        videoFile = File(ctx.filesDir, "vr_midway.mp4")
        if (!videoFile.exists()) {
            ctx.assets.open("midway.mp4").use { input ->
                videoFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // 级联模型从 assets 拷贝到私有目录（已存在则直接复用）
        faceModelPaths = faceModels.map { copyAssetIfAbsent(it) }
        featureModelPaths = featureModels.map { copyAssetIfAbsent(it) }

        // 已有分析结果（切页返回）：直接恢复播放
        analysis?.let { onAnalysisDone(it, restored = true) }
    }

    /** 已释放到私有目录的人脸/特征模型路径（initData 填充）。 */
    private var faceModelPaths: List<String> = emptyList()
    private var featureModelPaths: List<String> = emptyList()

    /** 从 assets 拷贝模型到私有目录（已存在则直接复用），返回绝对路径。 */
    private fun copyAssetIfAbsent(name: String): String {
        val f = File(requireContext().filesDir, name)
        if (!f.exists()) {
            requireContext().assets.open(name).use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return f.absolutePath
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MAIN)
        }

        binding.btnAnalyze.setOnClickListener {
            if (!analyzing) startAnalysis()
        }

        // 人脸识别页签：以当前播放帧检索视频（供人脸识别检查）
        binding.btnFaceSearch.setOnClickListener {
            if (!faceSearchBusy) runFaceFrameSearch()
        }

        // 右栏页签切换
        binding.tabPanel.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showPanel(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.btnKfExtract.setOnClickListener {
            if (!kfBusy) runKeyframeExtract()
        }

        binding.btnKfSearch.setOnClickListener {
            if (!kfBusy) runKeyframeSearch()
        }

        binding.btnPlay.setOnClickListener {
            val p = player
            if (p == null || !playerPrepared) return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                setPlayIcon(false)
            } else {
                p.start()
                setPlayIcon(true)
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seekDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                val p = player ?: return
                if (playerPrepared) {
                    p.seekTo(sb?.progress ?: 0)
                    // 立即刷新该位置的人脸框
                    updateFacesFor(p.currentPosition)
                }
            }
        })

        // 视频画面 Surface 生命周期
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: SurfaceTexture, width: Int, height: Int
            ) {
                openPlayer(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture, width: Int, height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                pausePlayerForSurface()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    // -------------------------------------------------------------------------
    // 阶段一：人脸分析
    // -------------------------------------------------------------------------

    private fun startAnalysis() {
        analyzing = true
        binding.btnAnalyze.isEnabled = false
        binding.analyzingOverlay.visibility = View.VISIBLE
        binding.progressAnalysis.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.vr_status_analyzing)

        thread(start = true, name = "face-analyze") {
            try {
                val analyzer = FaceVideoAnalyzer()
                val result = analyzer.analyze(
                    videoPath = videoFile.absolutePath,
                    faceCascadePaths = faceModelPaths,
                    featureCascadePaths = featureModelPaths,
                    onProgress = { done, total ->
                        activity?.runOnUiThread {
                            if (isAdded) {
                                binding.progressAnalysis.progress = (done * 100L / total).toInt()
                                binding.tvAnalyzing.text =
                                    getString(R.string.vr_analyzing_progress, done, total)
                                binding.tvStatus.text =
                                    getString(R.string.vr_status_progress, done, total)
                            }
                        }
                    }
                )
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    analyzing = false
                    analysis = result
                    onAnalysisDone(result, restored = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "face analysis failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    analyzing = false
                    binding.btnAnalyze.isEnabled = true
                    binding.analyzingOverlay.visibility = View.GONE
                    binding.progressAnalysis.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.vr_status_error, err)
                }
            }
        }
    }

    /** 分析完成：显示统计并启动播放。 */
    private fun onAnalysisDone(result: FaceVideoAnalyzer.Result, restored: Boolean) {
        binding.btnAnalyze.isEnabled = false
        binding.analyzingOverlay.visibility = View.GONE
        binding.progressAnalysis.visibility = View.GONE

        // 叠加层坐标映射（工作分辨率 → 视频原始分辨率 → View）
        binding.faceOverlay.setMapping(
            result.videoWidth, result.videoHeight, result.workWidth
        )

        val r = analysis ?: result
        binding.tvStats.text = getString(
            R.string.vr_stats_done,
            r.videoWidth, r.videoHeight, r.frames.size,
            r.framesWithFaces, r.coverage * 100,
            r.avgFaces, r.maxFacesInFrame,
            r.elapsedSec
        )
        binding.tvStatus.text = getString(R.string.vr_status_done, r.maxFacesInFrame)

        // 跟踪评价面板（跟踪命中率）
        val ts = r.trackStats
        binding.tvTrackStats.text = getString(
            R.string.vr_track_stats_done,
            ts.hitRate * 100, ts.candidatePairs, ts.hitPairs,
            ts.lostPairs, ts.longestStreak, ts.avgAssociationIoU
        )

        if (!restored) {
            binding.tvStatus.text = getString(R.string.vr_status_playing)
        }
        // surface 就绪即播放
        wantPlay = true
        tryStartPlayback()
    }

    // -------------------------------------------------------------------------
    // 人脸识别页签：以该帧检索视频（供人脸识别检查分析）
    // -------------------------------------------------------------------------

    /**
     * 捕获当前播放画面作为查询帧，复用关键帧索引按综合相似度
     * 检索相似时刻（「以查询帧检索视频」）：
     * 1. UI 线程捕获 TextureView 当前帧（用户所见即所查）；
     * 2. 索引未建立时先「抽取关键帧并建立索引」（与关键帧检索
     *    页签共用同一索引，只建一次）；
     * 3. 查询帧 → 亮度平面 → 综合相似度 Top-5，结果附该时刻
     *    检出人脸数（已完成人脸分析时）；
     * 4. 点击结果行跳转播放——人脸框随即叠加，直接检查相似
     *    时刻的人脸识别效果。
     */
    private fun runFaceFrameSearch() {
        if (!playerPrepared) {
            binding.tvFaceSearchStatus.text =
                getString(R.string.vr_face_search_hint_no_player)
            return
        }
        val frame = binding.textureView.bitmap
        if (frame == null) {
            binding.tvFaceSearchStatus.text =
                getString(R.string.vr_face_search_hint_no_player)
            return
        }

        faceSearchBusy = true
        binding.btnFaceSearch.isEnabled = false
        binding.progressFaceSearch.visibility = View.VISIBLE
        binding.progressFaceSearch.progress = 0
        binding.layoutFaceResults.removeAllViews()
        binding.tvFaceResultTitle.visibility = View.GONE
        // 查询帧预览：用户所见画面
        binding.ivFaceQuery.setImageBitmap(frame)
        binding.ivFaceQuery.visibility = View.VISIBLE
        binding.tvFaceSearchStatus.text = getString(
            if (indexer.keyframes.isEmpty()) R.string.vr_face_search_extracting
            else R.string.vr_kf_searching
        )

        thread(start = true, name = "face-frame-search") {
            try {
                // 索引未建立：先抽取关键帧并建立索引（后台）
                if (indexer.keyframes.isEmpty()) {
                    val kfs = indexer.extractKeyframes(
                        videoFile.absolutePath, KEYFRAME_COUNT
                    ) { done, total ->
                        activity?.runOnUiThread {
                            if (isAdded) {
                                binding.progressFaceSearch.progress =
                                    (done * 100L / total).toInt()
                                binding.tvFaceSearchStatus.text =
                                    getString(R.string.vr_kf_progress, done, total)
                            }
                        }
                    }
                    activity?.runOnUiThread {
                        if (isAdded) {
                            // 同步关键帧检索页签：填充关键帧带
                            buildKeyframeStrip(kfs)
                            binding.tvKfStatus.text =
                                getString(R.string.vr_kf_status_extracted, kfs.size)
                        }
                    }
                }
                // 以查询帧检索视频：画面 → 亮度平面 → 综合相似度 Top-K
                val query = VideoKeyframeIndexer.bitmapToLuma(frame)
                val topK = indexer.search(
                    query.luma, query.width, query.height, k = TOP_K
                )
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    faceSearchBusy = false
                    binding.btnFaceSearch.isEnabled = true
                    binding.progressFaceSearch.visibility = View.GONE
                    binding.tvFaceResultTitle.visibility = View.VISIBLE
                    binding.layoutFaceResults.removeAllViews()
                    for ((rank, m) in topK.withIndex()) {
                        binding.layoutFaceResults.addView(
                            buildKfResultRow(
                                rank + 1, m,
                                faceCountAt(m.keyframe.timestampUs)
                            )
                        )
                    }
                    val top = topK.firstOrNull()
                    binding.tvFaceSearchStatus.text = if (top != null) {
                        getString(
                            R.string.vr_face_search_hit,
                            top.keyframe.timestampUs / 1_000_000f, top.score
                        )
                    } else {
                        getString(
                            R.string.vr_kf_status_extracted, indexer.keyframes.size
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "face frame search failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    faceSearchBusy = false
                    binding.btnFaceSearch.isEnabled = true
                    binding.progressFaceSearch.visibility = View.GONE
                    binding.tvFaceSearchStatus.text =
                        getString(R.string.vr_status_error, err)
                }
            }
        }
    }

    /**
     * 关键帧时间戳处最近分析帧的检出人脸数（检索结果辅助人脸
     * 识别检查）；未完成人脸分析时返回 -1（界面显示「未分析」）。
     */
    private fun faceCountAt(timestampUs: Long): Int {
        val r = analysis ?: return -1
        val idx = FaceTrackMath.findNearestFrameIndex(r.frames, timestampUs)
        return if (idx >= 0) r.frames[idx].faces.size else -1
    }

    // -------------------------------------------------------------------------
    // 页签切换
    // -------------------------------------------------------------------------

    /** 右栏面板切换：0=人脸识别 1=关键帧检索 2=跟踪评价。 */
    private fun showPanel(index: Int) {
        binding.panelFace.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.panelKeyframe.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.panelTrack.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------------------
    // 关键帧检索（抽取关键帧并建立索引 + 以查询帧检索视频）
    // -------------------------------------------------------------------------

    /** 第一步：抽取关键帧并建立特征索引。 */
    private fun runKeyframeExtract() {
        kfBusy = true
        binding.btnKfExtract.isEnabled = false
        binding.btnKfSearch.isEnabled = false
        binding.progressKf.visibility = View.VISIBLE
        binding.progressKf.progress = 0
        binding.tvKfStatus.text = getString(R.string.vr_kf_extracting)
        binding.layoutKeyframes.removeAllViews()
        binding.layoutKfResults.removeAllViews()
        binding.tvKfResultTitle.visibility = View.GONE
        selectedKfIndex = -1

        thread(start = true, name = "kf-extract") {
            try {
                val kfs = indexer.extractKeyframes(
                    videoFile.absolutePath, KEYFRAME_COUNT
                ) { done, total ->
                    activity?.runOnUiThread {
                        if (isAdded) {
                            binding.progressKf.progress = (done * 100L / total).toInt()
                            binding.tvKfStatus.text =
                                getString(R.string.vr_kf_progress, done, total)
                        }
                    }
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    kfBusy = false
                    binding.btnKfExtract.isEnabled = true
                    binding.progressKf.visibility = View.GONE
                    buildKeyframeStrip(kfs)
                    binding.tvKfStatus.text =
                        getString(R.string.vr_kf_status_extracted, kfs.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "keyframe extraction failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    kfBusy = false
                    binding.btnKfExtract.isEnabled = true
                    binding.progressKf.visibility = View.GONE
                    binding.tvKfStatus.text = getString(R.string.vr_status_error, err)
                }
            }
        }
    }

    /** 构建关键帧带（点击选择查询帧）。 */
    private fun buildKeyframeStrip(kfs: List<VideoKeyframeIndexer.Keyframe>) {
        binding.layoutKeyframes.removeAllViews()
        kfs.forEachIndexed { idx, kf ->
            val ctx = requireContext()
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(4, 4, 4, 4)
                updateStripSelection(this, idx == selectedKfIndex)
                setOnClickListener {
                    selectedKfIndex = idx
                    refreshStripSelection()
                    // 选中即可检索
                    binding.btnKfSearch.isEnabled = true
                }
            }
            val iv = ImageView(ctx).apply {
                setImageBitmap(
                    VideoKeyframeIndexer.lumaToBitmap(kf.luma, kf.width, kf.height, 160)
                )
                layoutParams = LinearLayout.LayoutParams(120, 68)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.vr_kf_keyframe_desc, idx + 1)
            }
            val tv = TextView(ctx).apply {
                text = getString(R.string.vr_kf_keyframe_time, kf.timestampUs / 1_000_000f)
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
            if (selected) R.color.cs_selected else android.R.color.transparent
        )
    }

    /** 第二步：以选中的关键帧为查询帧检索视频（综合相似度）。 */
    private fun runKeyframeSearch() {
        val kfs = indexer.keyframes
        if (selectedKfIndex < 0 || selectedKfIndex >= kfs.size) {
            binding.tvKfStatus.text = getString(R.string.vr_kf_hint_select)
            return
        }
        val query = kfs[selectedKfIndex]

        kfBusy = true
        binding.btnKfSearch.isEnabled = false
        binding.tvKfStatus.text = getString(R.string.vr_kf_searching)

        thread(start = true, name = "kf-search") {
            try {
                // 排除查询帧本身：展示「视频中最相似的其他内容」
                val topK = indexer.search(
                    query.luma, query.width, query.height,
                    k = TOP_K, exclude = query
                )
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    kfBusy = false
                    binding.btnKfSearch.isEnabled = true
                    binding.tvKfResultTitle.visibility = View.VISIBLE
                    binding.layoutKfResults.removeAllViews()
                    for ((rank, m) in topK.withIndex()) {
                        binding.layoutKfResults.addView(buildKfResultRow(rank + 1, m))
                    }
                    val top = topK.firstOrNull()
                    binding.tvKfStatus.text = if (top != null) {
                        getString(
                            R.string.vr_kf_status_hit,
                            top.keyframe.timestampUs / 1_000_000f, top.score
                        )
                    } else {
                        getString(R.string.vr_kf_status_extracted, kfs.size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "keyframe search failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    kfBusy = false
                    binding.btnKfSearch.isEnabled = true
                    binding.tvKfStatus.text = getString(R.string.vr_status_error, err)
                }
            }
        }
    }

    /**
     * 检索结果行：排名 + 缩略图 + 时间点 + 综合相似度及分项；
     * [faceCount] 非空时（人脸识别页签「以该帧检索视频」）追加
     * 该时刻检出人脸数，供人脸识别检查。
     */
    private fun buildKfResultRow(
        rank: Int,
        m: VideoKeyframeIndexer.Match,
        faceCount: Int? = null
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
            // 点击跳转播放：验证「相似内容定位」
            setOnClickListener { seekToUs(m.keyframe.timestampUs) }
        }

        val iv = ImageView(ctx).apply {
            setImageBitmap(
                VideoKeyframeIndexer.lumaToBitmap(
                    m.keyframe.luma, m.keyframe.width, m.keyframe.height, 160
                )
            )
            layoutParams = LinearLayout.LayoutParams(120, 68)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.vr_kf_keyframe_desc, rank)
        }

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            text = if (faceCount != null) {
                getString(
                    R.string.vr_face_result_row,
                    rank, m.keyframe.timestampUs / 1_000_000f,
                    m.score, m.colorScore, m.textureScore,
                    // 未完成人脸分析时显示「未分析」
                    if (faceCount >= 0) faceCount.toString()
                    else getString(R.string.vr_face_count_unknown)
                )
            } else {
                getString(
                    R.string.vr_kf_result_row,
                    rank, m.keyframe.timestampUs / 1_000_000f,
                    m.score, m.colorScore, m.textureScore
                )
            }
            textSize = 12f
            setTextColor(0xFF333333.toInt())
        }

        row.addView(iv)
        row.addView(text)
        return row
    }

    /** 播放器跳转到指定时间戳（µs）。 */
    private fun seekToUs(timestampUs: Long) {
        val p = player ?: return
        if (!playerPrepared) return
        val posMs = (timestampUs / 1000).toInt()
        p.seekTo(posMs)
        if (!p.isPlaying) {
            p.start()
            setPlayIcon(true)
        }
        binding.seekBar.progress = posMs
        updateFacesFor(p.currentPosition)
    }

    // -------------------------------------------------------------------------
    // 阶段二：播放 + 人脸框实时叠加
    // -------------------------------------------------------------------------

    private fun openPlayer(surface: Surface) {
        if (player != null) return
        player = MediaPlayer().apply {
            setSurface(surface)
            setDataSource(videoFile.absolutePath)
            isLooping = true
            setOnPreparedListener {
                playerPrepared = true
                binding.tvTimeTotal.text = formatTime(it.duration)
                binding.seekBar.max = it.duration
                tryStartPlayback()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "player error what=$what extra=$extra")
                binding.tvStatus.text = getString(R.string.vr_status_error, "what=$what extra=$extra")
                true
            }
            prepareAsync()
        }
    }

    private fun tryStartPlayback() {
        val p = player
        if (wantPlay && p != null && playerPrepared && !p.isPlaying) {
            p.start()
            setPlayIcon(true)
            mainHandler.removeCallbacks(syncRunnable)
            mainHandler.post(syncRunnable)
        }
    }

    /** surface 销毁（页面被隐藏）：暂停播放，等待恢复。 */
    private fun pausePlayerForSurface() {
        player?.let { p ->
            wantPlay = playerPrepared && p.isPlaying
            if (p.isPlaying) p.pause()
        }
    }

    /** 按播放进度（毫秒）查找最近分析帧并刷新人脸框。 */
    private fun updateFacesFor(posMs: Int) {
        val r = analysis ?: return
        val best = FaceTrackMath.findNearestFrameIndex(r.frames, posMs * 1000L)
        if (best < 0) return
        binding.faceOverlay.setFaces(r.frames[best].faces)
    }

    private fun setPlayIcon(playing: Boolean) {
        binding.btnPlay.setIconResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(syncRunnable)
        player?.let { p ->
            try { p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        player = null
        playerPrepared = false
    }

    companion object {
        private const val TAG = "VR_Fragment"

        /** 关键帧抽取数量（与 contentsearch 模块一致的检索规模）。 */
        private const val KEYFRAME_COUNT = 12

        /** 检索返回 Top-K。 */
        private const val TOP_K = 5
    }
}
