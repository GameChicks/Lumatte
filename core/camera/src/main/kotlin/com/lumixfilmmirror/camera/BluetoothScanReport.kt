package com.lumixfilmmirror.camera

/** Aggregate scan evidence only: no addresses, names, raw advertisements or credentials. */
data class BluetoothScanReport(
    val advertisements: Int,
    val panasonicAdvertisements: Int,
    val emptyPanasonicPayloads: Int,
    val observedStates: Set<Int>,
) {
    fun noCameraMessage(): String = when {
        advertisements == 0 -> "扫描期间没有收到蓝牙广播。请保持应用前台和屏幕亮起，确认附近设备、精确定位权限及系统定位已开启；若仍无结果，可重新开启手机蓝牙后查找。"
        panasonicAdvertisements == 0 -> "已收到周围蓝牙广播，但没有收到松下相机广播。请保持相机的「蓝牙 → 配对」页面打开后重新查找。"
        observedStates.isEmpty() -> "已收到松下广播，但广播中缺少连接状态，请重新进入相机配对页面后查找。"
        else -> "已收到松下广播，但尚未收到正常或配对状态（广播状态：${observedStates.sorted().joinToString()}）。请保持相机开机并进入「蓝牙 → 配对」页面后重新查找。"
    }
}
