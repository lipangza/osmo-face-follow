# 下载 YuNet 人脸检测模型到 app/src/main/assets/ (PowerShell)
$URL  = "https://raw.githubusercontent.com/opencv/opencv_zoo/master/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
$DEST = "app/src/main/assets/face_detection_yunet_2023mar.onnx"
New-Item -ItemType Directory -Force -Path (Split-Path $DEST) | Out-Null
Write-Host "Downloading YuNet model ..."
Invoke-WebRequest -Uri $URL -OutFile $DEST
Write-Host "Saved -> $DEST"
Get-Item $DEST | Select-Object Length, FullName
