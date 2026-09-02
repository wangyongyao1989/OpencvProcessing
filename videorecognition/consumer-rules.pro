# 消费者 ProGuard 规则（JNI 类需保留 native 方法）
-keepclassmembers class com.wangyao.videorecognition.jni.FaceJni {
    native <methods>;
}
