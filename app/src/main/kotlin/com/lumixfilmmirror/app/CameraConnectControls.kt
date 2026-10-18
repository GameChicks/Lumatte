package com.lumixfilmmirror.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.camera.CameraRouterSsid
import com.lumixfilmmirror.camera.CameraWifiConnector
import com.lumixfilmmirror.camera.CameraWifiSecurity

/** Ordinary class: a generated data-class toString must never include the password. */
private class ManualCameraWifiInput(val ssid: String, val password: String, val security: CameraWifiSecurity)

@Composable
fun CameraConnectControls(busy: Boolean, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    var showWifi by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var security by remember { mutableStateOf(CameraWifiSecurity.WPA2_PERSONAL) }
    val wpa3Available = remember(context, showWifi) {
        runCatching { CameraWifiConnector.supportsWpa3(context) }.getOrDefault(false)
    }
    // Credentials stay in this UI's memory only; no saveable state, logs or app preferences.
    var password by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ManualCameraWifiInput?>(null) }
    val ssidError = remember(ssid) {
        if (ssid.isEmpty()) null else runCatching { CameraRouterSsid.parse(ssid) }.exceptionOrNull()?.message
    }
    val passwordError = security.passwordProblem(password)
    val canConnect = ssid.isNotBlank() && ssidError == null && passwordError == null &&
        (security != CameraWifiSecurity.WPA3_PERSONAL || wpa3Available)
    val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val credentials = pending
        pending = null
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (credentials != null) {
            if (result[required] == true) workspace.connectCameraWifi(credentials.ssid, credentials.password, credentials.security)
            else workspace.cameraPermissionDenied()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("开启相机的 Wi-Fi 传输功能，然后连接相机显示的网络。",
            style = MaterialTheme.typography.bodySmall)
        MirrorButton(enabled = !busy, onClick = { showWifi = true }) { Text("连接相机 Wi-Fi") }
        FlowRow {
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
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按相机屏幕输入名称，选择对应安全类型；随后在系统弹窗中确认连接。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("Wi-Fi 名称") },
                    singleLine = true, isError = ssidError != null,
                    supportingText = { Text(ssidError ?: "${ssid.toByteArray(Charsets.UTF_8).size}/32 字节 · 按原名称输入") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (type in CameraWifiSecurity.entries) MirrorChoice(selected = security == type,
                        enabled = !busy && (type != CameraWifiSecurity.WPA3_PERSONAL || wpa3Available),
                        onClick = { security = type; if (type == CameraWifiSecurity.OPEN) password = "" },
                        label = { Text(type.label) })
                }
                if (!wpa3Available) Text("本机暂不可请求 WPA3；若已在系统设置连接，可直接查找相机。",
                    style = MaterialTheme.typography.labelSmall)
                if (security != CameraWifiSecurity.OPEN) OutlinedTextField(value = password,
                    onValueChange = { password = it }, label = { Text("密码") }, singleLine = true,
                    isError = password.isNotEmpty() && passwordError != null,
                    supportingText = { Text(passwordError ?: "保留原有空格和大小写") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && canConnect, onClick = {
                val credentials = ManualCameraWifiInput(ssid, password, security)
                password = ""
                showWifi = false
                if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    workspace.connectCameraWifi(credentials.ssid, credentials.password, credentials.security)
                } else {
                    pending = credentials
                    permissionLauncher.launch(permissions)
                }
            }) { Text("连接") }
        },
        dismissButton = { TextButton(onClick = { showWifi = false; password = "" }) { Text("取消") } },
    )
}
