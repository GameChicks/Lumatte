package com.lumixfilmmirror.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.lumixfilmmirror.camera.CameraAccessMode
import com.lumixfilmmirror.camera.CameraSendingReconnectReason
import com.lumixfilmmirror.camera.CameraSendingReconnectRequest

@Composable
fun CameraSendingReconnectControl(request: CameraSendingReconnectRequest, busy: Boolean, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else emptyList()
    val wifiPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
    val permissions = (bluetoothPermissions + wifiPermission +
        (if (Build.VERSION.SDK_INT < 33) listOf(Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList())).distinct().toTypedArray()
    fun proceed() {
        if (bluetoothPermissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            workspace.bluetoothPermissionDenied()
        else if (context.checkSelfPermission(wifiPermission) != PackageManager.PERMISSION_GRANTED) workspace.cameraPermissionDenied()
        else workspace.reconnectSendingCamera()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { proceed() }
    val mode = if (request.mode == CameraAccessMode.AUTO_UPLOAD) "拍后发送" else "相机选定发送"
    Text(if (request.reason == CameraSendingReconnectReason.MODE_CHANGED) "相机已切换为$mode，旧接收已暂停。"
        else "相机请求更新 Wi-Fi 资料，$mode 已暂停。", style = MaterialTheme.typography.bodySmall)
    Text("更新连接会重新读取相机网络；已保存结果及旧队列的确认记录保留。连接后再选择接收方式。",
        style = MaterialTheme.typography.labelSmall)
    Button(enabled = !busy, onClick = {
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) proceed()
        else launcher.launch(permissions)
    }) { Text("更新发送连接") }
}
