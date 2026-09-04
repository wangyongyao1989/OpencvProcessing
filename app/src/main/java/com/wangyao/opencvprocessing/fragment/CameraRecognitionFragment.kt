package com.wangyao.opencvprocessing.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.wangyao.camerarecognition.jni.CameraFaceJni
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentCameraRecognitionLayoutBinding
import java.io.File

/**
 * 相机识别页（移植自 ManiiuFace 工程 + 需求文档第九章多级联扩展）：
 *
 * 打开相机预览后实时人脸检测与跟踪：
 * 1. [CameraHelper]（ManiiuFace CameraHelper 的 Kotlin 版）以 NV21
 *    格式输出 640×480 预览帧（setPreviewCallbackWithBuffer 缓冲区复用）；
 * 2. 每帧送 native（CameraFaceDetector.cpp）：NV21→RGBA→方向校正→
 *    CLAHE 灰度增强→多级联融合检测（正脸 default/alt2 并集 + 侧脸
 *    profileface 镜像补扫 + 眼/鼻/嘴特征验证）→DetectionBasedTracker
 *    跟踪→红框绘制→ANativeWindow 渲染到 SurfaceView；
 * 3. 界面实时显示当前跟踪到的人脸数，可随时切换前后摄像头
 *    （切换后跟踪状态自动重置）。
 *
 * 右栏为人脸识别基本原理与操作说明面板。
 */
class CameraRecognitionFragment : BaseFragment() {

    private lateinit var binding: FragmentCameraRecognitionLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    /** 人脸级联模型（需求文档第九章：正脸双模型并集 + 侧脸模型）。 */
    private val faceModels = listOf(
        "haarcascade_frontalface_default.xml",
        "haarcascade_frontalface_alt2.xml",
        "haarcascade_profileface.xml"
    )

    /**
     * 面部特征级联模型（眼睛/鼻子/嘴巴，候选框验证降误检）。
     *
     * 注意：文档第九章的 mcs_nose/mcs_mouth 为 OpenCV 2 时代旧格式，
     * 其特征矩形超出训练窗口，OpenCV 4 严格校验下加载即抛异常
     * （线上崩溃根因），故替换为经验证可正常加载的等价模型；
     * 去掉 eye_tree_eyeglasses 以满足文档「模型合计 < 5MB」要求。
     */
    private val featureModels = listOf(
        "haarcascade_eye.xml",
        "haarcascade_nose.xml",
        "haarcascade_mouth.xml"
    )

    /** 旧版不兼容模型（曾在设备上拷贝过，加载会抛异常，需清理）。 */
    private val legacyBrokenModels = listOf(
        "haarcascade_mcs_nose.xml",
        "haarcascade_mcs_mouth.xml"
    )

    /** native 检测跟踪器句柄（onDestroy 释放）。 */
    private var trackerHandle = 0L

    private var cameraHelper: CameraHelper? = null
    private var previewing = false
    private var surfaceReady = false

    /** 离开页面时是否正在预览（返回本页自动恢复）。 */
    private var autoResumePreview = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 人脸数 UI 刷新限频（避免每帧刷新文本）。 */
    private var lastFaceCount = -1
    private var lastUiUpdate = 0L

