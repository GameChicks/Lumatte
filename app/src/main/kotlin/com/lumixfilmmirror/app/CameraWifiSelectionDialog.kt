package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CameraWifiSelectionDialog(selection: CameraWifiSelection, workspace: WorkspaceViewModel) {
    AlertDialog(onDismissRequest = { workspace.cancelWifiSelection(selection.id) },
        title = { Text(selection.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("发现多个已连接的 Wi-Fi。选择与相机或目标路由器相通的网络；本机地址可在系统网络详情中核对。",
                    style = MaterialTheme.typography.bodySmall)
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    selection.options.forEachIndexed { index, option ->
                        TextButton(onClick = { workspace.chooseWifiNetwork(selection.id, option.key) },
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Wi-Fi ${index + 1}${if (option.isSystemDefault) " · 系统默认" else ""}")
                                val addresses = option.localIpv4 + option.localIpv6
                                Text(if (addresses.isEmpty()) "暂未获取到本机 IP 地址" else
                                    "本机地址 · ${addresses.joinToString(" / ")}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (selection.options.isEmpty()) Text("暂无已连接的 Wi-Fi，请先在系统设置连接，再刷新列表。",
                    style = MaterialTheme.typography.bodySmall)
                selection.problem?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
                Text("网络名称请在系统设置核对。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { workspace.refreshWifiSelection(selection.id) }) { Text("刷新网络") } },
        dismissButton = { TextButton(onClick = { workspace.cancelWifiSelection(selection.id) }) { Text("取消连接") } })
}
