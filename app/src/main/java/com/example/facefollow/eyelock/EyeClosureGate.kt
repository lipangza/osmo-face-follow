package com.example.facefollow.eyelock

/**
 * 抗误触状态机（纯 Kotlin，无 Android 依赖，可跑 JVM 单测）。
 *
 * 触发必须同时满足：
 *  1) 连续闭眼保持 >= holdMs（默认 1350ms，落在要求的 1.2~1.5s 区间内）；
 *  2) 上一次触发后已过 cooldownMs（默认 8s），避免反复锁屏；
 *  3) 触发后必须先「睁眼」一次重新武装，防止闭着眼不放导致连环触发。
 *
 * 调用方每帧把「双眼是否都闭上」和时间戳（毫秒，单调时钟）喂进来即可。
 */
class EyeClosureGate(
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var closedSinceMs: Long? = null
    private var lastTriggerMs: Long = Long.MIN_VALUE
    private var armed = true

    /** @return true 表示本帧应触发动作 */
    fun onSample(eyesClosed: Boolean, nowMs: Long): Boolean {
        if (!eyesClosed) {
            closedSinceMs = null
            armed = true
            return false
        }
        val since = closedSinceMs
        if (since == null) {
            closedSinceMs = nowMs
            return false
        }
        if (!armed) return false
        if (nowMs - since < holdMs) return false
        if (lastTriggerMs != Long.MIN_VALUE && nowMs - lastTriggerMs < cooldownMs) return false

        armed = false
        lastTriggerMs = nowMs
        return true
    }

    /** 当前连续闭眼时长，用于界面展示；未闭眼返回 0。 */
    fun closedDurationMs(nowMs: Long): Long {
        val since = closedSinceMs ?: return 0L
        return (nowMs - since).coerceAtLeast(0L)
    }

    fun isArmed(): Boolean = armed

    fun reset() {
        closedSinceMs = null
        lastTriggerMs = Long.MIN_VALUE
        armed = true
    }

    companion object {
        const val DEFAULT_HOLD_MS = 1350L
        const val DEFAULT_COOLDOWN_MS = 8_000L
    }
}
