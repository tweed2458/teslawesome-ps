# The native bridge (src/main/cpp/ps-jni.c) binds PsNative's methods by their
# JNI names and calls the session listener's methods by name from chiaki's
# threads: neither may be renamed or dropped.
-keep class com.tals.psbridge.PsNative { *; }
-keep interface com.tals.psbridge.PsNative$Listener { *; }
-keep class * implements com.tals.psbridge.PsNative$Listener { *; }
-keepattributes SourceFile,LineNumberTable
