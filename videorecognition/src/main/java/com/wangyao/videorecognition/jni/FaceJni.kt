package com.wangyao.videorecognition.jni

/**
 * 人脸检测 JNI 桥（native 实现：cpp/FaceDetector.cpp，
 * 按需求文档第九章 Haar 级联模型库实现多级联融合检测）。
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

    /**
     * 创建多级联检测器并加载级联模型，失败返回 0。
     *
     * @param faceCascadePaths    人脸级联模型路径（正脸 default/alt2
     *                            + 侧脸 profileface，多模型并集检测）
     * @param featureCascadePaths 面部特征模型路径（眼/鼻/嘴，
     *                            候选框验证降误检）
     */
    external fun nativeCreate(
        faceCascadePaths: Array<String>,
        featureCascadePaths: Array<String>
    ): Long

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
