package com.lumixfilmmirror.camera

import java.io.IOException

enum class CameraSendingReconnectReason { MODE_CHANGED, WIFI_DETAILS_CHANGED }

/** Created only from a recognized camera event; callers cannot supply a mode byte. */
class CameraSendingReconnectRequest internal constructor(
    val mode: CameraAccessMode,
    val reason: CameraSendingReconnectReason,
)

class CameraSendingReconnectionRequired internal constructor(val request: CameraSendingReconnectRequest) : IOException(
    if (request.reason == CameraSendingReconnectReason.MODE_CHANGED)
        "相机发送模式已切换，旧接收已暂停；请按新模式重新连接"
    else "相机请求更新网络资料，接收已暂停；请更新连接",
)

internal fun sendingReconnect(mode: CameraAccessMode, reason: CameraSendingReconnectReason): CameraSendingReconnectionRequired =
    CameraSendingReconnectionRequired(CameraSendingReconnectRequest(mode, reason))
