package com.example.facefollow.eyelock

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log

/**
 * 闭眼触发总控：默认关闭（SharedPreferences 里 eye_lock=false），
 * 必须显式 [enabled] = true 才会真正下发动作。
 *
 * 主界面接线：每帧把正立位图交给 [processFrame]，由本类做
 * MediaPipe 推理 -> 抗误触判定 -> 锁屏动作分派。
 *
 * 线程：processFrame 需在单一后台线程调用（内部无锁，靠串行调用）。
 */
class EyeLockController(
    context: Context
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gate = EyeClosureGate()

    private var detector: EyeStateDetector? = null
    private var detectorBroken = false
    // 只保留「锁屏」一个动作（省电/清内存已按产品决定移除）
    private val actions: MutableList<EyeTriggerAction> = mutableListOf(
        LockScreenAction()
    )

    /** 动作开关，只有锁屏一项。 */
    private val enabledActionIds: MutableSet<String> =
        prefs.getStringSet(KEY_ACTIONS, setOf(LockScreenAction().id))?.toMutableSet()
            ?: mutableSetOf(LockScreenAction().id)

    private var lastActionLog: String = ""

    /** 回调给 UI/日志用；可为空。 */
    var onEvent: ((String) -> Unit)? = null

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            gate.reset()
            if (!value) release()
            emit("闭眼触发 ${if (value) "已开启" else "已关闭"}（默认关闭，需用户显式打开）")
        }

    fun actions(): List<EyeTriggerAction> = actions.toList()

    fun isActionEnabled(id: String): Boolean = id in enabledActionIds

    fun setActionEnabled(id: String, on: Boolean) {
        if (on) enabledActionIds.add(id) else enabledActionIds.remove(id)
        prefs.edit().putStringSet(KEY_ACTIONS, enabledActionIds).apply()
    }

    fun availability(id: String): ActionAvailability? =
        actions.firstOrNull { it.id == id }?.availability(appContext)

    /** 锁屏动作的设备管理员激活入口；已激活时返回 null。 */
    fun lockSetupIntent(): Intent? = availability(LockScreenAction().id)?.setupIntent

    fun lockReady(): Boolean = availability(LockScreenAction().id)?.canExecuteDirectly == true

    /**
     * 一帧入口：正立位图 -> MediaPipe 推理 -> 抗误触判定 -> 锁屏。
     * 功能关闭时直接返回 null，不跑推理（避免无谓 CPU 开销）。
     * 必须在单一后台线程调用。
     * @return 本帧眼部状态（供界面显示 EAR），未开启/推理失败时为 null
     */
    fun processFrame(bitmap: Bitmap, nowMs: Long): EyeState? {
        if (!enabled || detectorBroken) return null
        val det = detector ?: runCatching { EyeStateDetector(appContext) }.getOrElse { e ->
            detectorBroken = true
            Log.e(TAG, "EyeStateDetector 初始化失败: ${e.message}", e)
            emit("闭眼检测不可用：${e.message}")
            return null
        }.also { detector = it }

        val state = det.detect(bitmap)
        lastState = state
        onEyeState(state, nowMs)
        return state
    }

    /** 最近一帧眼部状态，供界面显示 EAR 读数。 */
    var lastState: EyeState? = null
        private set

    fun release() {
        detector?.release()
        detector = null
        lastState = null
    }

    /**
     * 喂入一帧眼部状态（与 [processFrame] 二选一，后者已含推理）。
     * @param nowMs 单调时钟毫秒（SystemClock.elapsedRealtime()）
     * @return 本帧触发的动作结果；未触发返回 null
     */
    fun onEyeState(state: EyeState, nowMs: Long): List<ActionResult>? {
        if (!enabled) {
            gate.reset()
            return null
        }
        // 人脸太远/太小说明不是本人在镜头前，直接视为“睁眼”以免误触发
        val closed = state.bothClosed && state.faceWidthRatio >= MIN_FACE_RATIO
        val trigger = gate.onSample(closed, nowMs)

        if (!trigger) return null

        val results = mutableListOf<ActionResult>()
        actions.filter { it.id in enabledActionIds }.forEach { action ->
            val avail = action.availability(appContext)
            if (!avail.canExecuteDirectly) {
                val msg = "${action.label}未执行：${avail.detail}"
                Log.w(TAG, msg)
                emit(msg)
                results += ActionResult(false, msg)
                return@forEach
            }
            val r = action.execute(appContext)
            Log.i(TAG, "${action.label}: ok=${r.ok} ${r.detail}")
            emit("${action.label}: ${if (r.ok) "成功" else "失败"} - ${r.detail}")
            results += r
        }
        lastActionLog = results.joinToString(" | ") { "${it.ok}:${it.detail}" }
        gate.reset() // 触发后重新计时，配合 cooldown 防连环
        return results
    }

    fun lastLog(): String = lastActionLog

    private fun emit(line: String) {
        onEvent?.invoke(line)
    }

    companion object {
        private const val TAG = "EyeLockController"
        private const val PREFS = "eye_lock"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACTIONS = "actions"
        private const val MIN_FACE_RATIO = 0.08f
    }
}
