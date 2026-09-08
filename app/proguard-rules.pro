# ===============================
# 保留 Android 组件
# ===============================
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

# ===============================
# 保留 UDP 通信相关类
# ===============================
-keep class com.carrot.amapnavi.UdpService { *; }
-keep class com.carrot.amapnavi.UdpService$* { *; }

# ===============================
# 保留 LaneDetect / SegResult 相关
# ===============================
-keep class com.carrot.lane.LaneDetect { *; }
-keep class com.carrot.lane.SegResult { *; }
-keep class com.carrot.lane.FastAnchor { *; }

# ===============================
# 保留 StableDeviceId / Global 等自定义类
# ===============================
-keep class com.carrot.amapnavi.Global { *; }

# ===============================
# JSON 解析安全
# ===============================
-keepclassmembers class * {
    <fields>;
}

# ===============================
# ONNX Runtime (JNI 必需)
# ===============================
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembers class ai.onnxruntime.** {
    native <methods>;
}
-dontwarn ai.onnxruntime.**

# ===============================
# Kotlin / 泛型 / 反射 / 协程
# ===============================
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }

# ===============================
# 日志
# ===============================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** w(...);
    public static *** v(...);
    public static *** i(...);
    public static *** e(...);
}

# ===============================
# 序列化安全（可选）
# ===============================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream;
    java.lang.Object readResolve();
}
