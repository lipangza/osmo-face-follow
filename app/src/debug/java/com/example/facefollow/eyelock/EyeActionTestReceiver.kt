package com.example.facefollow.eyelock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 真机验证用的调试入口（仅 debug 构建存在，见 src/debug/AndroidManifest.xml）。
 *
 * adb 用法：
 *   adb shell am broadcast -a com.example.facefollow.eyelock.TEST \
 *     -n com.example.facefollow/com.example.facefollow.eyelock.EyeActionTestReceiver \
 *     --es action lock_screen
 *
 * 这里只跑「动作层」，用于在尚未接入主界面的情况下验证 lockNow() 是否真的生效。
 */
class EyeActionTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("action") ?: return
        val controller = EyeLockController(context)
        controller.setActionEnabled(id, true)
        val action = controller.actions().firstOrNull { it.id == id }
        val avail = controller.availability(id)

        if (action == null) {
            Log.w(TAG, "unknown action id=$id")
            return
        }
        Log.i(TAG, "avail[$id] direct=${avail?.canExecuteDirectly} detail=${avail?.detail}")
        val result = if (avail?.canExecuteDirectly == true) {
            action.execute(context)
        } else {
            ActionResult(false, "不可直接执行：${avail?.detail}")
        }
        Log.i(TAG, "exec[$id] ok=${result.ok} ${result.detail}")
    }

    companion object {
        private const val TAG = "EyeActionTestReceiver"
        const val ACTION = "com.example.facefollow.eyelock.TEST"
    }
}