    /** 相机权限请求（首次打开预览时触发）。 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPreviewInternal()
        } else {
            binding.tvCrStatus.text = getString(R.string.cr_status_no_permission)
        }
    }

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentCameraRecognitionLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        // Surface 生命周期：创建时绑定 native 渲染目标，销毁时解绑
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (trackerHandle != 0L) {
                    CameraFaceJni.nativeSetSurface(trackerHandle, holder.surface)
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                if (trackerHandle != 0L) {
                    CameraFaceJni.nativeSetSurface(trackerHandle, null)
                }
            }
        })

        // 预览画面保持 4:3（640×480）比例居中，避免拉伸变形
        binding.previewContainer.post {
            val w = binding.previewContainer.width
            val h = binding.previewContainer.height
            if (w > 0 && h > 0) {
                val targetW: Int
                val targetH: Int
                if (w * 3f / 4f <= h) {
                    targetW = w
                    targetH = (w * 3f / 4f).toInt()
                } else {
                    targetH = h
                    targetW = (h * 4f / 3f).toInt()
                }
                binding.surfaceView.layoutParams =
                    (binding.surfaceView.layoutParams as FrameLayout.LayoutParams).apply {
                        width = targetW
                        height = targetH
                        gravity = Gravity.CENTER
                    }
            }
        }
    }

    override fun initData() {
        // 级联模型从 assets 拷贝到私有目录（native 侧按文件路径加载；
        // 首次拷贝后缓存路径，后续直接使用——需求文档第九章加载流程）
        if (trackerHandle == 0L) {
            // 清理旧版不兼容模型（OpenCV 4 加载即异常的历史文件）
            legacyBrokenModels.forEach {
                File(requireContext().filesDir, it).delete()
            }
            val facePaths = faceModels.map { copyAssetIfAbsent(it) }
            val featurePaths = featureModels.map { copyAssetIfAbsent(it) }
            trackerHandle = CameraFaceJni.nativeCreate(
                facePaths.toTypedArray(), featurePaths.toTypedArray()
            )
        }
        if (trackerHandle == 0L) {
            binding.tvCrStatus.text = getString(R.string.cr_status_init_error)
        }
        binding.tvCrVersion.text =
            getString(R.string.cr_opencv_version, CameraFaceJni.nativeOpencvVersion())
    }

    /** 从 assets 拷贝模型到私有目录（已存在则直接复用），返回绝对路径。 */
    private fun copyAssetIfAbsent(name: String): String {
        val f = File(requireContext().filesDir, name)
        if (!f.exists()) {
            requireContext().assets.open(name).use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return f.absolutePath
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
        // 离开本页（hide/show 切换）时释放摄像头
        ffViewModel.switchFragment.observe(this) { status ->
            if (status != null && status != FFViewModel.FRAGMENT_STATUS.CAMERA_RECOGNITION
                && previewing
            ) {
                autoResumePreview = false
                stopPreviewInternal()
            }
        }
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MAIN)
        }

        binding.btnTogglePreview.setOnClickListener {
            if (previewing) stopPreview() else startPreview()
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (!previewing) return@setOnClickListener
            cameraHelper?.switchCamera()
            // 旧跟踪框对新摄像头画面无效，重置后重新检测
            if (trackerHandle != 0L) {
                CameraFaceJni.nativeResetTracking(trackerHandle)
            }
            updateStatus(0)
        }
    }

    /** 打开预览（权限检查 → 启动相机 → native 开始收帧）。 */
    private fun startPreview() {
        if (previewing) return
        if (trackerHandle == 0L) {
            binding.tvCrStatus.text = getString(R.string.cr_status_init_error)
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
        updateStatus(0)
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
        binding.tvFaceCount.text = ""
        binding.tvCrStatus.text = getString(R.string.cr_status_stopped)
    }

    /**
     * 预览帧回调（相机线程）：整帧处理（方向校正 + 检测跟踪 + 渲染）
     * 在 native 完成，此处仅回传数据并限频刷新人脸数。
     */
    private fun onPreviewFrame(data: ByteArray) {
        if (!previewing || trackerHandle == 0L) return
        val helper = cameraHelper ?: return

        val mirror = helper.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        val faces = CameraFaceJni.nativePostFrame(
            trackerHandle, data,
            CameraHelper.WIDTH, CameraHelper.HEIGHT,
            frameRotationDegrees(helper), mirror
        )

        val now = SystemClock.elapsedRealtime()
        if (faces != lastFaceCount || now - lastUiUpdate > 500) {
            lastFaceCount = faces
            lastUiUpdate = now
            mainHandler.post { if (previewing) updateStatus(faces) }
        }
    }

    /**
     * 计算画面顺时针校正角：传感器方向相对显示方向的差值。
     * 本应用固定横屏，后置摄像头通常为 0°；前置摄像头因镜像
     * 视图需附加 180°（与 ManiiuFace 竖屏的「逆时针 90°+镜像」等价）。
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

    private fun updateStatus(faces: Int) {
        val cameraName = if (cameraHelper?.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            getString(R.string.cr_camera_front)
        } else {
            getString(R.string.cr_camera_back)
        }
        binding.tvCrStatus.text = getString(R.string.cr_status_previewing, cameraName, faces)
        binding.tvFaceCount.text = getString(R.string.cr_face_count, faces)
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
            CameraFaceJni.nativeDestroy(trackerHandle)
            trackerHandle = 0
        }
        super.onDestroy()
    }
}
