package com.example.facefollow.eyelock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员接收器：只申请 force-lock 一项策略（用于 DevicePolicyManager.lockNow()）。
 *
 * 激活方式（二选一）：
 *  - 用户手动：跳 [LockScreenAction.setupIntent] 里的 ADD_DEVICE_ADMIN 界面点「激活」；
 *  - adb（真机验证用）：
 *      adb shell dpm set-active-admin --user 0 \
 *        com.example.facefollow/com.example.facefollow.eyelock.EyeLockDeviceAdminReceiver
 */
class EyeLockDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
    }

    companion object {
        private const val TAG = "EyeLockAdmin"

        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, EyeLockDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            return try {
                dpm.isAdminActive(component(context))
            } catch (e: Exception) {
                false
            }
        }

        fun requestAdminIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "仅用于「闭眼触发」功能调用 lockNow() 锁屏；不会授予擦除数据、改密码等权限。"
            )
        }
    }
}

/**
 * 闭眼触发的唯一动作：锁屏（DevicePolicyManager.lockNow()）。
 *
 * 前提：应用被用户/企业激活为设备管理员，且 manifest 中的 device_admin_eyelock.xml 声明了 force-lock。
 * 无管理员权限时 lockNow() 会抛 SecurityException —— 这里如实返回失败，不做任何伪成功处理，
 * 并通过 setupIntent 给出激活入口。
 *
 * 未激活时的替代路径：普通应用没有其它无需特权的锁屏 API
 * （GLOBAL_ACTION_LOCK_SCREEN 只有 AccessibilityService 能派发）。
 *
 * 注：「开省电」「清内存」已按产品决定移除，不再有对应实现与入口。
 */
class LockScreenAction : EyeTriggerAction {
    override val id = "lock_screen"
    override val label = "锁屏"

    override fun availability(context: Context): ActionAvailability {
        val active = EyeLockDeviceAdminReceiver.isActive(context)
        return ActionAvailability(
            canExecuteDirectly = active,
            detail = if (active) {
                "设备管理员已激活，可调用 lockNow()"
            } else {
                "未激活设备管理员：lockNow() 会抛 SecurityException，需先激活"
            },
            setupIntent = if (active) null else EyeLockDeviceAdminReceiver.requestAdminIntent(context)
        )
    }

    override fun execute(context: Context): ActionResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ActionResult(false, "本机无 DevicePolicyService")

        if (!EyeLockDeviceAdminReceiver.isActive(context)) {
            return ActionResult(false, "设备管理员未激活，lockNow() 未执行（无伪造成功）")
        }
        return try {
            dpm.lockNow()
            Log.i(TAG, "lockNow() 已下发")
            ActionResult(true, "lockNow() 已下发")
        } catch (e: SecurityException) {
            Log.e(TAG, "lockNow SecurityException: ${e.message}")
            ActionResult(false, "lockNow() 被拒：${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "lockNow failed: ${e.message}")
            ActionResult(false, "lockNow() 异常：${e.message}")
        }
    }

    companion object {
        private const val TAG = "LockScreenAction"
    }
}
