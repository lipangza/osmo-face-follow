#!/usr/bin/env bash
# 下载 YuNet 人脸检测模型到 app/src/main/assets/
set -e
URL="https://raw.githubusercontent.com/opencv/opencv_zoo/master/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
DEST="app/src/main/assets/face_detection_yunet_2023mar.onnx"
mkdir -p "$(dirname "$DEST")"
echo "Downloading YuNet model ..."
curl -L -o "$DEST" "$URL"
echo "Saved -> $DEST"
ls -la "$DEST"
