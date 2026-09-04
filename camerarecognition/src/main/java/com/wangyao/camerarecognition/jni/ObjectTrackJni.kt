package com.wangyao.camerarecognition.jni

import android.view.Surface

/**
 * 框选实物实时检测跟踪 JNI 桥（native 实现：cpp/ObjectTracker.cpp，
 * 以 videorecognition「以该帧检索视频」的检索流程为基础：手势框选
 * ROI 作为查询 → 同空间特征（64 维亮度直方图 + Sobel 方向直方图）→
 * CamShift 候选定位 → 综合相似度验证 → 绿框实时跟踪）。
 *
 * 依赖加载顺序：先加载 OpenCV 动态库（本模块 cpp/libs 下的
 * 预编译 libopencv_java4.so，经 jniLibs 打包进 APK），
 * 再加载本模块 native 库（DT_NEEDED 依赖前者）。
 */
object ObjectTrackJni {

    init {
        System.loadLibrary("opencv_java4")
        System.loadLibrary("camerarecognition_native")
    }

    /** 创建实物跟踪器（无需级联模型），失败返回 0。 */
    external fun nativeCreate(): Long

    /** 释放跟踪器与渲染窗口。 */
    external fun nativeDestroy(handle: Long)

    /** 绑定渲染 Surface（传 null 解绑）。 */
    external fun nativeSetSurface(handle: Long, surface: Surface?)

    /** 清除目标模板与跟踪状态（重新框选 / 切换摄像头后调用）。 */
    external fun nativeResetTracking(handle: Long)

    /**
     * 手势框选目标（检索中的「查询」）。
     *
     * @param x/y/w/h ROI（显示图像坐标，即与渲染帧同坐标系）
     */
    external fun nativeSelectObject(
        handle: Long, x: Int, y: Int, w: Int, h: Int
    )

    /**
     * 提交一帧 NV21 预览数据：native 侧完成 NV21→RGBA、方向校正、
     * CamShift 跟踪 + 综合相似度验证、绿框与相似度绘制、
     * ANativeWindow 渲染。
     *
     * @param data      NV21 数据（长度 = w*h*3/2）
     * @param w/h       预览宽高
     * @param rotation  顺时针旋转校正角（0/90/180/270，横屏应用通常为 0）
     * @param mirror    前置摄像头是否水平镜像（自拍视图）
     * @return [状态, x, y, w, h, 相似度]：状态 0=待框选 1=已框选
     *         2=跟踪中（框与相似度有效）3=目标丢失
     */
    external fun nativePostFrame(
        handle: Long, data: ByteArray, w: Int, h: Int,
        rotation: Int, mirror: Boolean
    ): FloatArray

    /**
     * 导出「框选模板」缩略图（核验框选的 Object 是否正确）。
     *
     * @return [w(4B)][h(4B)][RGBA...]（小端），无模板时返回空数组；
     *         UI 侧用 ByteBuffer.order(LITTLE_ENDIAN) 解析
     */
    external fun nativeGetTemplateThumb(handle: Long): ByteArray

    /**
     * 导出「实时跟踪框」缩略图（视频当前帧中绿框内画面，核验
     * 「视频中的 Object」与「框选的 Object」是否同一实物）。
     *
     * @return 格式同上，未跟踪/未缓存时返回空数组
     */
    external fun nativeGetTrackedThumb(handle: Long): ByteArray

    /** OpenCV 版本串。 */
    external fun nativeOpencvVersion(): String
}
