package com.lumixfilmmirror.app

import com.lumixfilmmirror.transfer.TransferPhase

fun TransferPhase?.caption(): String = when (this) {
    TransferPhase.RESOLVING -> "读取媒体信息"
    TransferPhase.DOWNLOADING -> "下载中"
    TransferPhase.IMPORTING_PHOTO -> "导入照片"
    TransferPhase.SAVING_VIDEO -> "保存视频"
    null -> "传输中"
}
