package com.example.facefollow

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.facefollow.ble.BleManager
import com.example.facefollow.camera.CameraXManager
import com.example.facefollow.detector.FaceDetector
import com.example.facefollow.detector.ModelPrep
import com.example.facefollow.eyelock.EyeLockController
import com.example.facefollow.eyelock.EyeState
import com.example.facefollow.model.FaceResult
import com.example.facefollow.model.OffsetListener
import com.example.facefollow.tracker.FaceOffsetCalculator
import com.example.facefollow.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity(), OffsetListener {

    private lateinit var binding: ActivityMainBinding
    private var detector: FaceDetector? = null
    private var cameraMgr: CameraXManager? = null
    private val calculator = FaceOffsetCalculator()
    private var bleManager: BleManager? = null
    private var started = false

    private var smoothingTauMs = 45f // 一阶低通时间常数（原 60ms 偏拖）, 用真实 dt 换算
    private var lastYaw = 0f
    private var lastPitch = 0f

    // ---- 人脸跟随（yaw 单轴闭环，默认关闭，需已连接）----
    private var followEnabled = false
    private var followYaw = 0f
    private var followLastOffsetX = 0f
    private var followLastOffsetY = 0f
    /** 俯仰软件积分：距开启时的累计俯仰指令（°），用于限制在安全包络内。 */
    private var pitchIntegratedDeg = 0f
    private var lastPitchSendAt = 0L
    private var lastFollowSendAt = 0L
    private var lastFaceSeenAt = 0L
    private var followZeroSent = true
    private var followUiAt = 0L
    /** 跟丢诊断：连续漏检帧数、上次检出置信度、上一次落日志时间。 */
    private var consecutiveMisses = 0
    private var lastConf = 0f
    private var lastLossLogAt = 0L
    /** 实测下发频率：按 BLE 写完成计数（writeOkCount）在 500ms 窗口内计算。 */
    private var hzWindowStart = 0L
    private var hzWindowBase = 0L
    private var measuredHz = 0f
    private var lastSendLogAt = 0L
    /** 代码内改变 swFollow 状态时置 true，避免监听器递归回调。 */
    private var suppressFollowListener = false
    private val followSendTimes = ArrayDeque<Long>()

    /** M2 只读枚举阶段：默认不发任何业务帧，避免在未验证协议前驱动云台。只有 M3 实验窗口内临时打开。 */
    private var allowGimbalCommands = false

    /** M3 实验进行中：屏蔽人脸跟随的连续发送，避免和手动的单帧实验混在一起。 */
    private var gimbalExperimentBusy = false
    private var lastM3At = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    // BLE 日志滚动缓冲（不覆盖人脸读数）
    private val bleLogLines = ArrayDeque<String>()
    private var lastFrameDtMs = 0f

    // 闭眼锁屏：默认状态来自持久化设置（默认关闭）
    private lateinit var eyeLock: EyeLockController
    private var lastEyeUiMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // COMPATIBLE 模式使用 TextureView，scaleX 镜像才能生效(前置自拍对齐)
        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        try {
            if (!OpenCVLoader.initDebug()) {
                Log.w(TAG, "OpenCV initDebug 返回 false，依赖打包内原生库，通常可正常使用")
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenCV initDebug 异常: ${e.message}")
        }

        binding.btnSwitch.setOnClickListener { cameraMgr?.toggleCamera() }
        // 实测出现过非本意的连续触发（疑似云台 HID 按键/误触命中获得焦点的按钮）。
        // 标定阶段只允许 adb intent 单发，界面按钮彻底禁用，避免把命令意外打出去。
        binding.btnM3Test.isEnabled = false
        binding.btnM3Test.isFocusable = false

        initEyeLock()
        initFollow()

        val missing = missingPermissions()
        if (missing.isEmpty()) {
            prepareAndStart()
        } else {
            // 相机已授权时先启动链路，蓝牙权限异步补齐即可
            if (hasPermission(Manifest.permission.CAMERA)) prepareAndStart()
            ActivityCompat.requestPermissions(this, missing, REQ_PERMS)
        }
    }

    // ---------------------------------------------------------------- 权限

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Android 12+ 用 BLUETOOTH_SCAN/CONNECT（manifest 已声明 neverForLocation）；11 及以下扫描需定位权限。 */
    private fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun missingPermissions(): Array<String> =
        (arrayOf(Manifest.permission.CAMERA) + blePermissions()).filterNot { hasPermission(it) }.toTypedArray()

    /** 标定入口：`adb shell am start -f 0x20000000 -n com.example.facefollow/.MainActivity --ei m3_yaw 2000` 单发一次。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val rawHex = intent.getStringExtra("m3_pl")
        if (rawHex != null) {
            runM3Raw(
                tag = intent.getStringExtra("m3_tag") ?: "raw",
                cs = intent.getIntExtra("m3_cs", 0x04),
                id = intent.getIntExtra("m3_id", 0x0C),
                payload = com.example.facefollow.protocol.Hex.parse(rawHex),
                holdMs = intent.getLongExtra("m3_hold", 500L)
            )
            return
        }
        if (!intent.hasExtra("m3_yaw")) return
        Log.i(TAG, "[M3] onNewIntent yaw=${intent.getIntExtra("m3_yaw", 0)}")
        runM3Params(
            tag = "calib",
            yaw = intent.getIntExtra("m3_yaw", 0),
            pitch = intent.getIntExtra("m3_pitch", 0),
            timeSec = intent.getIntExtra("m3_time", 1),
            holdMs = intent.getLongExtra("m3_hold", 500L)
        )
    }

    /** 本 Activity 在 manifest 里声明了 configChanges，旋转不会重建相机；这里手动同步 targetRotation。 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.overlay.post { cameraMgr?.updateTargetRotation() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return

        if (!hasPermission(Manifest.permission.CAMERA)) {
            binding.readout.text = "需要相机权限才能运行"
            Toast.makeText(this, "未授予相机权限", Toast.LENGTH_LONG).show()
            return
        }
        val bleOk = blePermissions().all { hasPermission(it) }
        if (!bleOk) {
            appendBleLog("蓝牙权限未授予：扫描/连接不可用（其余功能仍可运行）")
        }
        prepareAndStart()
    }

    // ---------------------------------------------------------------- 闭眼锁屏

    private fun initEyeLock() {
        eyeLock = EyeLockController(this)
        eyeLock.onEvent = { line -> Log.i(TAG, "eyeLock: $line") }

        // 开关初值 = 持久化设置（默认 false）
        binding.swEyeLock.isChecked = eyeLock.enabled
        binding.swEyeLock.setOnCheckedChangeListener { _, checked -> onEyeLockToggled(checked) }
        binding.eyeReadout.setOnClickListener {
            eyeLock.lockSetupIntent()?.let { startActivity(it) }
        }
        refreshEyeHint()
    }

    private fun onEyeLockToggled(checked: Boolean) {
        // 关闭：立即停掉推理与后续动作
        if (!checked) {
            eyeLock.enabled = false
            syncEyeFrameHook()
            refreshEyeHint()
            return
        }
        eyeLock.enabled = true
        syncEyeFrameHook()
        refreshEyeHint()
        // 无设备管理员权限：如实提示并直接给出激活入口（不伪造“已生效”）
        if (!eyeLock.lockReady()) {
            Toast.makeText(this, "未激活设备管理员，闭眼不会锁屏；请先激活", Toast.LENGTH_LONG).show()
            eyeLock.lockSetupIntent()?.let { startActivity(it) }
        }
    }

    /** 仅当功能开启时才给相机分析线程挂位图回调，关闭时零开销。 */
    private fun syncEyeFrameHook() {
        cameraMgr?.onEyeFrame = if (!eyeLock.enabled) {
            null
        } else { bmp, nowMs ->
            val state = eyeLock.processFrame(bmp, nowMs)
            if (state != null) postEyeReadout(state)
        }
    }

    private fun postEyeReadout(state: EyeState) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEyeUiMs < 250L) return // UI 4fps 足够
        lastEyeUiMs = now
        runOnUiThread {
            binding.eyeReadout.text = buildString {
                if (!state.facePresent) {
                    append("闭眼锁屏：开启，未检测到人脸")
                } else {
                    append("EAR L=%.2f R=%.2f  blink L=%.2f R=%.2f  %s".format(
                        state.earLeft, state.earRight, state.blinkLeft, state.blinkRight,
                        if (state.bothClosed) "闭眼中" else "睁眼"
                    ))
                }
                if (!eyeLock.lockReady()) append("\n未激活设备管理员：闭眼不会锁屏（点此激活）")
            }
        }
    }

    private fun refreshEyeHint() {
        binding.eyeReadout.text = when {
            !eyeLock.enabled -> "闭眼锁屏：关闭"
            !eyeLock.lockReady() -> "闭眼锁屏：开启，等待人脸…\n未激活设备管理员：闭眼不会锁屏（点此激活）"
            else -> "闭眼锁屏：开启，等待人脸…"
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统「设备管理员」页返回后刷新提示
        if (::eyeLock.isInitialized) refreshEyeHint()
    }

    // ---------------------------------------------------------------- 人脸跟随

    private fun initFollow() {
        binding.swFollow.isChecked = false
        binding.swFollow.setOnCheckedChangeListener { _, checked ->
            if (suppressFollowListener) return@setOnCheckedChangeListener
            setFollowEnabled(checked, if (checked) "开关开启" else "开关关闭")
        }
        updateFollowReadout(force = true)
    }

    /**
     * 唯一的状态切换入口：开/关都会启动或停止 25Hz 发送节拍；
     * 关闭立刻补一帧零速度，避免云台继续按上一次的速度转。
     */
    private fun setFollowEnabled(enabled: Boolean, reason: String) {
        if (enabled) {
            if (bleManager?.isConnected() != true) {
                Toast.makeText(this, "未连接云台，无法开启跟随", Toast.LENGTH_SHORT).show()
                syncFollowSwitch(false)
                return
            }
            followEnabled = true
            followYaw = 0f
            pitchIntegratedDeg = 0f
            lastPitchSendAt = 0L
            followLastOffsetX = 0f
            followLastOffsetY = 0f
            followZeroSent = true
            lastFaceSeenAt = 0L
            lastFollowSendAt = 0L
            followSendTimes.clear()
            consecutiveMisses = 0
            hzWindowStart = 0L
            hzWindowBase = 0L
            measuredHz = 0f
            syncFollowSwitch(true)
            appendBleLog("[跟随] 开启（$reason）rate≤${1000 / FOLLOW_RATE_MS}Hz deadzone=$FOLLOW_DEADZONE max=$FOLLOW_MAX_OUTPUT(105°/s) 限幅±$FOLLOW_MAX_VALUE")
            uiHandler.removeCallbacks(followTicker)
            uiHandler.post(followTicker)
        } else {
            // 关闭前补零：即使断连也调用一次（send 内部会记录并丢弃）
            bleManager?.sendYawSpeedRaw(0)
            followEnabled = false
            followYaw = 0f
            followZeroSent = true
            followLastOffsetX = 0f
            followLastOffsetY = 0f
            pitchIntegratedDeg = 0f
            uiHandler.removeCallbacks(followTicker)
            syncFollowSwitch(false)
            updateFollowReadout(force = true)
            appendBleLog("[跟随] 关闭（$reason），已补零速度")
        }
    }

    private fun syncFollowSwitch(checked: Boolean) {
        if (binding.swFollow.isChecked == checked) return
        suppressFollowListener = true
        binding.swFollow.isChecked = checked
        suppressFollowListener = false
    }

    /**
     * 33Hz 发送节拍（与相机帧率解耦）：
     * - 人脸新鲜（≤100ms）→ 按平滑后的 followYaw 全量下发；
     * - 100~300ms（单帧漏检/短暂遮挡）→ 线性衰减，不是急停；
     * - >300ms → 发一次零速度，之后每秒补零，绝不持续转；
     * - 断连 → 自动关闭跟随；
     * - 手动实验占用通道时只跳动不发，避免两类命令混发。
     */
    private val followTicker = object : Runnable {
        override fun run() {
            if (!followEnabled) return
            val bm = bleManager
            val now = SystemClock.elapsedRealtime()
            if (bm?.isConnected() != true) {
                setFollowEnabled(false, "断连自动停止")
                return
            }
            measureHz(bm, now)
            if (!gimbalExperimentBusy) {
                val age = now - lastFaceSeenAt
                when {
                    age <= FOLLOW_LOST_STOP_MS -> {
                        // 100ms 内全量；之后线性衰减到 0，避免漏检瞬间的急停/急摆
                        val decay = if (age <= FOLLOW_DECAY_START_MS) 1f
                        else (1f - (age - FOLLOW_DECAY_START_MS).toFloat() /
                            (FOLLOW_LOST_STOP_MS - FOLLOW_DECAY_START_MS)).coerceIn(0f, 1f)
                        val v = (followYaw * decay).roundToInt().coerceIn(-FOLLOW_MAX_VALUE, FOLLOW_MAX_VALUE)
                        if (bm.sendYawSpeedRaw(v)) {
                            recordFollowSend(now)
                            followZeroSent = v == 0
                        }
                        // 俯仰轴：仅在检测新鲜时步进，避免丢脸时继续抬/压
                        if (decay >= 0.9f && now - lastPitchSendAt >= FOLLOW_PITCH_RATE_MS) {
                            lastPitchSendAt = now
                            var dy = followLastOffsetY
                            if (abs(dy) < FOLLOW_DEADZONE) dy = 0f
                            val newInt = (pitchIntegratedDeg + dy * FOLLOW_PITCH_GAIN_DEG * FOLLOW_PITCH_SIGN)
                                .coerceIn(-FOLLOW_PITCH_MAX_DEG, FOLLOW_PITCH_MAX_DEG)
                            val stepDeg = newInt - pitchIntegratedDeg
                            if (abs(stepDeg) >= 0.1f) {
                                val pv = (stepDeg * 10f).roundToInt().coerceIn(-600, 600)
                                if (pv != 0 && bm.sendPitchAngleStep(pv)) pitchIntegratedDeg = newInt
                            }
                        }
                        updateFollowReadout(force = false)
                    }
                    !followZeroSent || now - lastFollowSendAt >= 1000L -> {
                        if (bm.sendYawSpeedRaw(0)) {
                            recordFollowSend(now)
                            followZeroSent = true
                        }
                        updateFollowReadout(force = false)
                    }
                }
            }
            uiHandler.postDelayed(this, FOLLOW_RATE_MS)
        }
    }

    /** 用 BLE 实际写完成数算下发频率，而不是「调用了几次」。 */
    private fun measureHz(bm: com.example.facefollow.ble.BleManager, now: Long) {
        val count = bm.writeOkCount
        if (hzWindowStart == 0L) {
            hzWindowStart = now
            hzWindowBase = count
            return
        }
        val dt = now - hzWindowStart
        if (dt >= 500L) {
            measuredHz = (count - hzWindowBase) * 1000f / dt
            hzWindowStart = now
            hzWindowBase = count
        }
    }

    private fun recordFollowSend(now: Long) {
        lastFollowSendAt = now
        followSendTimes.addLast(now)
        while (followSendTimes.isNotEmpty() && now - followSendTimes.first() > 1000L) {
            followSendTimes.removeFirst()
        }
        // 33Hz 下逐帧打日志会刷爆 logcat，改为每秒一条
        if (now - lastSendLogAt >= 1000L) {
            lastSendLogAt = now
            Log.i(TAG, "[跟随] 下发 yaw=%.0f 实际%.0fHz 丢弃=%d 漏检=%d".format(
                followYaw, measuredHz, bleManager?.realtimeDropped ?: 0L, consecutiveMisses))
        }
    }

    private fun updateFollowReadout(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - followUiAt < FOLLOW_UI_MS) return
        followUiAt = now
        binding.followReadout.text = buildString {
            if (!followEnabled) {
                append("跟随：关闭")
            } else if (bleManager?.isConnected() != true) {
                append("跟随：已断连")
            } else {
                val age = now - lastFaceSeenAt
                val v = followYaw.roundToInt().coerceIn(-FOLLOW_MAX_VALUE, FOLLOW_MAX_VALUE)
                when {
                    age <= FOLLOW_LOST_STOP_MS -> {
                        append("跟随 ON dx=%+.2f dy=%+.2f | yaw=%+d pitch=%+.1f° %.0fHz".format(
                            followLastOffsetX, followLastOffsetY, v, pitchIntegratedDeg, measuredHz))
                    }
                    else -> append("跟随 ON 丢脸 ${age}ms：已发零速度")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 启动

    private fun prepareAndStart() {
        if (started) return
        started = true
        lifecycleScope.launch(Dispatchers.IO) {
            val path = ModelPrep.ensureModel(this@MainActivity) { status ->
                runOnUiThread { binding.readout.text = status }
            }
            if (path == null) {
                runOnUiThread {
                    binding.readout.text = "模型缺失：请按 README 放入 assets 或检查网络"
                    Toast.makeText(this@MainActivity, "人脸模型未能就绪", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            detector = FaceDetector(path)
            cameraMgr = CameraXManager(
                context = this@MainActivity,
                lifecycleOwner = this@MainActivity,
                previewView = binding.previewView,
                overlayView = binding.overlay,
                listener = this@MainActivity,
                detector = detector!!,
                calculator = calculator
            )
            runOnUiThread {
                cameraMgr?.start()
                // 功能已开启时，相机就绪后立刻挂上闭眼回调
                syncEyeFrameHook()
            }

            runOnUiThread { initBle() }
        }
    }

    private fun initBle() {
        if (adapterMissingReason() != null) {
            appendBleLog(adapterMissingReason()!!)
            return
        }
        val bleOk = blePermissions().all { hasPermission(it) }
        if (!bleOk) {
            appendBleLog("缺少蓝牙运行时权限，跳过扫描")
            return
        }
        bleManager = BleManager(this)
        bleManager?.setCallback(object : BleManager.BleCallback {
            override fun onLog(line: String) {
                runOnUiThread { appendBleLog(line) }
            }

            override fun onDeviceFound(name: String?, address: String, rssi: Int) {
                // 详细行已由 onLog 输出；这里只做界面提示
                runOnUiThread { binding.readout.text = "发现设备 ${name ?: address}" }
            }

            override fun onConnected(deviceName: String) {
                runOnUiThread { appendBleLog("已连接 $deviceName") }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    appendBleLog("已断开")
                    if (followEnabled) setFollowEnabled(false, "断连自动停止")
                }
            }

            override fun onError(msg: String) {
                runOnUiThread { appendBleLog("BLE 错误: $msg") }
            }

            override fun onNotificationReceived(data: ByteArray) {
                // 原始报文与 DUML 解析已在 BleManager 内部打日志
            }
        })
        bleManager?.startScan()
    }

    private fun adapterMissingReason(): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return when {
            adapter == null -> "本机无蓝牙适配器"
            !adapter.isEnabled -> "蓝牙未开启，请先开启蓝牙"
            else -> null
        }
    }

    // ---------------------------------------------------------------- 回调

    /** 第三阶段结果回调：显示偏移量 + 更新 yaw 跟随目标（发送由 followTicker 以固定频率负责）。 */
    override fun onFaceOffset(result: FaceResult) {
        binding.overlay.setResult(result)
        lastFrameDtMs = result.dtMs

        if (result.present) {
            binding.readout.text = buildString {
                append("dx=%.3f dy=%.3f  conf=%.2f\n".format(result.offsetX, result.offsetY, result.confidence))
                append("yaw=%.1f° pitch=%.1f° roll=%.1f°  size=%.3f\n".format(
                    result.yaw, result.pitch, result.roll, result.sizeRatio))
                val fps = if (result.dtMs > 0f) 1000f / result.dtMs else 0f
                append("dt=%.1fms fps=%.1f  follow=%.0f".format(result.dtMs, fps, followYaw))
            }

            val now = SystemClock.elapsedRealtime()
            lastFaceSeenAt = now
            followLastOffsetX = result.offsetX
            followLastOffsetY = result.offsetY
            lastConf = result.confidence
            if (consecutiveMisses > 0) {
                Log.i(TAG, "[跟丢恢复] 连续漏检 $consecutiveMisses 帧后重新检出 conf=%.2f dx=%+.2f"
                    .format(result.confidence, result.offsetX))
                consecutiveMisses = 0
            }

            // === yaw 闭环：死区 → 软拐点曲线（小偏移柔、大偏移快速提速）→ 一阶低通 ===
            // offsetX 右为正，官方 setYaw 正值=右转，同号；方向不符时把 FOLLOW_YAW_SIGN 取 -1。
            val target = shapeYawCommand(result.offsetX)
            val alpha = if (result.dtMs > 0f)
                (1f - exp(-result.dtMs / smoothingTauMs)).coerceIn(0f, 1f)
            else 0.3f
            followYaw += (target - followYaw) * alpha
            lastYaw = followYaw
            lastPitch = 0f
        } else {
            binding.readout.text = "未检测到人脸"
            // 注意：这里不清零 followYaw —— 单帧漏检不应让云台急停急摆，
            // 衰减与停止统一由 followTicker 按「距上次检出时间」处理。
            consecutiveMisses++
            val now = SystemClock.elapsedRealtime()
            val age = now - lastFaceSeenAt
            if (consecutiveMisses == 1 || (age >= FOLLOW_LOST_STOP_MS && now - lastLossLogAt >= 1000L)) {
                lastLossLogAt = now
                Log.i(TAG, "[跟丢] 连续漏检=$consecutiveMisses 距上次检出=${age}ms 上次conf=%.2f 上次dx=%+.2f yaw=%+.0f"
                    .format(lastConf, followLastOffsetX, followYaw))
            }
        }
    }

    /**
     * 偏移 → yaw 速度命令（单位 0.1°/s）。
     * 死区外先归一化到 [0,1]，再按 FOLLOW_CURVE_EXP 做软拐点：
     *   |dx|=0.04 → 0；0.2 → 约 82(8°/s)；0.5 → 约 346(35°/s)；1.0 → 1050(105°/s)。
     */
    private fun shapeYawCommand(offsetX: Float): Float {
        val a = abs(offsetX)
        if (a <= FOLLOW_DEADZONE) return 0f
        val t = ((a - FOLLOW_DEADZONE) / (1f - FOLLOW_DEADZONE)).coerceIn(0f, 1f)
        val shaped = t.toDouble().pow(FOLLOW_CURVE_EXP.toDouble()).toFloat()
        return shaped * FOLLOW_MAX_OUTPUT * FOLLOW_YAW_SIGN
    }

    /**
     * M3 单次开环试验（安全版），参数化以便做标定：
     *   零速度帧 -> 300ms -> 指定 yaw/pitch/time 的速率帧 -> 保持 holdMs -> 零速度帧 -> 关闭放行开关。
     * 单次触发、不循环；「一次只改一个变量」由调用方保证。任一步未连接/写入失败立刻停发。
     */
    private fun runM3Experiment() = runM3Params("button", M3_YAW_TEST, 0, 1, M3_HOLD_MS)

    private fun runM3Params(tag: String, yaw: Int, pitch: Int, timeSec: Int, holdMs: Long) {
        val bm = bleManager
        val now = System.currentTimeMillis()
        Log.i(TAG, "[M3] params tag=$tag yaw=$yaw pitch=$pitch time=$timeSec hold=$holdMs connected=${bm?.isConnected()} busy=$gimbalExperimentBusy")
        if (bm == null || !bm.isConnected()) {
            appendBleLog("[M3] $tag 未连接，取消")
            return
        }
        if (gimbalExperimentBusy) {
            appendBleLog("[M3] $tag 上一次实验还没结束，忽略")
            return
        }
        // 防抖：连续点按或重复 intent 在 1.5s 内只允许一次，避免无意连发。
        if (now - lastM3At < 1500L) {
            appendBleLog("[M3] $tag 距上次不足 1.5s，忽略")
            return
        }
        lastM3At = now
        gimbalExperimentBusy = true
        allowGimbalCommands = true
        bm.setNotifyLogInterval(100)
        appendBleLog("[M3] $tag 开始 t=$now yaw=$yaw pitch=$pitch time=$timeSec hold=$holdMs")

        if (!bm.sendSpeedRaw(0, 0, 0, timeSec)) {
            finishM3("$tag 零速度帧未发出")
            return
        }
        uiHandler.postDelayed({
            if (!bm.isConnected()) {
                finishM3("$tag 等待期间断连")
                return@postDelayed
            }
            if (!bm.sendSpeedRaw(yaw, pitch, 0, timeSec)) {
                finishM3("$tag 目标帧未发出")
                return@postDelayed
            }
            uiHandler.postDelayed({
                bm.sendSpeedRaw(0, 0, 0, timeSec)
                finishM3("$tag 已补零速度")
            }, holdMs)
        }, M3_GAP_MS)
    }

    /** 标定用裸帧：零速度 -> 300ms -> 任意 cs/id/payload 一帧 -> holdMs -> 零速度。 */
    private fun runM3Raw(tag: String, cs: Int, id: Int, payload: ByteArray, holdMs: Long) {
        val bm = bleManager
        val now = System.currentTimeMillis()
        if (bm == null || !bm.isConnected()) {
            appendBleLog("[M3] $tag 未连接，取消")
            return
        }
        if (gimbalExperimentBusy || now - lastM3At < 1500L) {
            appendBleLog("[M3] $tag 被防抖/忙碌拦截")
            return
        }
        lastM3At = now
        gimbalExperimentBusy = true
        allowGimbalCommands = true
        bm.setNotifyLogInterval(100)
        appendBleLog("[M3] $tag 开始 t=$now cs=$cs id=$id payload=${com.example.facefollow.protocol.Hex.format(payload)}")
        if (!bm.sendSpeedRaw(0, 0, 0, 1)) {
            finishM3("$tag 零速度帧未发出")
            return
        }
        uiHandler.postDelayed({
            if (!bm.isConnected()) {
                finishM3("$tag 等待期间断连")
                return@postDelayed
            }
            if (!bm.sendRawFrame(cs, id, payload)) {
                finishM3("$tag 裸帧未发出")
                return@postDelayed
            }
            uiHandler.postDelayed({
                bm.sendSpeedRaw(0, 0, 0, 1)
                finishM3("$tag 已补零速度")
            }, holdMs)
        }, M3_GAP_MS)
    }

    private fun finishM3(reason: String) {
        allowGimbalCommands = false
        gimbalExperimentBusy = false
        bleManager?.setNotifyLogInterval(500)
        appendBleLog("[M3] $reason t=${System.currentTimeMillis()}（allowGimbalCommands 已关回 false）")
    }

    private fun appendBleLog(line: String) {
        bleLogLines.addLast(line)
        while (bleLogLines.size > 10) bleLogLines.removeFirst()
        binding.bleLog.text = bleLogLines.joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (followEnabled) {
            bleManager?.sendYawSpeedRaw(0)
            followEnabled = false
        }
        uiHandler.removeCallbacks(followTicker)
        uiHandler.removeCallbacksAndMessages(null)
        allowGimbalCommands = false
        cameraMgr?.onEyeFrame = null
        if (::eyeLock.isInitialized) eyeLock.release()
        cameraMgr?.release()
        detector?.release()
        bleManager?.disconnect()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_PERMS = 100

        /** M3 实验：yaw 单轴 int16 值。2000 是放大档，保证肉眼可见（约 int16 满量程的 6%）。 */
        private const val M3_YAW_TEST = 2000
        private const val M3_GAP_MS = 300L
        private const val M3_HOLD_MS = 500L

        // ---- 人脸跟随（yaw 单轴闭环）----
        /** 下发间隔 30ms ≈ 33Hz（BLE 写入用「只留最新帧」策略，不会堆积）。 */
        private const val FOLLOW_RATE_MS = 30L
        /** 丢脸超过 300ms 立即发零速度停住。 */
        private const val FOLLOW_LOST_STOP_MS = 300L
        /** 丢脸后 100ms 内保持原速度，之后线性衰减到 0（避免单帧漏检就急停/急摆）。 */
        private const val FOLLOW_DECAY_START_MS = 100L
        /** 实测标定：value × 0.1°/s，官方上限 120°/s → 安全限幅 ±1200。 */
        private const val FOLLOW_MAX_VALUE = 1200
        /** 死区：|dx|<0.04 视为居中（原 0.08 太大，人稍微移动云台不动）。 */
        private const val FOLLOW_DEADZONE = 0.04f
        /** 满幅输出 1050 = 105°/s，贴近但不超过官方 120°/s。 */
        private const val FOLLOW_MAX_OUTPUT = 1050f
        /** 软拐点指数：>1 时小偏移柔和、大偏移迅速提速（1.6 在 dx=0.2 时约 8°/s）。 */
        private const val FOLLOW_CURVE_EXP = 1.6f
        /** 方向位：实测/官方 setYaw 正值=右转，与 offsetX 同号；若真机反向改 -1.0f。 */
        private const val FOLLOW_YAW_SIGN = 1.0f
        /** 读数刷新限速 100ms。 */
        private const val FOLLOW_UI_MS = 100L

        // ---- 俯仰轴（实测 cs=04/id=0x14 相对角度模式，0.1°/单位）----
        /** 俯仰增量下发 10Hz。 */
        private const val FOLLOW_PITCH_RATE_MS = 100L
        /** 满幅偏移(1.0)每 100ms 抬/压 1°（≈10°/s）。 */
        private const val FOLLOW_PITCH_GAIN_DEG = 1.0f
        /** offsetY 下为正 → 需要低头 → 负值；若真机反向改 +1.0f。 */
        private const val FOLLOW_PITCH_SIGN = -1.0f
        /** 软件安全包络：距开启姿态累计俯仰 ±45°（不撞官方机械限位 −106.54~+235.5）。 */
        private const val FOLLOW_PITCH_MAX_DEG = 45f
    }
}
