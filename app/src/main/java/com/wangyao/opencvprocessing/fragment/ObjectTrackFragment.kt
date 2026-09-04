package com.wangyao.opencvprocessing.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.wangyao.camerarecognition.camera.CameraHelper
import com.wangyao.camerarecognition.jni.ObjectTrackJni
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentObjectTrackLayoutBinding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 相机识别·框选实物实时检测跟踪页（以 videorecognition 模块
 * 「以该帧检索视频」的检索代码流程为基础）：
 *
 * 1. [CameraHelper] 以 NV21 格式输出 640×480 预览帧；
 * 2. 手势框选：在预览画面按住拖动框选实物（[ObjectSelectView]，
 *    松手回调 View 坐标选框，换算为图像坐标后作为检索的「查询」）；
 * 3. 每帧送 native（ObjectTracker.cpp）：NV21→RGBA→方向校正→
 *    下一帧提取目标模板（与视频检索同一特征空间：64 维亮度
 *    直方图 + Sobel 方向直方图）→ CamShift 反向投影定位候选 →
 *    综合相似度（0.6·直方图相交 + 0.4·余弦）验证 → 绿框与相似度
 *    绘制 → ANativeWindow 渲染；
 * 4. 界面实时显示跟踪状态与综合相似度；目标丢失后重新框选即可。
 *
 * 右栏为原理（与检索流程的对应关系）与操作说明面板。
 */
class ObjectTrackFragment : BaseFragment() {

    private lateinit var binding: FragmentObjectTrackLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    /** native 实物跟踪器句柄（onDestroy 释放）。 */
    private var trackerHandle = 0L

    private var cameraHelper: CameraHelper? = null
    private var previewing = false
    private var surfaceReady = false

    /** 离开页面时是否正在预览（返回本页自动恢复）。 */
    private var autoResumePreview = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 上次 UI 刷新的状态与时间（状态变化立即刷，相似度限频刷）。 */
    private var lastState = -1
    private var lastUiUpdate = 0L

    /** 目标核验缩略图（框选模板 / 实时跟踪框内画面）。 */
    private var templateBitmap: Bitmap? = null
    private var trackedBitmap: Bitmap? = null

    /** 相机权限请求（首次打开预览时触发）。 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPreviewInternal()
        } else {
            binding.tvOtStatus.text = getString(R.string.cr_status_no_permission)
        }
    }

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentObjectTrackLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        // Surface 生命周期：创建时绑定 native 渲染目标，销毁时解绑
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (trackerHandle != 0L) {
                    ObjectTrackJni.nativeSetSurface(trackerHandle, holder.surface)
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                if (trackerHandle != 0L) {
                    ObjectTrackJni.nativeSetSurface(trackerHandle, null)
                }
            }
        })

        // 手势框选：松手后把 View 坐标选框换算为图像坐标送 native
        // （下一帧到达时提取目标模板，与检索「捕获查询帧」同源）
        binding.objectSelectView.onSelect = { rect -> onObjectSelected(rect) }

        // 预览区域默认按 640×480（4:3）比例居中，避免拉伸变形
        resizePreviewWrapper(CameraHelper.WIDTH, CameraHelper.HEIGHT)
    }

    override fun initData() {
        if (trackerHandle == 0L) {
            trackerHandle = ObjectTrackJni.nativeCreate()
        }
        if (trackerHandle == 0L) {
            binding.tvOtStatus.text = getString(R.string.cr_status_init_error)
        }
        binding.tvOtVersion.text =
            getString(R.string.ot_version_info, ObjectTrackJni.nativeOpencvVersion())
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
        // 离开本页（hide/show 切换）时释放摄像头
        ffViewModel.switchFragment.observe(this) { status ->
            if (status != null && status != FFViewModel.FRAGMENT_STATUS.CAMERA_OBJECT_TRACK
                && previewing
            ) {
                autoResumePreview = false
                stopPreviewInternal()
            }
        }
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.CAMERA_MENU)
        }

        binding.btnTogglePreview.setOnClickListener {
            if (previewing) stopPreview() else startPreview()
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (!previewing) return@setOnClickListener
            cameraHelper?.switchCamera()
            // 旧目标模板对新摄像头画面无效，清除后重新框选
            if (trackerHandle != 0L) {
                ObjectTrackJni.nativeResetTracking(trackerHandle)
            }
            lastState = -1
            binding.tvOtSim.text = ""
        }

        binding.btnResetTrack.setOnClickListener {
            if (trackerHandle != 0L) {
                ObjectTrackJni.nativeResetTracking(trackerHandle)
            }
            lastState = -1
            binding.tvOtSim.text = ""
            if (previewing) {
                binding.tvOtStatus.text = getString(R.string.ot_status_previewing, cameraName())
            }
        }
    }

    /** 打开预览（权限检查 → 启动相机 → native 开始收帧）。 */
    private fun startPreview() {
        if (previewing) return
        if (trackerHandle == 0L) {
            binding.tvOtStatus.text = getString(R.string.cr_status_init_error)
            return
        }
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        startPreviewInternal()
    }

