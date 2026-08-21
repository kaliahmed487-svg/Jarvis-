# Keep JNI-facing methods for llama.cpp bridge
-keepclasseswithmembers class com.jarvis.assistant.ai.LlamaBridge {
    native <methods>;
}

# Vosk / JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
