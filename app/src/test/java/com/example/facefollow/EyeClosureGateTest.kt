package com.example.facefollow

import com.example.facefollow.eyelock.EyeClosureGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抗误触判定：连续闭眼 >= holdMs 才触发；冷却期内不再触发；必须重新睁眼才重新武装。
 */
class EyeClosureGateTest {

    @Test
    fun shortBlinkDoesNotTrigger() {
        val gate = EyeClosureGate()
        var t = 1000L
        // 闭眼 1.2s（1200ms < 1350ms）
        repeat(121) {
            assertFalse("1.2s 内不应触发", gate.onSample(true, t))
            t += 10
        }
    }

    @Test
    fun longClosureTriggersOnceAtHoldBoundary() {
        val gate = EyeClosureGate()
        val start = 1000L
        var triggeredAt = -1L
        for (i in 0..200) {
            val now = start + i * 10L
            if (gate.onSample(true, now)) {
                triggeredAt = now - start
                break
            }
        }
        // 第一次采样只记录起点，因此触发点应为 >= holdMs 的第一个采样
        assertTrue("应在 1.35s 附近触发，实际=$triggeredAt", triggeredAt >= 1350L)
        assertTrue("不应过晚触发，实际=$triggeredAt", triggeredAt <= 1360L)
    }

    @Test
    fun noRepeatWhileStillClosed() {
        val gate = EyeClosureGate()
        var t = 1000L
        var count = 0
        // 连续闭眼 10s，且期间一次都没睁眼
        repeat(1000) {
            if (gate.onSample(true, t)) count++
            t += 10
        }
        assertEquals("持续闭眼 10s 只应触发 1 次", 1, count)
        assertFalse("未重新睁眼时不应重新武装", gate.isArmed())
        assertTrue("闭眼计时仍在走", gate.closedDurationMs(t) > 0L)
    }

    @Test
    fun cooldownBlocksImmediateRetrigger() {
        val gate = EyeClosureGate()
        var t = 1000L
        // 第一次：闭眼 -> 触发
        repeat(140) { gate.onSample(true, t); t += 10 }
        // 睁眼 0.2s 重新武装
        repeat(20) { gate.onSample(false, t); t += 10 }
        // 再闭眼 1.4s，但距上次触发 < 8s 冷却
        var anyTrigger = false
        repeat(140) { if (gate.onSample(true, t)) anyTrigger = true; t += 10 }
        assertFalse("冷却期（8s）内不应再次触发", anyTrigger)
    }

    @Test
    fun retriggerAfterCooldown() {
        val gate = EyeClosureGate()
        var t = 1000L
        repeat(140) { gate.onSample(true, t); t += 10 }   // 第一次触发 @ ~2350ms
        repeat(20) { gate.onSample(false, t); t += 10 }
        // 推进到超过冷却 + 再闭眼 1.4s
        t += 8000
        var triggered = false
        repeat(140) { if (gate.onSample(true, t)) triggered = true; t += 10 }
        assertTrue("冷却结束后应可再次触发", triggered)
    }
}