    private fun startPreviewInternal() {
        if (previewing) return
        cameraHelper = CameraHelper().apply {
            previewCallback = { data -> onPreviewFrame(data) }
            startPreview()
        }
        previewing = true
        binding.btnTogglePreview.text = getString(R.string.cr_btn_close_preview)
        // 按当前校正角重算画面比例（横屏后置为 0°：640×480）
        val rotation = frameRotationDegrees(cameraHelper!!)
        if (rotation == 90 || rotation == 270) {
            resizePreviewWrapper(CameraHelper.HEIGHT, CameraHelper.WIDTH)
        } else {
            resizePreviewWrapper(CameraHelper.WIDTH, CameraHelper.HEIGHT)
        }
        lastState = -1
        binding.tvOtStatus.text = getString(R.string.ot_status_previewing, cameraName())
    }

    /** 关闭预览并释放摄像头。 */
    private fun stopPreview() {
        stopPreviewInternal()
    }

    private fun stopPreviewInternal() {
        previewing = false
        cameraHelper?.stopPreview()
        cameraHelper = null
        binding.btnTogglePreview.text = getString(R.string.cr_btn_open_preview)
        binding.tvOtSim.text = ""
        lastState = -1
        binding.tvOtStatus.text = getString(R.string.cr_status_stopped)
        clearVerifyThumbs("preview stopped")
    }

    /**
     * 手势框选回调：View 坐标选框 → 图像坐标 ROI → native 记为
     * 「查询」，下一帧提取目标模板并开始跟踪。
     */
    private fun onObjectSelected(rect: RectF) {
        if (!previewing || trackerHandle == 0L) return
        val helper = cameraHelper ?: return
        val rotation = frameRotationDegrees(helper)
        val imgW: Int
        val imgH: Int
        if (rotation == 90 || rotation == 270) {
            imgW = CameraHelper.HEIGHT
            imgH = CameraHelper.WIDTH
        } else {
            imgW = CameraHelper.WIDTH
            imgH = CameraHelper.HEIGHT
        }
        val vw = binding.objectSelectView.width.toFloat()
        val vh = binding.objectSelectView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // 线性换算（叠加层与渲染画面同区域、同比例）
        val x = (rect.left / vw * imgW).roundToInt()
        val y = (rect.top / vh * imgH).roundToInt()
        val w = (rect.width() / vw * imgW).roundToInt()
        val h = (rect.height() / vh * imgH).roundToInt()
        Log.d(TAG, "onObjectSelected: viewRect=(${rect.left.toInt()}," +
                "${rect.top.toInt()},${rect.right.toInt()}," +
                "${rect.bottom.toInt()}) view=${vw.toInt()}x${vh.toInt()} " +
                "→ imageRoi=[$x,$y ${w}x$h] img=${imgW}x$imgH " +
                "rotation=$rotation")
        ObjectTrackJni.nativeSelectObject(trackerHandle, x, y, w, h)
        lastState = -1
        binding.tvOtStatus.text = getString(R.string.ot_status_selecting)
    }

    /**
     * 预览帧回调（相机线程）：整帧处理（方向校正 + CamShift 跟踪 +
     * 相似度验证 + 渲染）在 native 完成，此处仅回传数据并刷新状态。
     */
    private fun onPreviewFrame(data: ByteArray) {
        if (!previewing || trackerHandle == 0L) return
        val helper = cameraHelper ?: return

        val mirror = helper.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        val result = ObjectTrackJni.nativePostFrame(
            trackerHandle, data,
            CameraHelper.WIDTH, CameraHelper.HEIGHT,
            frameRotationDegrees(helper), mirror
        )
        if (result.size < 6) return
        val state = result[0].toInt()
        val sim = result[5]

        // 状态迁移立即打日志：跟踪框（自动跟踪的 Object）坐标与
        // 相似度一并列出——与「框选的 Object」日志（imageRoi）同
        // 坐标系，logcat 中可对照三者一致性与跟踪是否漂移
        if (state != lastState) {
            Log.d(TAG, "state ${stateName(lastState)} -> " +
                    "${stateName(state)}: sim=${"%.3f".format(sim)} " +
                    "box=[${result[1].toInt()},${result[2].toInt()} " +
                    "${result[3].toInt()}x${result[4].toInt()}]")
        }

        val now = SystemClock.elapsedRealtime()
        if (state != lastState || now - lastUiUpdate > 500) {
            lastState = state
            lastUiUpdate = now
            mainHandler.post { if (previewing) updateStatus(state, sim) }
        }
    }

    /** 按跟踪状态刷新右栏状态与相似度文本。 */
    private fun updateStatus(state: Int, sim: Float) {
        when (state) {
            STATE_TRACKING -> {
                binding.tvOtStatus.text =
                    getString(R.string.ot_status_tracking, cameraName(), sim * 100f)
                binding.tvOtSim.text = getString(R.string.ot_sim, sim * 100f)
                refreshVerifyThumbs()
            }
            STATE_LOST -> {
                binding.tvOtStatus.text = getString(R.string.ot_status_lost)
                binding.tvOtSim.text = ""
                // 保留模板缩略图便于对照，仅清空跟踪缩略图
                trackedBitmap = null
                binding.ivOtTrackedThumb.setImageDrawable(null)
            }
            STATE_ARMED ->
                binding.tvOtStatus.text = getString(R.string.ot_status_selecting)
            else -> {
                binding.tvOtStatus.text = getString(R.string.ot_status_previewing, cameraName())
                binding.tvOtSim.text = ""
            }
        }
    }

