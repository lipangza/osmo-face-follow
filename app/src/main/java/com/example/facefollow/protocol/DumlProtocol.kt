package com.example.facefollow.protocol

/**
 * DJI DUML 帧的组帧/解帧 + 云台转动指令构造。
 *
 * 纯 Kotlin/JVM，不依赖 Android，可在本地单元测试里离线自检。
 *
 * 帧结构（与公开的 Osmo Mobile 逆向资料一致，已在 OM2/OM3/OM4 实机验证过；
 * OM SE 未验证，字段语义仍以真机回包为准）：
 *
 *   [0x55][总长度][版本 0x04][CRC8] + [sender][receiver][seq lo][seq hi][flags][cmdSet][cmdId] + payload + [CRC16 lo][CRC16 hi]
 *
 *  - 总长度 = 整个帧的字节数（含头与 CRC），所以 8 字节 payload 时为 21 = 0x15。
 *  - CRC8 只覆盖前 3 个字节 (0x55, 长度, 版本)，反射多项式 0x31、初值 0x77。
 *    （OMSE 实测 5 帧全部命中。）
 *  - CRC16 覆盖**从 0x55 到 payload 末尾**（即整帧去掉最后 2 字节 CRC），
 *    反射多项式 0x31（查表用 0x8408）、初值 0x3692。这是从 OMSE 实测帧反推并按 6 条样本
 *    逐字节复现验证的结论（旧的 0xDF0C/仅 body+payload 假设是错的，组出来的帧会被丢弃）。
 *  - 云台转动 payload 顺序是 yaw / roll / pitch（注意 roll 在中间），各 int16 小端，再跟 mode、time 各 1 字节。
 *  - body 的 flags 在 OMSE 实机抓到的官方 App 写帧里是 0x40；本工程早期用 0x00 也能被接受，
 *    这里统一跟官方写帧取 0x40。
 *
 * 字段命名说明（诚实标注）：
 *  - `cmdSet = 0x04` 有较明确的协议族含义（云台）。
 *  - `cmdId` 取 0x0C（速度模式）/ 0x14（角度模式）已由实机动作验证；各字段语义仍无官方文档。
 *  - body 里第 3、4 字节是自增序号（不是 cmd 0x01）。
 */
object Duml {

    const val MAGIC: Byte = 0x55
    const val VERSION: Byte = 0x04
    const val HEADER_SIZE = 4
    const val BODY_SIZE = 7
    const val CRC_SIZE = 2

    const val DEFAULT_SENDER = 0x02
    const val DEFAULT_RECEIVER = 0x04

    // ---- CRC ----

