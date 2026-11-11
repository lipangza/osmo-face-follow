package com.example.facefollow.model

/**
 * 偏移量结果回调。这是整条链路的「对外接口 / 扩展点」：
 * 现在的 MainActivity 只是把结果画到屏幕上；以后要把人脸跟随接到 OM SE 云台时，
 * 实现这个接口、在里面把 FaceResult 转成摇杆控制量并通过 BLE 下发即可，无需改动检测/采集代码。
 */
interface OffsetListener {
    fun onFaceOffset(result: FaceResult)
}