    /**
     * 刷新右栏「目标一致性核验」缩略图：框选模板（框选的 Object）
     * 与实时跟踪框内画面（视频中的 Object），供人工对照验证
     * 「框选的 / 视频中的 / 自动跟踪的」三者是否同一实物。
     */
    private fun refreshVerifyThumbs() {
        if (trackerHandle == 0L) return
        if (templateBitmap == null) {
            decodeThumb(ObjectTrackJni.nativeGetTemplateThumb(trackerHandle))
                ?.let { bmp ->
                    templateBitmap = bmp
                    binding.ivOtTemplateThumb.setImageBitmap(bmp)
                    Log.d(TAG, "template thumb shown: ${bmp.width}x${bmp.height}")
                }
        }
        decodeThumb(ObjectTrackJni.nativeGetTrackedThumb(trackerHandle))
            ?.let { bmp ->
                trackedBitmap = bmp
                binding.ivOtTrackedThumb.setImageBitmap(bmp)
            }
    }

    /** 解析 native 导出的缩略图 [w(4B)][h(4B)][RGBA]（小端）。 */
    private fun decodeThumb(data: ByteArray): Bitmap? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val w = buf.int
        val h = buf.int
        if (w <= 0 || h <= 0 || buf.remaining() < w * h * 4) return null
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(buf)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeThumb failed: ${e.message}")
            null
        }
    }

    /** 清空目标核验缩略图（重新框选 / 切换摄像头 / 关闭预览后）。 */
    private fun clearVerifyThumbs(reason: String) {
        templateBitmap = null
        trackedBitmap = null
        binding.ivOtTemplateThumb.setImageDrawable(null)
        binding.ivOtTrackedThumb.setImageDrawable(null)
        Log.d(TAG, "verify thumbs cleared: $reason")
    }

    /** 预览 wrapper 按画面宽高比居中缩放（避免拉伸变形）。 */
    private fun resizePreviewWrapper(imgW: Int, imgH: Int) {
        binding.previewContainer.post {
            val w = binding.previewContainer.width
            val h = binding.previewContainer.height
            if (w <= 0 || h <= 0) return@post
            val targetW: Int
            val targetH: Int
            if (w.toLong() * imgH <= h.toLong() * imgW) {
                targetW = w
                targetH = (w.toLong() * imgH / imgW).toInt()
            } else {
                targetH = h
                targetW = (h.toLong() * imgW / imgH).toInt()
            }
            binding.previewWrapper.layoutParams =
                (binding.previewWrapper.layoutParams as FrameLayout.LayoutParams).apply {
                    width = targetW
                    height = targetH
                    gravity = Gravity.CENTER
                }
        }
    }

    private fun cameraName(): String {
        return if (cameraHelper?.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            getString(R.string.cr_camera_front)
        } else {
            getString(R.string.cr_camera_back)
        }
    }

    /**
     * 计算画面顺时针校正角：传感器方向相对显示方向的差值
     * （与 CameraRecognitionFragment 同一规则）。
     */
    private fun frameRotationDegrees(helper: CameraHelper): Int {
        val d = when (displayRotation()) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (helper.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (helper.sensorOrientation - d + 540) % 360
        } else {
            (helper.sensorOrientation - d + 360) % 360
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context?.display?.rotation ?: Surface.ROTATION_0
        } else {
            requireActivity().windowManager.defaultDisplay.rotation
        }
    }

    override fun onResume() {
        super.onResume()
        if (autoResumePreview && surfaceReady && trackerHandle != 0L) {
            autoResumePreview = false
            startPreviewInternal()
        }
    }

    override fun onPause() {
        super.onPause()
        if (previewing) {
            autoResumePreview = true
            stopPreviewInternal()
        }
    }

    override fun onDestroy() {
        if (previewing) {
            cameraHelper?.stopPreview()
            cameraHelper = null
            previewing = false
        }
        if (trackerHandle != 0L) {
            ObjectTrackJni.nativeDestroy(trackerHandle)
            trackerHandle = 0
        }
        super.onDestroy()
    }

    private companion object {
        /** 日志 tag（与 native 侧 CR_ObjectTracker 配对过滤）。 */
        const val TAG = "CR_ObjectTrack"

        /** native 返回的跟踪状态（ObjectTracker.cpp TrackState）。 */
        const val STATE_IDLE = 0
        const val STATE_ARMED = 1
        const val STATE_TRACKING = 2
        const val STATE_LOST = 3
    }

    /** 跟踪状态枚举名（日志可读性）。 */
    private fun stateName(state: Int): String = when (state) {
        STATE_IDLE -> "IDLE"
        STATE_ARMED -> "ARMED"
        STATE_TRACKING -> "TRACKING"
        STATE_LOST -> "LOST"
        else -> "UNKNOWN($state)"
    }
}
