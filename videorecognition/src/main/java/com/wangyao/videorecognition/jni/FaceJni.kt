package com.wangyao.videorecognition.jni

/**
 * 人脸检测 JNI 桥（native 实现：cpp/FaceDetector.cpp）。
 *
 * 依赖加载顺序：先加载 OpenCV 动态库（本模块 cpp/libs 下的
 * 预编译 libopencv_java4.so，经 jniLibs 打包进 APK），
 * 再加载本模块 native 库（DT_NEEDED 依赖前者）。
 */
object FaceJni {

    init {
        System.loadLibrary("opencv_java4")
        System.loadLibrary("videorecognition_native")
    }

    /** 创建检测器并加载级联模型，失败返回 0。 */
    external fun nativeCreate(cascadePath: String): Long

    /** 释放检测器。 */
    external fun nativeDestroy(handle: Long)

    /**
     * 对灰度帧检测人脸。
     *
     * @param gray     行紧凑灰度像素（长度 = w*h）
     * @param minFace  最小人脸边长（像素）
     * @return 扁平数组 [n, x1,y1,w1,h1,conf1*1000, 0, x2,...]，
     *         无人脸时 [0]
     */
    external fun nativeDetect(
        handle: Long, gray: ByteArray, w: Int, h: Int, minFace: Int
    ): IntArray

    /** OpenCV 版本串。 */
    external fun nativeOpencvVersion(): String
}