    /** 反射多项式 0x8C（对应标准多项式 0x31），初值 0x77。 */
    private val CRC8_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c shr 1) xor 0x8C else c shr 1 }
        c and 0xFF
    }

    /** 反射多项式 0x8408（对应标准多项式 0x1021）。 */
    private val CRC16_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c shr 1) xor 0x8408 else c shr 1 }
        c and 0xFFFF
    }

    fun crc8(data: ByteArray, offset: Int = 0, length: Int = data.size, init: Int = 0x77): Int {
        var crc = init and 0xFF
        for (i in offset until offset + length) {
            crc = CRC8_TABLE[(data[i].toInt() xor crc) and 0xFF]
        }
        return crc
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size, init: Int = 0x3692): Int {
        var crc = init and 0xFFFF
        for (i in offset until offset + length) {
            crc = (crc shr 8) xor CRC16_TABLE[(data[i].toInt() xor crc) and 0xFF]
        }
        return crc and 0xFFFF
    }

    // ---- 帧 ----

    data class Frame(
        val sender: Int,
        val receiver: Int,
        val sequence: Int,
        val flags: Int,
        val cmdSet: Int,
        val cmdId: Int,
        val payload: ByteArray
    ) {
        fun toBytes(): ByteArray {
            val total = HEADER_SIZE + BODY_SIZE + payload.size + CRC_SIZE
            require(total <= 0xFF) { "帧过长: $total" }

            val out = ByteArray(total)
            out[0] = MAGIC
            out[1] = total.toByte()
            out[2] = VERSION
            out[3] = Duml.crc8(out, 0, 3).toByte()

            out[4] = sender.toByte()
            out[5] = receiver.toByte()
            out[6] = (sequence and 0xFF).toByte()
            out[7] = ((sequence shr 8) and 0xFF).toByte()
            out[8] = flags.toByte()
            out[9] = cmdSet.toByte()
            out[10] = cmdId.toByte()
            payload.copyInto(out, HEADER_SIZE + BODY_SIZE)

            val crc = Duml.crc16(out, 0, total - CRC_SIZE)
            out[total - 2] = (crc and 0xFF).toByte()
            out[total - 1] = ((crc shr 8) and 0xFF).toByte()
            return out
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return sender == other.sender && receiver == other.receiver &&
                sequence == other.sequence && flags == other.flags &&
                cmdSet == other.cmdSet && cmdId == other.cmdId &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = sender
            result = 31 * result + receiver
            result = 31 * result + sequence
            result = 31 * result + flags
            result = 31 * result + cmdSet
            result = 31 * result + cmdId
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /** 解析单帧；帧不完整或 CRC 校验失败返回 null。 */
    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE + BODY_SIZE + CRC_SIZE) return null
        if (bytes[0] != MAGIC) return null
        val total = bytes[1].toInt() and 0xFF
        if (total != bytes.size) return null
        if (bytes[2] != VERSION) return null
        if (crc8(bytes, 0, 3).toInt() != (bytes[3].toInt() and 0xFF)) return null

        val payloadSize = total - HEADER_SIZE - BODY_SIZE - CRC_SIZE
        val crcPos = HEADER_SIZE + BODY_SIZE + payloadSize
        val crc = crc16(bytes, 0, total - CRC_SIZE)
        val got = (bytes[crcPos].toInt() and 0xFF) or ((bytes[crcPos + 1].toInt() and 0xFF) shl 8)
        if (crc != got) return null

        return Frame(
            sender = bytes[4].toInt() and 0xFF,
            receiver = bytes[5].toInt() and 0xFF,
            sequence = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8),
            flags = bytes[8].toInt() and 0xFF,
            cmdSet = bytes[9].toInt() and 0xFF,
            cmdId = bytes[10].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_SIZE + BODY_SIZE, crcPos)
        )
    }
}

/**
 * BLE 通知是分包到达的，必须按流解析：缓存字节，遇到完整 DUML 帧就切出来。
 */
class DumlStreamParser {

    private val buffer = ArrayList<Byte>(512)

    fun feed(chunk: ByteArray): List<Duml.Frame> {
        chunk.forEach { buffer.add(it) }
        val frames = mutableListOf<Duml.Frame>()
        while (true) {
            val start = indexOfMagic()
            if (start < 0) break
            if (start > 0) discard(start)
            if (buffer.size < Duml.HEADER_SIZE) break
            val total = buffer[1].toInt() and 0xFF
            if (total < Duml.HEADER_SIZE + Duml.BODY_SIZE + Duml.CRC_SIZE) {
                discard(1)  // 长度字段异常，跳过这个 0x55 重新找
                continue
            }
            if (buffer.size < total) break
            val candidate = ByteArray(total)
            for (i in 0 until total) candidate[i] = buffer[i]
            val frame = Duml.parse(candidate)
            if (frame == null) {
                discard(1)
                continue
            }
            frames.add(frame)
            discard(total)
        }
        return frames
    }

    private fun indexOfMagic(): Int {
        for (i in buffer.indices) if (buffer[i] == Duml.MAGIC) return i
        return -1
    }

    private fun discard(count: Int) {
        repeat(count) { if (buffer.isNotEmpty()) buffer.removeAt(0) }
    }
}

/** 云台命令构造。 */
object GimbalControl {

