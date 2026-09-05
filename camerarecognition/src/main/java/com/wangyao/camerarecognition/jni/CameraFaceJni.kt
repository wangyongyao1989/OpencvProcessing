package com.wangyao.camerarecognition.jni

import android.view.Surface

/**
 * 相机人脸检测跟踪 JNI 桥（native 实现：cpp/CameraFaceDetector.cpp，
 * 移植自 ManiiuFace 工程的 native-lib.cpp，并按需求文档第九章的
 * Haar 级联模型库扩展为多级联融合检测）。
 *
 * 依赖加载顺序：先加载 OpenCV 动态库（本模块 cpp/libs 下的
 * 预编译 libopencv_java4.so，经 jniLibs 打包进 APK），
 * 再加载本模块 native 库（DT_NEEDED 依赖前者）。
 */
object CameraFaceJni {

    init {
        System.loadLibrary("opencv_java4")
        System.loadLibrary("camerarecognition_native")
    }

    /**
     * 创建多级联检测跟踪器并加载级联模型，失败返回 0。
     *
     * @param faceCascadePaths    人脸级联模型路径（正脸 default/alt2
     *                            + 侧脸 profileface，多模型并集检测）
     * @param featureCascadePaths 面部特征模型路径（眼/戴眼镜眼/鼻/嘴，
     *                            候选框验证降误检）
     */
    external fun nativeCreate(
        faceCascadePaths: Array<String>,
        featureCascadePaths: Array<String>
    ): Long

    /** 释放检测跟踪器与渲染窗口。 */
    external fun nativeDestroy(handle: Long)

    /** 绑定渲染 Surface（传 null 解绑）。 */
    external fun nativeSetSurface(handle: Long, surface: Surface?)

    /** 重置跟踪状态（切换摄像头后旧跟踪框失效，需重新检测）。 */
    external fun nativeResetTracking(handle: Long)

    /**
     * 提交一帧 NV21 预览数据：native 侧完成 NV21→RGBA、方向校正、
     * 灰度均衡化、DetectionBasedTracker 检测跟踪、人脸框绘制与
     * ANativeWindow 渲染。
     *
     * @param data      NV21 数据（长度 = w*h*3/2）
     * @param w/h       预览宽高
     * @param rotation  顺时针旋转校正角（0/90/180/270，横屏应用通常为 0）
     * @param mirror    前置摄像头是否水平镜像（自拍视图）
     * @return          当前跟踪到的人脸数
     */
    external fun nativePostFrame(
        handle: Long, data: ByteArray, w: Int, h: Int,
        rotation: Int, mirror: Boolean
    ): Int

    /** OpenCV 版本串。 */
    external fun nativeOpencvVersion(): String
}
