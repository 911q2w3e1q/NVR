# 代码混淆规则（R8 / ProGuard）
#
# 重要：assets 目录下的 .mesh 模型文件不是 Java 类，R8 从不混淆资源文件，
# 模型一律保持原样。同时 build.gradle 里对 .mesh 做了 noCompress，
# 保证模型字节不被 aapt 重新压缩，读进来和源文件逐字节一致。

# EasyAR 通过 JNI 反射调用，整个包必须原样保留（AAR 自带的 consumer 规则
# 也有这一条，这里再写一遍确保生效）
-keep class cn.easyar.** { *; }
-keep class com.example.sensorar.EasyKey { *; }

# 模型加载链路：自动发现 + 解析 + 渲染的入口类。
# 这些类主要是内部直接调用，R8 本来就能正确处理；显式 keep 让
# "放模型的文件能正常被发现加载"这件事不受混淆影响，也方便日后反射/调试。
-keep class com.example.sensorar.ModelScanner { *; }
-keep class com.example.sensorar.ModelScanner$Entry { *; }
-keep class com.example.sensorar.MeshParts { *; }
-keep class com.example.sensorar.MeshParts$Part { *; }
-keep class com.example.sensorar.GLRenderer { *; }
-keep class com.example.sensorar.MainActivity { *; }

# Activity 由系统反射实例化，构造保持可用
-keep public class * extends android.app.Activity

# GLSurfaceView.Renderer 的三个回调由系统按接口调用
-keep public class * implements android.opengl.GLSurfaceView$Renderer {
    public void onSurfaceCreated(...);
    public void onSurfaceChanged(...);
    public void onDrawFrame(...);
}

# 保留行号，崩溃日志能定位到源码行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 日志/诊断里会打印类名，保留注解与签名
-keepattributes *Annotation*, Signature
