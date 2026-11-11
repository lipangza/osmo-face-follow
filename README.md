# FaceFollow —— 用手机前置摄像头驱动 DJI Osmo Mobile SE 跟随人脸

一个独立的第三方 Android 实验项目：用手机前置摄像头检测人脸位置，通过 BLE 直接向
DJI Osmo Mobile SE 发送云台控制指令，实现「人脸往哪走，云台往哪转」。

不需要 DJI Mimo，不需要 DJI 官方 SDK，也不需要 root。

> 本项目是**个人研究性质的互操作性实验**，与 DJI / 大疆创新**没有任何关联**，
> 未获得其授权或认可。详见下方[免责声明](#免责声明)。

---

## 本项目为独立实现，未基于其他开源项目

本项目全部代码为独立编写，**未基于任何其他开源项目修改而来**，不存在上游仓库、
fork 关系或需要继承的上游许可证。

项目中的协议事实（GATT 表、DUML 帧格式、CRC 参数、命令字与换算关系）来自作者对
**自有设备之间蓝牙通信的实测分析**（本机 HCI 抓包 + 真机逐条动作验证），属于对设备
通信行为的事实性描述，不包含任何 DJI 的代码、二进制、反编译产物或文档素材。

---

## 免责声明

- 本项目与 DJI（大疆创新）**无任何关联**，不是官方产品，也未获得官方支持或认可。
- 项目仅用于**学习、研究与个人互操作性实验**，请勿用于商业用途或批量生产。
- 通过非官方方式修改设备通信行为，**可能影响设备保修、固件稳定性甚至造成硬件损坏**。
  所有操作由使用者自行承担风险，作者不对任何直接或间接损失负责。
- 请遵守你所在地区的法律法规以及设备厂商的服务条款。是否使用本项目的代码与结论，
  由你自行判断并承担后果。
- 仓库中不包含 DJI Mimo 的 APK、反编译代码、固件、官方文档截图，也不包含任何抓包原始文件。

---

## 能做什么

- **人脸跟随**：前置摄像头实时检测人脸，横向偏移驱动云台 yaw 转动，纵向偏移驱动 pitch 俯仰。
- **实时叠加显示**：人脸框、5 个关键点、中心偏移矢量都画在预览上，并带底部数值读数
  （偏移量、置信度、近似头部姿态、实际下发频率）。
- **BLE 直连**：自动扫描并连接名字以 `OMSE` 开头的 Osmo Mobile SE，枚举 GATT、
  协商 MTU、订阅通知、发送 DUML 控制帧，并解析设备 ACK。
- **闭眼锁屏**（附加功能）：MediaPipe Face Landmarker 判断双眼闭合，持续闭眼一段时间后
  调用设备管理员锁屏，可用于「放下手机自动锁屏」这类场景。

## 当前状态

### 已验证（在真机上跑通）

- 工程可编译：`gradle assembleDebug` 与 `testDebugUnitTest` 均 BUILD SUCCESSFUL（8 个协议单测通过）。
- BLE 连接 OM SE：GATT 枚举、MTU 517 → 247、订阅 `FFF4` 通知、向 `FFF5` 写入，全程无配对/鉴权。
- DUML 组帧与 CRC：用本仓库算法重算真机发出的帧，CRC 与抓到的字节完全一致；设备对合法帧在 20–30 ms 内回同序号 ACK。
- yaw 速度控制：`cmdSet=0x04 / cmdId=0x0C / mode=0x80`，`°/s = value × 0.1`，限幅 ±1200（120°/s）。
- pitch 角度控制：`cmdSet=0x04 / cmdId=0x14 / mode=0x04`（相对角度），`° = value × 0.1`；
  俯仰轴在速度模式下不生效，必须走角度模式。
- 人脸检测：OpenCV `FaceDetectorYN` + YuNet，前置 4:3 采集，检测输入与预览取景一致，
  叠加层坐标与预览对齐（含前置镜像）。
- 跟随闭环：yaw 33 Hz 下发、pitch 10 Hz 步进；死区 0.04、软拐点 `t^1.6 × 1050`、一阶低通 45 ms；
  漏检 100 ms 内保持、100–300 ms 线性衰减、超过 300 ms 发零速度。
- 闭眼锁屏链路：模型推理 + EAR/Blendshape 判定 + 设备管理员锁屏（需手动激活设备管理员）。

### 未解决 / 已知限制

- **快速移动时仍可能跟丢**：逐帧独立检测，没有时序跟踪器，运动模糊或侧脸时 YuNet 分数会掉。
- **俯仰正负号与增益尚未逐台标定**：当前 `FOLLOW_PITCH_SIGN = -1` 是实测推断值，
  不同固件/安装方向可能需要改成 `+1`。
- 俯仰只做**相对角度步进 + 软件积分**，没有绝对角度闭环，长时间运行会有累计误差。
- 只控制 yaw / pitch，**不支持 roll**；yaw 也是开环（按偏移给速度，不读云台姿态做闭环）。
- 仅在**一台 OM SE + 一台 Android 手机**上验证，其他机型/系统版本未覆盖。
- 闭眼锁屏是附加功能，只在单机验证过，锁屏依赖设备管理员权限。
- 详细清单见 [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md)。

---

## 工作原理（一句话版）

```
前置摄像头 → 逐帧人脸检测 → 人脸中心相对画面中心的归一化偏移 (dx, dy)
          → 控制律（死区 / 曲线 / 低通）→ DUML 帧 → BLE 写入 FFF5 → 云台转动
```

完整数据流与模块职责见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)；
协议细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## 编译与安装

