# JNI: native code calls NativeCore.Listener.onEvent by name.
-keep class app.neochat.core.NativeCore { *; }
-keep interface app.neochat.core.NativeCore$Listener { *; }
-keep class * implements app.neochat.core.NativeCore$Listener { void onEvent(java.lang.String); }

# WebRTC: native code reaches these classes through JNI by name (JNI_OnLoad
# registers through org.jni_zero, so stripping it aborts the process).
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**
