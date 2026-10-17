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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import com.lumixfilmmirror.design.PaperPanel
import com.lumixfilmmirror.camera.CameraRouterSsid
import com.lumixfilmmirror.camera.BluetoothConnectionStep

@Composable
fun CameraBluetoothControls(camera: CameraWorkspaceState, busy: Boolean, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    var pendingStartup by rememberSaveable { mutableStateOf(false) }
    var pendingSending by rememberSaveable { mutableStateOf(false) }
    var selectedCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }
    // Router SSID is an optional input, not a password or a value read from Android.
    var routerSsid by remember { mutableStateOf("") }
    val routerError = remember(routerSsid) {
        if (routerSsid.isEmpty()) null
        else runCatching { CameraRouterSsid.parse(routerSsid) }.exceptionOrNull()?.message
    }
    val routerValid = routerSsid.isNotBlank() && routerError == null
    var pendingRouter by remember { mutableStateOf(false) }
    var pendingRouterSending by remember { mutableStateOf(false) }
    val wifiPermissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val required = if (Build.VERSION.SDK_INT >= 31) bluetoothPermissions.toList()
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val router = pendingRouter
        val routerSending = pendingRouterSending
        pendingRouter = false
        pendingRouterSending = false
        if (required.all { permission -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) {
            if (router) {
                if (routerSending) workspace.connectBluetoothRouterSendingWifi(routerSsid)
                else workspace.connectBluetoothRouterWifi(routerSsid)
            } else workspace.scanBluetoothCameras()
        }
        else workspace.bluetoothPermissionDenied()
    }
    fun connectRouter(sending: Boolean) {
        if (bluetoothPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (sending) workspace.connectBluetoothRouterSendingWifi(routerSsid)
            else workspace.connectBluetoothRouterWifi(routerSsid)
        } else {
            pendingRouter = true
            pendingRouterSending = sending
            bluetoothLauncher.launch(bluetoothPermissions)
        }
    }
    val wifiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        val startup = pendingStartup
        val candidate = pendingCamera
        pendingCamera = null
        val sending = pendingSending
        pendingStartup = false
        pendingSending = false
        val hasBluetooth = !(startup || candidate != null) || bluetoothPermissions.all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
        if (!hasBluetooth) workspace.bluetoothPermissionDenied()
        else if (context.checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED) {
            if (candidate != null) workspace.readBluetoothCamera(candidate)
            else if (sending) workspace.connectBluetoothSendingWifi() else workspace.connectBluetoothWifi(startup)
        }
        else workspace.cameraPermissionDenied()
    }
    fun connect(startup: Boolean, sending: Boolean = false) {
        val permissions = if (startup) (wifiPermissions + bluetoothPermissions).distinct().toTypedArray() else wifiPermissions
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (sending) workspace.connectBluetoothSendingWifi() else workspace.connectBluetoothWifi(startup)
        }
        else {
            pendingStartup = startup
            pendingSending = sending
            wifiLauncher.launch(permissions)
        }
    }
    fun connectCandidate(address: String) {
        selectedCamera = address
        val permissions = (wifiPermissions + bluetoothPermissions).distinct().toTypedArray()
        if (permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
            workspace.readBluetoothCamera(address)
        else {
            pendingCamera = address
            wifiLauncher.launch(permissions)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("在相机上打开蓝牙配对。",
                style = MaterialTheme.typography.bodySmall)
            MirrorButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                if (bluetoothPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
                    workspace.scanBluetoothCameras()
                else bluetoothLauncher.launch(bluetoothPermissions)
            }) { Text(if (camera.bluetoothStep == BluetoothConnectionStep.SCANNING) "正在查找相机…" else "查找蓝牙相机") }
            if (camera.bluetoothStep == BluetoothConnectionStep.SCANNING)
                Text("发现相机后可直接点连接，无需等查找结束。", style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("打开系统蓝牙设置 →")
            }
            TextButton(enabled = !busy, onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                Text("打开系统定位设置 →")
            }
        if (camera.bluetoothCameras.isNotEmpty()) {
            Text("选择你的相机", style = MaterialTheme.typography.titleMedium)
            val selectable = !busy || camera.bluetoothStep == BluetoothConnectionStep.SCANNING
            camera.bluetoothCameras.forEach { found ->
                PaperPanel(Modifier.fillMaxWidth().clickable(enabled = selectable) { selectedCamera = found.address }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedCamera == found.address, enabled = selectable,
                            onClick = { selectedCamera = found.address })
                        DotIcon(DotGlyph.CAMERA, Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(found.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (found.pairing) "相机正在等待配对" else "相机已开机",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = selectable, onClick = { connectCandidate(found.address) }) {
                            Text("连接")
                        }
                    }
                }
            }
            MirrorButton(modifier = Modifier.fillMaxWidth(), enabled = selectable &&
                camera.bluetoothCameras.any { it.address == selectedCamera }, onClick = {
                val address = selectedCamera ?: return@MirrorButton
                connectCandidate(address)
            }) { Text("配对并连接所选相机") }
            val selected = camera.bluetoothCameras.firstOrNull { it.address == selectedCamera }
            if (selected != null && !selected.pairing) {
                TextButton(enabled = selectable, onClick = {
                    workspace.readBluetoothCamera(selected.address, startTransfer = false)
                }) { Text("已完成配对 · 查看其他连接方式") }
                Text("仅供已完成配对的相机选择路由器或发送方式；首次配对请使用上方按钮。",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        camera.bluetoothWifiSsid?.let { ssid ->
            Text("相机网络 · $ssid", style = MaterialTheme.typography.bodySmall)
            if (camera.bluetoothCanStartWifi) {
                Text("启动相机的传输网络，随后在系统弹窗确认连接。", style = MaterialTheme.typography.labelSmall)
                MirrorButton(enabled = !busy, onClick = { connect(true) }) { Text("启动传输 Wi-Fi 并连接") }
                if (camera.bluetoothCanStartSendingWifi) {
                    Text("先点下方入口；提示等待后，在相机上开启拍后发送或选定文件发送。等待最多 90 秒。",
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !busy, onClick = { connect(true, sending = true) }) {
                        Text("等待相机发送并连接")
                    }
                }
                TextButton(enabled = !busy, onClick = { connect(false) }) { Text("已在相机上开启 · 直接连接") }
            } else {
                Text("在相机上开启 Wi-Fi 传输后连接，随后在系统弹窗确认。", style = MaterialTheme.typography.labelSmall)
                MirrorButton(enabled = !busy, onClick = { connect(false) }) { Text("连接此相机 Wi-Fi") }
            }
            if (camera.bluetoothCanStartRouterWifi) {
                HorizontalDivider()
                Text("同一路由器连接", style = MaterialTheme.typography.titleSmall)
                Text("先在手机系统设置连接路由器，并在相机上保存该网络。输入与手机当前 Wi-Fi 相同的名称；此入口用于浏览和下载。",
                    style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(value = routerSsid, onValueChange = { routerSsid = it }, enabled = !busy,
                    singleLine = true, isError = routerError != null, label = { Text("路由器 Wi-Fi 名称") },
                    supportingText = { Text(routerError ?: "${routerSsid.toByteArray(Charsets.UTF_8).size}/32 字节 · 可输入中文，保留空格和大小写；密码在相机上设置。") })
                TextButton(enabled = !busy && routerValid, onClick = { connectRouter(false) }) {
                    Text("让相机连接此路由器")
                }
                if (camera.bluetoothCanStartSendingWifi) {
                    Text("需要拍后或选定发送时，先点下方入口；提示等待后再在相机上开启发送。等待最多 90 秒。",
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !busy && routerValid, onClick = { connectRouter(true) }) {
                        Text("通过此路由器等待相机发送")
                    }
                }
            }
        }
    }
}
