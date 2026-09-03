package com.wangyao.opencvprocessing.fragment

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentFaceVideoLayoutBinding
import com.wangyao.videorecognition.face.FaceTrackMath
import com.wangyao.videorecognition.face.FaceVideoAnalyzer
import java.io.File
import kotlin.concurrent.thread

/**
 * 视频识别页（《视频识别及物体人脸识别需求文档》F-05：
 * 视频人脸检测与跟踪）：对 assets/midway.mp4 的人脸识别验证。
 *
 * 两个阶段：
 * 1. 分析（后台线程）：软解逐帧 → Haar 级联人脸检测（OpenCV
 *    JNI）→ IoU 关联时间平滑 → 逐帧人脸框与全片统计；
 * 2. 播放（UI 线程）：MediaPlayer 播放视频，人脸框叠加层按
 *    播放进度实时刷新——用户可直观看到框选随人脸移动的跟踪
 *    效果（播放/暂停/拖动进度条均保持同步）。
 */
class FaceVideoFragment : BaseFragment() {

    private lateinit var binding: FragmentFaceVideoLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private lateinit var videoFile: File
    private lateinit var cascadeFile: File

    /** 分析结果（hide/show 切页后保留，无需重新分析）。 */
    private var analysis: FaceVideoAnalyzer.Result? = null
    private var analyzing = false

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
        cascadeFile = File(ctx.filesDir, "haarcascade_frontalface_alt2.xml")
        if (!videoFile.exists()) {
            ctx.assets.open("midway.mp4").use { input ->
                videoFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!cascadeFile.exists()) {
            ctx.assets.open("haarcascade_frontalface_alt2.xml").use { input ->
                cascadeFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        // 已有分析结果（切页返回）：直接恢复播放
        analysis?.let { onAnalysisDone(it, restored = true) }
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MAIN)
        }

        binding.btnAnalyze.setOnClickListener {
            if (!analyzing) startAnalysis()
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
                    cascadePath = cascadeFile.absolutePath,
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
                Log.e("VR_Fragment", "face analysis failed", e)
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

        if (!restored) {
            binding.tvStatus.text = getString(R.string.vr_status_playing)
        }
        // surface 就绪即播放
        wantPlay = true
        tryStartPlayback()
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
                Log.e("VR_Fragment", "player error what=$what extra=$extra")
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
}
