# JNI entry and cancellation callback are resolved by their JVM names.
-keep class com.lumixfilmmirror.media.LibRawPreview { native <methods>; }
-keep class com.lumixfilmmirror.media.LibRawPreview$CancellationProbe { boolean isCancelled(); }
-keep class com.lumixfilmmirror.media.NativeLutBridge { native <methods>; }
-keep class com.lumixfilmmirror.media.NativeMobileFilmBridge { native <methods>; }
-keep class com.lumixfilmmirror.media.MobileFilmGpuFailure { public <init>(java.lang.String); }
