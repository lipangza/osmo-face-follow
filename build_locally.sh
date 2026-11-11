#!/usr/bin/env bash
# 本地一键编译 FaceFollowApp (Linux / macOS / Git Bash)
# 前置:
#   1. 已安装 JDK 17+ 并在 PATH (java -version 可见)
#   2. 已安装 Android SDK, 且:
#        - 设置环境变量 ANDROID_HOME 指向 SDK 根目录
#        - 已安装: sdkmanager "build-tools;34.0.0" "platforms;android-34" "platform-tools"
#   3. (可选) 把 YuNet 模型放到 app/src/main/assets/face_detection_yunet_2023mar.onnx
#      （不放也行, 首次启动 App 会自动联网下载）
set -e
cd "$(dirname "$0")"

# 若没有 gradle wrapper, 先用系统 gradle 生成
if [ ! -f gradlew ] && command -v gradle >/dev/null 2>&1; then
  gradle wrapper --gradle-version 8.7
fi

if [ -f gradlew ]; then
  chmod +x gradlew
  ./gradlew assembleDebug --no-daemon
else
  gradle assembleDebug --no-daemon
fi

echo
echo "APK 已生成: app/build/outputs/apk/debug/app-debug.apk"
