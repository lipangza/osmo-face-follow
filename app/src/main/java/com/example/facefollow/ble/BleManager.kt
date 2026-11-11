package com.example.facefollow.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.facefollow.protocol.Duml
import com.example.facefollow.protocol.DumlStreamParser
import com.example.facefollow.protocol.GeneralControl
import com.example.facefollow.protocol.GimbalControl
import com.example.facefollow.protocol.Hex
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * 机型无关的 BLE 探针 + 指令通道。
 *
 * 设计原则（OM SE 未验证，所有协议假设都只作为「假设」打印，不当事实）：
 * 1. 扫描不做名称过滤，所有 BLE 设备都上报并打日志；
 * 2. 连接后遍历并打印全部 service / characteristic（UUID、属性、权限）与协商后的 MTU；
 * 3. 订阅所有支持 notify/indicate 的特征；
 * 4. 主动读取标准设备信息服务 0x180A（型号/序列号/固件/硬件/厂商），
 *    缺失时用 DUML 版本查询兜底（该命令本身也是未验证假设）；
 * 5. 不做"fff0 一定存在"的硬编码——fff0/fff4/fff5 只出现在「预期 vs 实际」比对日志里。
 */
class BleManager(private val context: Context) {

    interface BleCallback {
        /** 所有可读日志统一走这里，便于直接打到界面/文件 */
        fun onLog(line: String) {}
        fun onDeviceFound(name: String?, address: String, rssi: Int) {}
        fun onConnected(deviceName: String) {}
        fun onDisconnected() {}
        fun onError(message: String) {}
        fun onNotificationReceived(data: ByteArray) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private var callback: BleCallback? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var connectedName: String = ""

    /** 协商后的 MTU，默认 23（BLE 最小值）；写入分片按 mtu-3 计算。 */
    private var mtu = 23

    private var writeChar: BluetoothGattCharacteristic? = null
    /** 当前选中的写入特征是否是 0xFFF5（fff0 厂商服务里的命令通道）。 */
    private var writeCharIsVendor = false
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    /** 实际写完成的帧数（GATT_SUCCESS），用于计算真实下发频率而非排队频率。 */
    @Volatile
    var writeOkCount = 0L
        private set

    /** 跟随帧日志限速：避免 33Hz 把 logcat 刷掉。 */
    private var lastYawLogMs = 0L
    private var lastYawLogged = Int.MIN_VALUE
    private var lastPitchLogMs = 0L
    /** 实时帧丢弃计数，用于判断 BLE 是否成为瓶颈。 */
    @Volatile
    var realtimeDropped = 0L
        private set

    private val readQueue = ArrayDeque<Pair<BluetoothGattCharacteristic, String>>()
    private var reading = false
    private val subscribeQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** 高频通知日志限速间隔：避免把枚举表从 logcat 环形缓冲里刷掉。实验期间可临时调低。 */
    private var notifyLogIntervalMs = 500L

    private val streamParser = DumlStreamParser()
    private var sequence = 1
    private var lastNotifyLogMs = 0L
    private var notifySkipped = 0

    /** 名称前缀只用于「自动连接」便利，不作为协议判断依据。 */
    var autoConnectNamePrefixes: List<String> =
        listOf("OMSE", "OM SE", "Osmo Mobile", "DJI OM", "OsmoMobile", "OM-")

    fun setCallback(cb: BleCallback) {
        callback = cb
    }

    fun isConnected(): Boolean = gatt != null && writeChar != null

    // ------------------------------------------------------------------ 扫描