### 环境要求

- JDK 17 或更高（本机验证用的是 JDK 21）
- Android SDK：`compileSdk 34`、`build-tools 34.0.0`、`platform-tools`
- Gradle 8.7（仓库只保留了 `gradle/wrapper/gradle-wrapper.properties`，未提交 `gradlew` 脚本与 jar；
  直接用本机 Gradle，或让 Android Studio 自动生成 wrapper）
- 手机：Android 7.0（API 24）及以上，支持 BLE

### 方式一：Android Studio

1. `File → Open` 打开本目录，等待 Gradle Sync 完成。
2. 连接手机并开启 USB 调试。
3. 点击 Run。

### 方式二：命令行

```bash
# 告诉 Gradle 你的 SDK 位置（二选一）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
export ANDROID_HOME=/path/to/Android/Sdk

gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> APK 较大（约 140 MB），因为同时打包了 OpenCV 与 MediaPipe 的多架构原生库。
> 只给自己手机用时，可在 `app/build.gradle.kts` 的 `abiFilters` 里只留 `arm64-v8a`。

### 人脸模型

- 检测模型 `face_detection_yunet_2023mar.onnx`（约 230 KB）已放在 `app/src/main/assets/`，
  构建时会打进 APK；缺失时 App 也会在首次启动联网下载。
- 闭眼锁屏模型 `face_landmarker.task`（约 3.7 MB）同样已放在 assets 中。
- 两者都是第三方开源模型，许可证见[第三方组件](#第三方组件与模型)。

## 权限

| 权限 | 用途 | 说明 |
| --- | --- | --- |
| `CAMERA` | 前置摄像头采集 | 必需，拒绝则无法运行 |
| `BLUETOOTH_SCAN` | 扫描云台 | Android 12+，已声明 `neverForLocation` |
| `BLUETOOTH_CONNECT` | 连接与读写特征 | Android 12+ |
| `ACCESS_FINE_LOCATION` | BLE 扫描 | 仅 Android 11 及以下需要 |
| `INTERNET` | 首次下载人脸模型 | 模型已内置于 assets 时可不联网 |

蓝牙权限被拒绝时，App 仍可正常预览与检测，只是无法连接云台。

## 使用步骤

1. 打开云台电源，确认手机蓝牙已开启。
2. 启动 App 并授予相机、蓝牙权限。
3. App 会自动扫描名字以 `OMSE` 开头的设备并连接（日志区会打印 GATT 枚举与 MTU）。
4. 连接成功后打开「跟随」开关。未连接时开关会被拒绝并提示。
5. 关闭跟随会立即补发一帧零速度，避免云台继续按上一速度转动；断连也会自动关闭跟随。
6. 闭眼锁屏需要先在系统「设备管理员」里激活本应用（开关打开时会给出入口）。

## 目录结构

```
FaceFollowApp/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/                     # YuNet 检测模型 + MediaPipe 闭眼模型
│       │   ├── res/                        # 布局 / 主题 / 字符串
│       │   └── java/com/example/facefollow/
│       │       ├── MainActivity.kt          # 权限、UI、跟随节拍、标定入口
│       │       ├── camera/                  # CameraX 采集与坐标变换
│       │       ├── detector/                # YuNet 检测 + 模型准备
│       │       ├── tracker/                 # 归一化偏移与近似头姿
│       │       ├── protocol/                # DUML 组帧 / 解帧 / CRC
│       │       ├── ble/                     # GATT 连接、写入队列、ACK 日志
│       │       ├── eyelock/                 # 闭眼检测与锁屏
│       │       ├── ui/                      # 叠加层绘制
│       │       ├── model/                   # 数据类与回调接口
│       │       └── util/                    # YUV → Mat
│       └── test/                            # DUML / 闭眼判定的单元测试
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── docs/                                    # 协议、架构、已知问题
```

## 文档

- [docs/PROTOCOL.md](docs/PROTOCOL.md) —— GATT 表、DUML 帧格式、CRC、命令字、标定方法
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) —— 数据流、线程模型、控制律、模块职责
- [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) —— 已知问题与改进方向

## 第三方组件与模型

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| OpenCV（含 dnn / objdetect） | 4.10.0 | Apache-2.0 |
| AndroidX CameraX | 1.3.4 | Apache-2.0 |
| MediaPipe Tasks Vision | 0.10.20 | Apache-2.0 |
| YuNet 人脸检测模型（OpenCV Zoo） | 2023mar | MIT |
| MediaPipe Face Landmarker 模型 | face_landmarker.task | Apache-2.0 |

模型文件随仓库分发，仅用于本项目的演示用途；如再分发请保留各自的许可证与署名。

## 许可证

本项目以 [MIT License](LICENSE) 开源。
