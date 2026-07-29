# 本项目不用反射、不做序列化，AGP 与各依赖自带的 consumer rules 已经够用。
# 这里只保留少量兜底。

# 崩溃日志能对上源码行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose 运行时（AGP 已带，这里是双保险）
-keep class androidx.compose.runtime.** { *; }

# 协程内部用到的 ServiceLoader
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
