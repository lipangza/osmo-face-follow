# assets 目录：放置人脸检测模型（可选）

本工程默认会在「首次启动」时自动联网下载 YuNet 模型到应用私有目录；
你也可以手动把模型放到这里，构建后会随 APK 一起打包，离线即可使用。

模型文件：face_detection_yunet_2023mar.onnx
下载地址：
https://raw.githubusercontent.com/opencv/opencv_zoo/master/models/face_detection_yunet/face_detection_yunet_2023mar.onnx

放法（任选其一）：
1) 直接把 .onnx 复制到本目录（app/src/main/assets/），文件名保持为
   face_detection_yunet_2023mar.onnx，然后重新构建。
2) 在项目根目录执行 ./download_model.sh (Linux/Mac) 或 download_model.ps1 (PowerShell)，
   脚本会把它下载到本目录。

注意：不要把 .onnx 改名，ModelPrep.kt 里是按这个文件名查找的。
