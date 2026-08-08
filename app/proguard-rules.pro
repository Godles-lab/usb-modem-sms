# 本项目不用反射、不做序列化，AGP 与各依赖自带的 consumer rules 已经够用。
# 下面在默认 proguard-android-optimize 之上再加一层防反编译加固。

# ---- 防反编译加固 ----
# 不保留源文件名与行号：崩溃栈里读不到源码结构（牺牲行号换更难逆向）
-renamesourcefileattribute ""
# 把所有类塞进根包，抹掉包层次
-repackageclasses ''
# 放开访问修饰符，容许更激进的内联与优化
-allowaccessmodification
-optimizationpasses 5

# 编译期抹掉日志调用，避免运行时留下可读线索
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ---- 兜底 keep ----
# Compose 运行时（AGP 已带，这里是双保险）
-keep class androidx.compose.runtime.** { *; }

# 协程内部用到的 ServiceLoader
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
