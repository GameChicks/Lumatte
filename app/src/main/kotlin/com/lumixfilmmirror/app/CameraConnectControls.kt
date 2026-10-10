package com.lumixfilmmirror.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CameraConnectControls(busy: Boolean, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    var showWifi by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    // Credentials stay in this UI's memory only; no saveable state, logs or app preferences.
    var password by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val credentials = pending
        pending = null
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (credentials != null) {
            if (result[required] == true) workspace.connectCameraWifi(credentials.first, credentials.second)
            else workspace.cameraPermissionDenied()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("开启相机的 Wi-Fi 传输功能，然后连接相机显示的网络。",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = { showWifi = true }) { Text("连接相机 Wi-Fi") }
        Row {
            TextButton(enabled = !busy, onClick = workspace::connectCamera) { Text("已连 Wi-Fi · 查找相机") }
            TextButton(enabled = !busy, onClick = {
                runCatching { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            }) { Text("系统 Wi-Fi") }
        }
    }
    if (showWifi) AlertDialog(
        onDismissRequest = { showWifi = false; password = "" },
        title = { Text("连接相机 Wi-Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("输入相机屏幕上的名称和密码，随后在系统弹窗中确认连接。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("Wi-Fi 名称") }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("开放网络可留空密码。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && ssid.isNotBlank(), onClick = {
                val credentials = ssid to password
                password = ""
                showWifi = false
                if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    workspace.connectCameraWifi(credentials.first, credentials.second)
                } else {
                    pending = credentials
                    permissionLauncher.launch(permissions)
                }
            }) { Text("连接") }
        },
        dismissButton = { TextButton(onClick = { showWifi = false; password = "" }) { Text("取消") } },
    )
}
