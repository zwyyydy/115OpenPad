# 115 OpenPad R8 规则
#
# 只保留「真的靠反射/动态查名字」的入口；其余交给各库自带的 consumer rules。
# 改这里之后务必装一次 release 包：R8 的问题是"编译过、打开某个页面才崩"，
# 不能只看 assembleRelease 成功。

# ---------- 基础 ----------
-keepattributes *Annotation*,InnerClasses,Signature,Exceptions,MethodParameters
-dontwarn org.jetbrains.annotations.**
# OkHttp 的可选 TLS 实现（编译期存在、运行期不一定有）
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.zxing.**

# ---------- kotlinx.serialization ----------
# 生成器挂在 Companion 上；@Serializable 类的序列化名是编译期常量，类名混淆不影响，
# 但 serializer 本身不能被裁掉。
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 枚举常量的名字会写进序列化结果；R8 的枚举优化会删掉"未引用"的常量
-keepclassmembers enum com.open115.pad.** { *; }
-dontnote kotlinx.serialization.**

# ---------- Retrofit ----------
# 方法上的注解是运行期读的；接口本身用 ::class.java 引用，不会被裁，但别改名
-keep,allowobfuscation interface com.open115.pad.data.OpenApi
-keep,allowobfuscation interface com.open115.pad.data.AuthApi
-keep,allowobfuscation interface com.open115.pad.data.QrApi
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { *; }

# ---------- QuickJS（JNI + 回调） ----------
# 这个库没有自带 consumer rules：native 侧按 JNI 名字找方法，JavaCallback 的实现
# 由 JS 引擎回调，两者都可能被 R8 改名/内联掉。整包保留，代价只有几百 KB。
-keep class com.quickjs.** { *; }
-keep class * implements com.quickjs.JavaCallback { *; }
-keep class * implements com.quickjs.JavaVoidCallback { *; }
-keep class * implements com.quickjs.JavaConstructorCallback { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
