# JNI: native code calls NativeCore.Listener.onEvent by name.
-keep class app.neochat.core.NativeCore { *; }
-keep interface app.neochat.core.NativeCore$Listener { *; }
-keep class * implements app.neochat.core.NativeCore$Listener { void onEvent(java.lang.String); }

# WebRTC: native code reaches these classes through JNI by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
