package com.lumixfilmmirror.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun IncomingFollowControl(busy: Boolean, onFollow: () -> Unit, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    val permissions = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    fun granted(): Boolean = (if (Build.VERSION.SDK_INT >= 31) permissions.toList()
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)).all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) onFollow() else workspace.bluetoothPermissionDenied()
    }
    Text("保持当前相机连接，收到蓝牙发送事件后继续接收；可在通知栏暂停或停止。",
        style = MaterialTheme.typography.labelSmall)
    TextButton(enabled = !busy, onClick = { if (granted()) onFollow() else launcher.launch(permissions) }) {
        Text("接收当前及后续拍摄")
    }
}