    const val CMD_SET = 0x04
    const val CMD_SPEED = 0x0C  // 参考实现里「速度模式」用的 cmdId
    const val CMD_ROTATE = 0x14 // 参考实现里「角度/相对/绝对」用的 cmdId

    enum class RotationMode(val code: Int) {
        RELATIVE(0x04),
        ABSOLUTE(0x05),
        SPEED(0x80)
    }

    /**
     * 构造云台转动帧。
     *
     * @param yaw   速度模式下为 yaw 速度（单位待真机标定，参考实现直接写 int16）
     * @param pitch pitch 值
     * @param roll  roll 值
     * @param timeSec 持续时间（1 字节，单位待真机标定）
     * @param sequence 序号，每帧自增
     *
     * 注意序列化顺序是 yaw / roll / pitch，不是 yaw / pitch / roll。
     */
    fun rotation(
        yaw: Int,
        pitch: Int,
        roll: Int = 0,
        mode: RotationMode = RotationMode.SPEED,
        timeSec: Int = 1,
        sequence: Int = 1,
        sender: Int = Duml.DEFAULT_SENDER,
        receiver: Int = Duml.DEFAULT_RECEIVER
    ): ByteArray {
        require(timeSec in 0..255) { "timeSec 超出 1 字节: $timeSec" }
        require(yaw in Short.MIN_VALUE..Short.MAX_VALUE) { "yaw 超出 int16: $yaw" }
        require(pitch in Short.MIN_VALUE..Short.MAX_VALUE) { "pitch 超出 int16: $pitch" }
        require(roll in Short.MIN_VALUE..Short.MAX_VALUE) { "roll 超出 int16: $roll" }

        val payload = ByteArray(8)
        payload[0] = (yaw and 0xFF).toByte()
        payload[1] = ((yaw shr 8) and 0xFF).toByte()
        payload[2] = (roll and 0xFF).toByte()
        payload[3] = ((roll shr 8) and 0xFF).toByte()
        payload[4] = (pitch and 0xFF).toByte()
        payload[5] = ((pitch shr 8) and 0xFF).toByte()
        payload[6] = mode.code.toByte()
        payload[7] = timeSec.toByte()

        val cmdId = if (mode == RotationMode.SPEED) CMD_SPEED else CMD_ROTATE
        return Duml.Frame(
            sender = sender,
            receiver = receiver,
            sequence = sequence,
            // OMSE 实测写帧 flags 恒为 0x40，这里跟随实测，不用参考实现的 0x00。
            flags = 0x40,
            cmdSet = CMD_SET,
            cmdId = cmdId,
            payload = payload
        ).toBytes()
    }

    /** 速度模式的便捷入口，全零表示让云台停下。 */
    fun speed(yawSpeed: Int, pitchSpeed: Int, rollSpeed: Int = 0, timeSec: Int = 1, sequence: Int = 1): ByteArray =
        rotation(yawSpeed, pitchSpeed, rollSpeed, RotationMode.SPEED, timeSec, sequence)
}

/** 通用命令（版本查询等）。命令号来自 DUML 通用命令族的常见约定，SE 上未验证。 */
object GeneralControl {

    const val CMD_SET_GENERAL = 0x00
    const val CMD_GET_VERSION = 0x01

    /**
     * 版本查询帧，作为 0x180A 设备信息服务缺失时的兜底。
     * 注意：sender/receiver/命令号都是待验证假设，发送前会被明确标注，不做成功断言。
     */
    fun versionQuery(sequence: Int = 0x01): ByteArray =
        Duml.Frame(
            sender = Duml.DEFAULT_SENDER,
            receiver = Duml.DEFAULT_RECEIVER,
            sequence = sequence,
            flags = 0x00,
            cmdSet = CMD_SET_GENERAL,
            cmdId = CMD_GET_VERSION,
            payload = ByteArray(0)
        ).toBytes()
}

object Hex {
    fun format(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun parse(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace("\n", "")
        require(clean.length % 2 == 0) { "hex 长度必须为偶数" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
