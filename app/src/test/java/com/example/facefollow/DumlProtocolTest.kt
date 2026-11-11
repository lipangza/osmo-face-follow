package com.example.facefollow.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DUML 协议离线自检。不依赖真机，只验证编码/解码的一致性。
 *
 * 已知向量来源：
 *  - 帧头 CRC8：公开逆向资料里真实抓包头部恒为 55 15 04 A9，A9 即 CRC8(55 15 04)。
 *  - CRC16：同一份资料用 reveng 反推参数时给出的 4 条样本帧（init 0x30FB 命令族），
 *    这里用来验证查表实现与多项式/初值一致。
 *  - 速度指令帧：由参考实现的字节布局复算，断言长度/头部/字段位置，并做往返校验。
 */
class DumlProtocolTest {

    @Test
    fun `crc8 matches real captured header`() {
        val header = byteArrayOf(0x55, 0x15, 0x04)
        assertEquals(0xA9, Duml.crc8(header))
    }

    @Test
    fun `crc16 matches reference sample frames`() {
        // 每行：数据(不含 CRC) + 尾部 CRC(小端)。
        // 注意：reveng 输出的 init=0x30FB 是「非反射」写法，反射实现里要用按位反转后的 0xDF0C，
        // 这也正是参考驱动里写死的初值。
        val samples = listOf(
            "02031c004003f8ef979092ef3963f5" to 0xAA5E,
            "02044700400414c20100002000050f" to 0x63EB,
            "02045e004004143efe00002000050f" to 0xE7A2,
            "02047300400414c20100002000050f" to 0x9A2F
        )
        samples.forEach { (dataHex, expected) ->
            val data = Hex.parse(dataHex)
            assertEquals("CRC16 mismatch for $dataHex", expected, Duml.crc16(data, init = 0xDF0C))
        }
    }

    @Test
    fun `speed frame layout matches reference implementation`() {
        // yaw=+50, pitch=0, roll=0, SPEED, 2 秒
        val frame = GimbalControl.rotation(yaw = 50, pitch = 0, roll = 0,
            mode = GimbalControl.RotationMode.SPEED, timeSec = 2)

        assertEquals(21, frame.size)
        assertEquals(0x15, frame[1].toInt() and 0xFF)          // 总长度
        assertEquals(0xA9, frame[3].toInt() and 0xFF)          // 头部 CRC8
        assertEquals(0x02, frame[4].toInt() and 0xFF)          // sender
        assertEquals(0x04, frame[5].toInt() and 0xFF)          // receiver
        assertEquals(0x01, frame[6].toInt() and 0xFF)          // seq lo = 1
        assertEquals(0x00, frame[7].toInt() and 0xFF)          // seq hi
        assertEquals(0x40, frame[8].toInt() and 0xFF)          // flags（跟官方写帧）
        assertEquals(0x04, frame[9].toInt() and 0xFF)          // cmdSet
        assertEquals(GimbalControl.CMD_SPEED, frame[10].toInt() and 0xFF)

        // payload: yaw(2) roll(2) pitch(2) mode time
        assertEquals(50, readInt16(frame, 11))
        assertEquals(0, readInt16(frame, 13))                  // roll
        assertEquals(0, readInt16(frame, 15))                  // pitch
        assertEquals(0x80, frame[17].toInt() and 0xFF)         // SPEED
        assertEquals(2, frame[18].toInt() and 0xFF)            // time
        println("sample speed frame = ${Hex.format(frame)}")
    }

    @Test
    fun `rotation payload keeps yaw-roll-pitch order`() {
        val frame = GimbalControl.rotation(yaw = 10, pitch = 20, roll = 30,
            mode = GimbalControl.RotationMode.ABSOLUTE, timeSec = 1)
        assertEquals(10, readInt16(frame, 11))
        assertEquals(30, readInt16(frame, 13))
        assertEquals(20, readInt16(frame, 15))
        assertEquals(GimbalControl.CMD_ROTATE, frame[10].toInt() and 0xFF)
    }

    @Test
    fun `frame round trips through parse`() {
        val bytes = GimbalControl.speed(100, -40, 0, timeSec = 3, sequence = 7)
        val parsed = Duml.parse(bytes)
        assertNotNull(parsed)
        parsed!!
        assertEquals(0x04, parsed.cmdSet)
        assertEquals(GimbalControl.CMD_SPEED, parsed.cmdId)
        assertEquals(7, parsed.sequence)
        assertEquals(100, readInt16(parsed.payload, 0))
        assertEquals(0, readInt16(parsed.payload, 2))
        assertEquals(-40, readInt16(parsed.payload, 4))
        assertTrue(parsed.payload.contentEquals(byteArrayOf(
            100, 0, 0, 0, (256 - 40).toByte(), 0xFF.toByte(), 0x80.toByte(), 3
        )))
    }

    @Test
    fun `parse rejects corrupted frame`() {
        val bytes = GimbalControl.speed(10, 10, 0)
        bytes[12] = (bytes[12].toInt() xor 0x01).toByte() // 改 payload -> CRC 失效
        assertNull(Duml.parse(bytes))
    }

    @Test
    fun `stream parser handles chunked notify payload`() {
        val a = GimbalControl.speed(10, 5, 0, timeSec = 1, sequence = 1)
        val b = GimbalControl.speed(0, 0, 0, timeSec = 0, sequence = 2)
        val stream = byteArrayOf(0x12, 0x34) + a + b  // 前面带噪声字节
        val parser = DumlStreamParser()

        val frames = mutableListOf<Duml.Frame>()
        var i = 0
        while (i < stream.size) {
            val step = minOf(5, stream.size - i)
            frames += parser.feed(stream.copyOfRange(i, i + step))
            i += step
        }
        assertEquals(2, frames.size)
        assertEquals(1, frames[0].sequence)
        assertEquals(2, frames[1].sequence)
        assertEquals(0, readInt16(frames[1].payload, 0))
    }

    @Test
    fun `hex helper round trips`() {
        val bytes = GimbalControl.speed(-1, 1, 0)
        val text = Hex.format(bytes)
        assertTrue(Hex.parse(text).contentEquals(bytes))
    }

    private fun readInt16(data: ByteArray, offset: Int): Int {
        val lo = data[offset].toInt() and 0xFF
        val hi = (data[offset + 1].toInt() and 0xFF) shl 8
        val raw = lo or hi
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }
}
