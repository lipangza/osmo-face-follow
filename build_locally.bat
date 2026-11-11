@echo off
REM 本地一键编译 FaceFollowApp (Windows)
REM 前置:
REM   1. 已安装 JDK 17+ 并在 PATH (java -version 可见)
REM   2. 已安装 Android SDK, 且:
REM        - 设置环境变量 ANDROID_HOME 指向 SDK 根目录
REM        - 已安装: sdkmanager "build-tools;34.0.0" "platforms;android-34" "platform-tools"
REM   3. (可选) 把 YuNet 模型放到 app\src\main\assets\face_detection_yunet_2023mar.onnx
REM      （不放也行, 首次启动 App 会自动联网下载）
setlocal
cd /d %~dp0

if exist gradlew (
  call gradlew.bat assembleDebug --no-daemon
) else (
  call gradle assembleDebug --no-daemon
)

echo.
echo APK 已生成: app\build\outputs\apk\debug\app-debug.apk
pause
