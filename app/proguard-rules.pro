# Keep OpenCV classes (native JNI bindings)
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep CameraX / model loader
-keep class com.example.facefollow.** { *; }
