package com.example.facefollow.eyelock

import android.content.Context
import android.content.Intent

/**
 * 可插拔触发动作。新增动作只需实现本接口并在 EyeLockController 里注册。
 *
 * 约定：
 *  - availability() 必须如实反映「当前设备/当前权限下能否真的执行」，
 *    不能执行时给出原因与可跳转的补救入口（setupIntent / fallbackIntent）。
 *  - execute() 只有在动作真的下发成功时才返回 ok=true，禁止伪造成功日志。
 */
interface EyeTriggerAction {
    val id: String

    /** 供 UI 展示的短名称 */
    val label: String

    fun availability(context: Context): ActionAvailability

    fun execute(context: Context): ActionResult
}

/**
 * @param canExecuteDirectly 当前是否可直接执行
 * @param detail 原因/现状说明（会原样显示给用户）
 * @param setupIntent 需要用户完成的授权流程（如激活设备管理员）
 * @param fallbackIntent 能力受限时唯一可用的降级入口（如跳系统省电设置页）
 */
data class ActionAvailability(
    val canExecuteDirectly: Boolean,
    val detail: String,
    val setupIntent: Intent? = null,
    val fallbackIntent: Intent? = null
)

data class ActionResult(val ok: Boolean, val detail: String)
