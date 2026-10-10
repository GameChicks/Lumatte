package com.lumixfilmmirror.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun CameraBluetoothControls(camera: CameraWorkspaceState, busy: Boolean, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    val wifiPermissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val required = if (Build.VERSION.SDK_INT >= 31) bluetoothPermissions.toList()
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.all { permission -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED })
            workspace.scanBluetoothCameras()
        else workspace.bluetoothPermissionDenied()
    }
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (context.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED) workspace.connectBluetoothWifi()
        else workspace.cameraPermissionDenied()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("也可先在相机菜单开启蓝牙配对，读取它的 Wi-Fi 连接资料。",
            style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy, onClick = {
                if (bluetoothPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
                    workspace.scanBluetoothCameras()
                else bluetoothLauncher.launch(bluetoothPermissions)
            }) { Text("查找蓝牙相机") }
            TextButton(enabled = !busy, onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("系统蓝牙")
            }
        }
        for (found in camera.bluetoothCameras) TextButton(enabled = !busy,
            onClick = { workspace.readBluetoothCamera(found.address) }) {
            Text("${found.name} · ${if (found.pairing) "正在配对" else "可连接"}")
        }
        camera.bluetoothWifiSsid?.let { ssid ->
            Text("相机网络 · $ssid", style = MaterialTheme.typography.bodySmall)
            Text("在相机上开启 Wi-Fi 传输后连接，随后在系统弹窗确认。",
                style = MaterialTheme.typography.labelSmall)
            Button(enabled = !busy, onClick = {
                if (wifiPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
                    workspace.connectBluetoothWifi()
                else wifiLauncher.launch(wifiPermissions)
            }) { Text("连接此相机 Wi-Fi") }
        }
    }
}
