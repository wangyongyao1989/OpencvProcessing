package com.wangyao.opencvprocessing.fragment.video

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentIrVideoLayoutBinding
import com.wangyao.imagerecognition.video.VideoRecognitionPipeline
import java.io.File
import kotlin.concurrent.thread

/**
 * 三级页：视频的图像识别处理（《数字图像与视频处理》第 11 章
 * 图像识别在视频中的应用：目标跟踪与运动检测）。
 *
 * 对 assets/video.mp4 的逐帧识别：
 * 1. 模板跟踪：首帧中央区域为模板，逐帧在上一位置邻域内做 NCC
 *    搜索（式 11-1），输出目标轨迹——模板匹配的时序扩展；
 * 2. 帧差运动检测：相邻帧差分二值化（阈值 25），统计运动像素
 *    占比与外接框。
 *
 * 结果可视化：
 * - 识别曲线图：跟踪得分（蓝）与运动强度（红）随帧号变化；
 * - 统计摘要：帧数、平均跟踪得分、跟踪命中率（得分 ≥ 0.5 的
 *   帧占比）、运动剧烈帧数、平均运动占比。
 */
class VideoRecognitionFragment : BaseFragment() {

    private lateinit var binding: FragmentIrVideoLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private lateinit var videoFile: File
    private var busy = false

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentIrVideoLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        videoFile = File(requireContext().filesDir, "ir_video.mp4")
        if (!videoFile.exists()) {
            requireContext().assets.open("video.mp4").use { input ->
                videoFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_RECOGNITION)
        }

        binding.btnRecognize.setOnClickListener {
            if (!busy) runRecognize()
        }
    }

    // -------------------------------------------------------------------------
    // 识别流程
    // -------------------------------------------------------------------------

    private fun runRecognize() {
        busy = true
        binding.btnRecognize.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress = 0
        binding.tvStatus.text = getString(R.string.ir_video_status_running)

        thread(start = true) {
            try {
                val pipeline = VideoRecognitionPipeline()
                val t0 = System.currentTimeMillis()
                val (results, summary) = pipeline.recognize(
                    videoFile.absolutePath,
                    onProgress = { done, total ->
                        activity?.runOnUiThread {
                            if (isAdded) {
                                binding.progress.progress = (done * 100L / total).toInt()
                                binding.tvStatus.text =
                                    getString(R.string.ir_video_status_progress, done, total)
                            }
                        }
                    }
                )
                val elapsed = (System.currentTimeMillis() - t0) / 1000.0

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progress.visibility = View.GONE
                    binding.btnRecognize.isEnabled = true
                    busy = false

                    // 曲线图
                    binding.ivChart.setImageBitmap(
                        buildChart(results.map { it.trackScore },
                            results.map { it.motionRatio })
                    )

                    // 统计摘要
                    val timeSec = results.lastOrNull()?.timestampUs?.let { it / 1_000_000.0 } ?: 0.0
                    binding.tvSummary.text = getString(
                        R.string.ir_video_summary_done,
                        summary.frames, timeSec, elapsed,
                        summary.avgTrackScore, summary.trackRate, summary.lostFrames,
                        summary.activeFrames, summary.avgMotionRatio
                    )
                    binding.tvStatus.text = getString(R.string.ir_video_status_done)
                }
            } catch (e: Exception) {
                android.util.Log.e("IR_VideoFragment", "recognition failed", e)
                val err = "${e.javaClass.simpleName}: ${e.message}"
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    binding.progress.visibility = View.GONE
                    binding.btnRecognize.isEnabled = true
                    busy = false
                    binding.tvStatus.text = getString(R.string.ir_video_status_error, err)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 可视化
    // -------------------------------------------------------------------------

    /**
     * 识别曲线图：X=帧序号，蓝=跟踪得分 [-1,1]，红=运动占比 [0,1]。
     * 含网格、基线与图例。
     */
    private fun buildChart(trackScores: List<Double>, motionRatios: List<Double>): Bitmap {
        val w = 900
        val h = 420
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val padL = 60f
        val padR = 20f
        val padT = 20f
        val padB = 40f
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        // 网格与坐标轴
        val grid = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        for (i in 0..4) {
            val y = padT + plotH * i / 4f
            canvas.drawLine(padL, y, w - padR, y, grid)
        }
        val axis = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 2f
        }
        canvas.drawLine(padL, padT, padL, padT + plotH, axis)
        canvas.drawLine(padL, padT + plotH, w - padR, padT + plotH, axis)

        // 刻度文字
        val tick = Paint().apply {
            color = Color.DKGRAY
            textSize = 22f
        }
        canvas.drawText("+1.0", 8f, padT + 8f, tick)
        canvas.drawText("0", 8f, padT + plotH / 2 + 8f, tick)
        canvas.drawText("-1.0", 8f, padT + plotH + 4f, tick)

        val n = trackScores.size
        if (n < 2) return bmp
        fun xAt(i: Int) = padL + plotW * i / (n - 1).toFloat()
        // 跟踪得分 [-1,1] → y
        fun yScore(v: Double) = padT + plotH * (1 - (v + 1) / 2.0).toFloat()
        // 运动占比 [0,1] → y（画在上半区）
        fun yMotion(v: Double) = padT + plotH * (1 - v / 2.0).toFloat()

        // 0 分基线（跟踪丢失阈值辅助线）
        val base = Paint().apply {
            color = Color.GRAY
            strokeWidth = 1f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        canvas.drawLine(padL, yScore(0.5), w - padR, yScore(0.5), base)

        // 运动占比曲线（红）
        val motionPaint = Paint().apply {
            color = Color.rgb(220, 60, 60)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val motionPath = Path()
        for (i in motionRatios.indices) {
            val (x, y) = xAt(i) to yMotion(motionRatios[i])
            if (i == 0) motionPath.moveTo(x, y) else motionPath.lineTo(x, y)
        }
        canvas.drawPath(motionPath, motionPaint)

        // 跟踪得分曲线（蓝）
        val trackPaint = Paint().apply {
            color = Color.rgb(30, 100, 220)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val trackPath = Path()
        for (i in trackScores.indices) {
            val (x, y) = xAt(i) to yScore(trackScores[i])
            if (i == 0) trackPath.moveTo(x, y) else trackPath.lineTo(x, y)
        }
        canvas.drawPath(trackPath, trackPaint)

        // 图例
        val legend = Paint().apply {
            textSize = 24f
            isAntiAlias = true
        }
        legend.color = Color.rgb(30, 100, 220)
        canvas.drawText(getString(R.string.ir_chart_track), padL + 10f, padT + 26f, legend)
        legend.color = Color.rgb(220, 60, 60)
        canvas.drawText(getString(R.string.ir_chart_motion), padL + 10f, padT + 56f, legend)
        legend.color = Color.GRAY
        canvas.drawText(getString(R.string.ir_chart_lost_line), padL + 10f, padT + 86f, legend)

        return bmp
    }
}
