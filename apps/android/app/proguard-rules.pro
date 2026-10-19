# JNI: native code calls NativeCore.Listener.onEvent by name.
-keep class app.neochat.core.NativeCore { *; }
-keep interface app.neochat.core.NativeCore$Listener { *; }
-keep class * implements app.neochat.core.NativeCore$Listener { void onEvent(java.lang.String); }