    @SuppressLint("MissingPermission")
    fun startScan(timeoutMs: Long = 12_000) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            log("蓝牙不可用或未开启")
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            log("蓝牙 LE 扫描器不可用")
            return
        }
        if (scanning) return
        scanning = true
        log("[扫描] 开始，不过滤设备名，全部列出；超时 ${timeoutMs}ms")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            log("扫描缺少权限（Android 12+ 需要 BLUETOOTH_SCAN）: ${e.message}")
            return
        }
        handler.postDelayed({ stopScan("超时") }, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun stopScan(reason: String = "") {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan security", e)
        }
        handler.removeCallbacksAndMessages(null)
        log("[扫描] 结束 $reason")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = try { result.scanRecord?.deviceName ?: device.name } catch (e: SecurityException) { null }
            val address = device.address
            val rssi = result.rssi
            log("[扫描] name=${name ?: "<无>"} addr=$address rssi=$rssi")
            callback?.onDeviceFound(name, address, rssi)

            val hit = name != null && autoConnectNamePrefixes.any { name.contains(it, ignoreCase = true) }
            if (hit) {
                log("[扫描] 命中自动连接前缀，连接 $name ($address)")
                stopScan()
                connect(address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("[扫描] 失败 errorCode=$errorCode")
        }
    }

    // ------------------------------------------------------------------ 连接

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        // 扫描会连续命中同一条广播，重复 connect 会同时开多个 GATT 连接，先挡掉。
        if (gatt != null) {
            log("[连接] 已有连接/正在连接，忽略重复请求 $address")
            return
        }
        val a = adapter ?: run { log("蓝牙适配器不可用"); return }
        val device = try {
            a.getRemoteDevice(address)
        } catch (e: Exception) {
            log("地址非法: $address")
            return
        }
        connectedName = try { device.name ?: address } catch (e: SecurityException) { address }
        log("[连接] 发起连接 $connectedName")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan("主动断开")
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        writeCharIsVendor = false
        writeQueue.clear()
        readQueue.clear()
        writing = false
        reading = false
        mtu = 23
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("[连接] state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    callback?.onConnected(connectedName)
                    log("[连接] 已连接，开始发现服务")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    callback?.onDisconnected()
                    writeChar = null
                    g.close()
                    if (gatt === g) gatt = null
                    log("[连接] 已断开")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("[枚举] 服务发现失败 status=$status")
                return
            }
            enumerate(g)
            g.requestMtu(517)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            log("[MTU] 协商结果 mtu=$mtu status=$status（写入分片按 ${mtu - 3} 字节）")
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val label = labelOf(characteristic.uuid.toString())
            val value = characteristic.value ?: ByteArray(0)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("[读取] $label ${characteristic.uuid} = ${renderReadValue(label, value)}")
            } else {
                log("[读取] $label ${characteristic.uuid} 失败 status=$status")
            }
            reading = false
            readNext(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeOkCount++
            } else {
                log("[写入] 失败 status=$status")
            }
            writing = false
            writeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            log("[订阅] ${descriptor.characteristic.uuid} status=$status")
            subscribeNext(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            // 设备每秒会推几十帧，全量写日志会把 logcat 环形缓冲刷掉（枚举表会丢）。
            // 这里限速：每 500ms 才落一条，其余只计数；回调仍然全量转发。
            val now = SystemClock.elapsedRealtime()
            val allowed = now - lastNotifyLogMs >= notifyLogIntervalMs
            if (allowed) {
                val skipped = notifySkipped
                notifySkipped = 0
                lastNotifyLogMs = now
                val suffix = if (skipped > 0) "（此前跳过 $skipped 条）" else ""
                log("[通知] ${characteristic.uuid} ${Hex.format(data)}$suffix")
            } else {
                notifySkipped++
            }
            callback?.onNotificationReceived(data)
            for (frame in streamParser.feed(data)) {
                if (!allowed) continue
                log("[DUML] cmdSet=0x%02X cmdId=0x%02X seq=%d payload=%s"
                    .format(frame.cmdSet, frame.cmdId, frame.sequence, Hex.format(frame.payload)))
            }
        }
    }

    // ------------------------------------------------------------------ 枚举

    @SuppressLint("MissingPermission")
    private fun enumerate(g: BluetoothGatt) {
        val services: List<BluetoothGattService> = g.services
        log("[枚举] 共发现 ${services.size} 个服务")

        val found = HashSet<String>()
        for (svc in services) {
            val svcUuid = svc.uuid.toString().lowercase()
            val svcType = when (svc.type) {
                BluetoothGattService.SERVICE_TYPE_PRIMARY -> "primary"
                else -> "secondary"
            }
            log("[枚举] 服务 $svcUuid ($svcType) ${serviceHint(svcUuid)}")
            for (chr in svc.characteristics) {
                val chrUuid = chr.uuid.toString().lowercase()
                found.add(chrUuid)
                log("[枚举]   └ 特征 $chrUuid props=${props(chr.properties)} perm=0x%02X %s"
                    .format(chr.permissions, charHint(chrUuid)))

                if (isWritable(chr)) {
                    considerWriteChar(chr, svcUuid)
                }

                if (isNotify(chr)) {
                    // Android 的 GATT 同一时刻只允许一个未完成操作：
                    // 先串行把 CCCD 写完，等订阅全部完成后再开始读特征。
                    subscribeQueue.add(chr)
                }

                if (isReadable(chr) && isDeviceInfoUuid(chrUuid)) {
                    readQueue.add(chr to labelOf(chrUuid))
                }
            }
        }
        found.addAll(services.map { it.uuid.toString().lowercase() })

        compareWithHypothesis(found)

        log("[枚举] 待订阅 notify/indicate 特征 ${subscribeQueue.size} 个")
        subscribeNext(g)
    }

    /** 把实际发现与「OM2/3/4 公开逆向得出的假设」分开呈现，一眼就能判断 SE 是否同构。 */
    private fun compareWithHypothesis(found: Set<String>) {
        log("[假设清单] 来源：OM2/OM3/OM4 公开逆向，SE 未验证，仅作比对参考")
        for (entry in HYPOTHESIS) {
            val ok = found.contains(entry.uuid)
            log("[比对] ${entry.uuid} (${entry.what}) -> ${if (ok) "命中" else "未发现"}")
        }
        val anyFff = found.any { it.startsWith("0000fff") }
        log("[比对] 结论：${if (anyFff) "存在 fffx 厂商服务，需进一步核对特征角色" else "未见 fffx 服务，SE 可能与 OM2/3/4 不同构"}")
    }

    private data class Hypothesis(val uuid: String, val what: String)

    /**
     * 选择写入特征：优先 fff0 厂商服务里的 0xFFF5。
     * HID(0x1812) 等系统受保护服务在 Android 13+ 对普通应用禁止写入，直接跳过，
     * 否则会把命令写进错误的通道（表现为 writeCharacteristic 直接返回 false）。
     */
    @SuppressLint("MissingPermission")
    private fun considerWriteChar(chr: BluetoothGattCharacteristic, svcUuid: String) {
        val uuid = chr.uuid.toString().lowercase()
        val isVendor = uuid == WRITE_CHAR_FFF5
        if (!isVendor) {
            if (svcUuid == HID_SERVICE) {
                log("[枚举]   跳过受保护服务 HID 0x1812 的可写特征 $uuid")
                return
            }
            if (writeChar != null) return
        } else if (writeCharIsVendor) {
            return
        }
        writeChar = chr
        writeCharIsVendor = isVendor
        log("[枚举]   → 选用写入特征 $uuid" + if (isVendor) "（fff0 厂商命令通道，优先）" else "（非厂商通道，兜底）")
    }

    // ------------------------------------------------------------------ 读/写/订阅

    @SuppressLint("MissingPermission")
    private fun readNext(g: BluetoothGatt) {
        if (reading) return
        val next = readQueue.poll() ?: run {
            log("[0x180A] 读取完成")
            return
        }
        reading = true
        log("[读取] 请求 ${next.second} ${next.first.uuid}")
        try {
            g.readCharacteristic(next.first)
        } catch (e: SecurityException) {
            log("[读取] 平台拒绝 ${next.first.uuid}: ${e.message}")
            reading = false
            readNext(g)
        }
    }

    /** 订阅全部完成后才开始读 0x180A，避免 GATT 操作冲突。 */
    private fun startReads(g: BluetoothGatt) {
        if (readQueue.isEmpty()) {
            log("[0x180A] 未发现设备信息服务的可读特征")
            sendVersionQuery(g, reason = "180A 缺失")
        } else {
            log("[0x180A] 排队读取 ${readQueue.size} 个特征")
            reading = false
            readNext(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(g: BluetoothGatt) {
        val chr = subscribeQueue.poll() ?: run {
            log("[订阅] 全部完成")
            startReads(g)
            return
        }
        if (!subscribe(g, chr)) subscribeNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(g: BluetoothGatt, chr: BluetoothGattCharacteristic): Boolean {
        val enabled = try {
            g.setCharacteristicNotification(chr, true)
        } catch (e: SecurityException) {
            // Android 14+ 对 HID(0x1812) 等敏感服务的通知注册要求 BLUETOOTH_PRIVILEGED，
            // 普通应用会被拒。如实记录并跳过，不能因此中断后续订阅。
            log("[订阅] 平台拒绝 ${chr.uuid}: ${e.message}（跳过）")
            return false
        }
        if (!enabled) {
            log("[订阅] setCharacteristicNotification 失败 ${chr.uuid}")
            return false
        }
        val cccd = chr.getDescriptor(CCCD_UUID) ?: run {
            log("[订阅] 无 CCCD 描述符 ${chr.uuid}")
            return false
        }
        val enable = if (chr.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        cccd.value = enable
        try {
            g.writeDescriptor(cccd)
        } catch (e: SecurityException) {
            log("[订阅] 写 CCCD 被拒 ${chr.uuid}: ${e.message}（跳过）")
            return false
        }
        log("[订阅] 请求通知 ${chr.uuid}")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun sendVersionQuery(g: BluetoothGatt, reason: String) {
        val frame = GeneralControl.versionQuery(sequence++)
        log("[假设·未验证] DUML 版本查询（$reason），cmdSet=0x00 cmdId=0x01，帧=${Hex.format(frame)}")
        enqueueWrite(frame)
    }

    /** 发送任意已组好的 DUML 帧。 */
    fun send(bytes: ByteArray): Boolean {
        if (!isConnected()) {
            log("[发送] 未连接，丢弃 ${Hex.format(bytes)}")
            return false
        }
        log("[发送] ${Hex.format(bytes)}")
        enqueueWrite(bytes)
        return true
}

    /**
     * 实时控制帧专用：只保留最新一条，丢弃尚未发出的旧帧。
     * 云台跟随是「当前姿态纠偏」，旧指令已无意义；写不堆积才能让 33Hz 下发
     * 真正等于实际执行频率，而不是越排越长的积压队列。
     */
    private fun sendRealtime(bytes: ByteArray): Boolean {
        if (!isConnected()) return false
        enqueueWriteLatest(bytes)
        return true
    }

    /** 人脸跟随控制量的落地点：度/秒 -> int16。单位换算仍是假设，等真机标定。 */
    fun sendYawPitchSpeed(yawDegPerSec: Float, pitchDegPerSec: Float): Boolean {
        val yaw = yawDegPerSec.roundToInt().coerceIn(-1000, 1000)
        val pitch = pitchDegPerSec.roundToInt().coerceIn(-1000, 1000)
        val frame = GimbalControl.speed(yaw, pitch, rollSpeed = 0, timeSec = 1, sequence = sequence++)
        return send(frame)
    }

    /**
     * 人脸跟随专用：yaw 单轴速度帧，value 单位 0.1°/s（实测标定），限幅 ±1200。
     * 走实时通道（旧帧丢弃），日志按 300ms / 200 单位变化限速，避免 33Hz 刷爆 logcat。
     */
    fun sendYawSpeedRaw(value: Int): Boolean {
        val v = value.coerceIn(-1200, 1200)
        val frame = GimbalControl.rotation(
            yaw = v,
            pitch = 0,
            roll = 0,
            mode = GimbalControl.RotationMode.SPEED,
            timeSec = 1,
            sequence = sequence++
        )
        val now = SystemClock.elapsedRealtime()
        if (now - lastYawLogMs >= 300L || kotlin.math.abs(v - lastYawLogged) >= 200) {
            lastYawLogMs = now
            lastYawLogged = v
            log("[跟随] value=$v 深度=${writeQueue.size} 帧=${Hex.format(frame)}")
        }
        return sendRealtime(frame)
    }

    /**
     * 俯仰轴（实测确认可用）：cs=0x04 / id=0x14（相对角度模式），
     * value 单位 0.1°（+300 实测转 30.1°），正负号对应抬/压。
     */
    fun sendPitchAngleStep(value: Int): Boolean {
        val v = value.coerceIn(-600, 600)
        val frame = GimbalControl.rotation(
            yaw = 0,
            pitch = v,
            roll = 0,
            mode = GimbalControl.RotationMode.RELATIVE,
            timeSec = 1,
            sequence = sequence++
        )
        val now = SystemClock.elapsedRealtime()
        if (now - lastPitchLogMs >= 1000L) {
            lastPitchLogMs = now
            log("[跟随] pitch=$v 深度=${writeQueue.size} 帧=${Hex.format(frame)}")
        }
        return sendRealtime(frame)
    }

    /** 标定专用：按给定 cmdSet / cmdId / payload 组一帧发出，CRC 由 Duml 统一生成。 */
    fun sendRawFrame(cmdSet: Int, cmdId: Int, payload: ByteArray): Boolean {
        val frame = Duml.Frame(
            sender = Duml.DEFAULT_SENDER,
            receiver = Duml.DEFAULT_RECEIVER,
            sequence = sequence++,
            flags = 0x40,
            cmdSet = cmdSet,
            cmdId = cmdId,
            payload = payload
        ).toBytes()
        log("[M3] t=${System.currentTimeMillis()} cs=$cmdSet id=$cmdId 帧=${Hex.format(frame)}")
        return send(frame)
    }

    /**
     * M3 单轴开环实验专用：按给定 int16 数值直接组一帧 cs=0x04 / id=0x0C 发出。
     * 不做度/秒换算、不自动重复；「发完补零速度」由调用方负责。
     * 返回 false 表示未连接。
     */
    fun sendSpeedRaw(yaw: Int, pitch: Int, roll: Int = 0, timeSec: Int = 1): Boolean {
        val frame = GimbalControl.rotation(
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            mode = GimbalControl.RotationMode.SPEED,
            timeSec = timeSec,
            sequence = sequence++
        )
        log("[M3] t=${System.currentTimeMillis()} yaw=$yaw pitch=$pitch roll=$roll time=$timeSec 帧=${Hex.format(frame)}")
        return send(frame)
    }

    /** 实验窗口内把通知日志限速调低（默认 500ms），便于看姿态字段是否跟着变。 */
    fun setNotifyLogInterval(ms: Long) {
        notifyLogIntervalMs = ms
    }

    @SuppressLint("MissingPermission")
    private fun enqueueWrite(bytes: ByteArray) {
        val g = gatt ?: return
        val chr = writeChar ?: run { log("[发送] 未找到可写特征"); return }
        val chunkSize = maxOf(mtu - 3, 4)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + chunkSize, bytes.size)
            writeQueue.add(bytes.copyOfRange(i, end))
            i = end
        }
        writeNext(g)
    }

    /**
     * 实时帧：清掉尚未发出的旧指令后只放最新一条。
     * 若上一帧还在写，则等它的 onCharacteristicWrite 回来后再发这一条；
     * 期间新的实时帧会再次覆盖队列，所以最终发出的永远是最新控制量。
     */
    @SuppressLint("MissingPermission")
    private fun enqueueWriteLatest(bytes: ByteArray) {
        val g = gatt ?: return
        if (writeChar == null) return
        if (writeQueue.isNotEmpty()) {
            realtimeDropped += writeQueue.size
            writeQueue.clear()
        }
        val chunkSize = maxOf(mtu - 3, 4)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + chunkSize, bytes.size)
            writeQueue.add(bytes.copyOfRange(i, end))
            i = end
        }
        writeNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun writeNext(g: BluetoothGatt) {
        if (writing) return
        val chunk = writeQueue.poll() ?: return
        val chr = writeChar ?: return
        writing = true
        chr.writeType = if (chr.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        chr.value = chunk
        if (!g.writeCharacteristic(chr)) {
            writing = false
            log("[写入] writeCharacteristic 返回 false")
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun isWritable(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private fun isNotify(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun isReadable(c: BluetoothGattCharacteristic) =
        c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

    private fun props(p: Int): String {
        val names = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names += "BROADCAST"
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "WRITE_NO_RESP"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "INDICATE"
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names += "SIGNED_WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) names += "EXT_PROPS"
        return if (names.isEmpty()) "0x%02X".format(p) else names.joinToString("|")
    }

    private fun isDeviceInfoUuid(uuid: String): Boolean =
        DEVICE_INFO_CHARS.containsKey(uuid) || uuid.startsWith("00002a2")

    private fun labelOf(uuid: String): String = DEVICE_INFO_CHARS[uuid] ?: uuid

    private fun serviceHint(uuid: String): String = when (uuid) {
        DIS_SERVICE -> "<标准设备信息 0x180A>"
        BATTERY_SERVICE -> "<标准电池 0x180F>"
        GENERIC_ACCESS -> "<标准 GAP 0x1800>"
        GENERIC_ATTRIBUTE -> "<标准 GATT 0x1801>"
        else -> ""
    }

    private fun charHint(uuid: String): String = DEVICE_INFO_CHARS[uuid] ?: ""

    /** 0x2A29 等值可能是 UTF-8 字符串，其他保持 hex，两种都打。 */
    private fun renderReadValue(label: String, value: ByteArray): String {
        val text = runCatching { String(value, Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { s -> s.all { it.code in 0x20..0x7E } }
        return if (text != null) "hex=[${Hex.format(value)}] text=\"$text\"" else "hex=[${Hex.format(value)}]"
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        callback?.onLog(line)
    }

    companion object {
        private const val TAG = "BleManager"

        private const val DIS_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
        private const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        private const val GENERIC_ACCESS = "00001800-0000-1000-8000-00805f9b34fb"
        private const val GENERIC_ATTRIBUTE = "00001801-0000-1000-8000-00805f9b34fb"

        /** HID 服务在 Android 13+ 对普通应用禁止写入。 */
        private const val HID_SERVICE = "00001812-0000-1000-8000-00805f9b34fb"

        /** fff0 厂商服务里的命令写入通道（OMSE 实测 Mimo 就写在这个 handle）。 */
        private const val WRITE_CHAR_FFF5 = "0000fff5-0000-1000-8000-00805f9b34fb"

        private val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 标准设备信息服务 0x180A 的特征。 */
        private val DEVICE_INFO_CHARS = linkedMapOf(
            "00002a29-0000-1000-8000-00805f9b34fb" to "厂商 Manufacturer",
            "00002a24-0000-1000-8000-00805f9b34fb" to "型号 Model",
            "00002a25-0000-1000-8000-00805f9b34fb" to "序列号 Serial",
            "00002a26-0000-1000-8000-00805f9b34fb" to "固件版本 Firmware",
            "00002a27-0000-1000-8000-00805f9b34fb" to "硬件版本 Hardware",
            "00002a28-0000-1000-8000-00805f9b34fb" to "软件版本 Software"
        )

        /**
         * 预期清单：仅来自 OM2/OM3/OM4 的公开逆向资料，SE 尚未验证。
         * 只用于日志比对，不参与任何连接/发送决策。
         */
        private val HYPOTHESIS = listOf(
            Hypothesis("0000fff0-0000-1000-8000-00805f9b34fb", "假设：厂商自定义服务"),
            Hypothesis("0000fff4-0000-1000-8000-00805f9b34fb", "假设：通知通道"),
            Hypothesis("0000fff5-0000-1000-8000-00805f9b34fb", "假设：写入通道")
        )
    }
}
