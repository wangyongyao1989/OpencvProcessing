package com.wangyao.camerarecognition.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.util.Log

/**
 * 相机预览助手（移植自 ManiiuFace 工程的 CameraHelper）。
 *
 * 使用 legacy Camera API 以 NV21 格式输出 640×480 预览帧：
 * 1. setPreviewFormat(NV21) + setPreviewSize(640, 480)；
 * 2. setPreviewCallbackWithBuffer + addCallbackBuffer 缓冲区复用
 *    （避免每帧分配内存引起 GC 抖动）；
 * 3. 预览画面送入一个离屏 SurfaceTexture（实际渲染由 native 侧
 *    ANativeWindow 完成，与 ManiiuFace 的用法一致）；
 * 4. 每帧 NV21 数据通过 [previewCallback] 回调给上层送检。
 */
class CameraHelper(private var cameraId: Int = Camera.CameraInfo.CAMERA_FACING_BACK) :
    Camera.PreviewCallback {

    companion object {
        private const val TAG = "CameraHelper"

        /** 预览分辨率（与 ManiiuFace 相同：640×480，检测实时性最好）。 */
        const val WIDTH = 640
        const val HEIGHT = 480
    }

    private var camera: Camera? = null
    private lateinit var buffer: ByteArray
    private var surfaceTexture: SurfaceTexture? = null

    /** 预览帧回调（运行在相机线程，上层直接送 JNI 检测）。 */
    var previewCallback: ((ByteArray) -> Unit)? = null

    /** 传感器方向（用于计算画面校正角）。 */
    var sensorOrientation: Int = 90
        private set

    /** 当前摄像头朝向（前置/后置）。 */
    var facing: Int = Camera.CameraInfo.CAMERA_FACING_BACK
        private set

    fun getCameraId(): Int = cameraId

    /** 切换前后摄像头（重启预览）。 */
    fun switchCamera() {
        cameraId = if (cameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        } else {
            Camera.CameraInfo.CAMERA_FACING_BACK
        }
        stopPreview()
        startPreview()
    }

    /** 打开摄像头并启动 NV21 预览。 */
    fun startPreview() {
        try {
            // 读取传感器方向与朝向（供上层计算画面校正角）
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            sensorOrientation = info.orientation
            facing = info.facing

            camera = Camera.open(cameraId).apply {
                val parameters = parameters
                // 预览数据格式 NV21（Y 平面 + VU 交错平面）
                parameters.setPreviewFormat(ImageFormat.NV21)
                parameters.setPreviewSize(WIDTH, HEIGHT)
                setParameters(parameters)

                // 数据缓冲区复用：一帧 NV21 = w*h*3/2 字节
                buffer = ByteArray(WIDTH * HEIGHT * 3 / 2)
                addCallbackBuffer(buffer)
                setPreviewCallbackWithBuffer(this@CameraHelper)

                // 预览画面送入离屏纹理（真实显示由 native 渲染）
                surfaceTexture = SurfaceTexture(11).also { setPreviewTexture(it) }
                startPreview()
            }
            Log.i(TAG, "startPreview ok: ${WIDTH}x$HEIGHT, sensorOrientation=$sensorOrientation")
        } catch (ex: Exception) {
            Log.e(TAG, "startPreview failed", ex)
        }
    }

    /** 停止预览并释放摄像头。 */
    fun stopPreview() {
        camera?.run {
            setPreviewCallbackWithBuffer(null)
            stopPreview()
            release()
        }
        camera = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        previewCallback?.invoke(data)
        // 归还缓冲区供下一帧复用
        camera.addCallbackBuffer(buffer)
    }
}
