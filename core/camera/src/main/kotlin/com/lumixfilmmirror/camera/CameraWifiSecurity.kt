package com.lumixfilmmirror.camera

/** Personal camera access-point types only. No enterprise configuration or camera setting writes. */
enum class CameraWifiSecurity(val label: String) {
    WPA2_PERSONAL("WPA2"), WPA3_PERSONAL("WPA3"), OPEN("无密码");

    fun passwordProblem(password: String): String? = when (this) {
        OPEN -> if (password.isEmpty()) null else "无密码网络请留空密码"
        WPA2_PERSONAL -> if (password.length in 8..63 && password.all { it.code in 32..126 }) null
            else "WPA2 密码需要 8–63 位英文字符、数字或符号"
        WPA3_PERSONAL -> if (password.length in 1..63 && password.all { it.code in 32..126 }) null
            else "WPA3 密码需要 1–63 位英文字符、数字或符号"
    }

    companion object {
        /** Keep the pre-existing BLE behavior until a security-type field is recovered. */
        fun legacy(password: String): CameraWifiSecurity = if (password.isEmpty()) OPEN else WPA2_PERSONAL
    }
}
