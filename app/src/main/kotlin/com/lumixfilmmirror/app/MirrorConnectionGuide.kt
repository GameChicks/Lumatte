package com.lumixfilmmirror.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts

@Composable
internal fun MirrorConnectionGuide(bluetooth: Boolean, model: String? = null) {
    val tones = LocalFilmMaterials.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(model?.let { "连接 ${it.removePrefix("DC-").removePrefix("DMC-")}" } ?: "让相机与手机相遇",
            fontFamily = MirrorFonts.ChineseEditorial, fontSize = 27.sp, lineHeight = 36.sp)
        Text(if (bluetooth) "先完成蓝牙配对，再连接相机的照片传输网络。" else "没有蓝牙的机型，也可以通过 Wi-Fi 传入照片。",
            style = MaterialTheme.typography.bodyMedium, color = tones.secondaryInk)
        val steps = if (bluetooth) listOf("相机开启配对" to "进入相机「蓝牙 → 配对」页面并保持打开；只设置为 ON 不等于进入配对。扫描时先退出其他 Sync 软件。",
            "选中你的相机" to "点击下方查找结果，并在相机上确认配对。",
            "连接传输网络" to "读取网络后，确认手机的 Wi-Fi 连接弹窗。")
        else listOf("打开相机 Wi-Fi" to "进入相机的智能手机传输页面，显示网络名称。",
            "连接这个网络" to "填写相机显示的名称和密码，或先在系统 Wi-Fi 中连接。",
            "浏览与传入" to "连接后多选照片，选择保存到手机或进入编辑。")
        steps.forEachIndexed { i, (title, caption) ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Text("0${i + 1}", Modifier.width(32.dp), fontFamily = MirrorFonts.Editorial, fontSize = 25.sp,
                    lineHeight = 30.sp, color = tones.amber)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontFamily = MirrorFonts.ChineseEditorial, fontSize = 17.sp, lineHeight = 23.sp)
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                }
            }
        }
    }
}
