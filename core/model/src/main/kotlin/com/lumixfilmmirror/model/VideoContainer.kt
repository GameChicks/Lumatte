package com.lumixfilmmirror.model

/** File containers only; no decoder or phone playback capability is implied. */
enum class VideoContainer(val extension: String, val mimeType: String, val label: String) {
    MP4("mp4", "video/mp4", "MP4"),
    MOV("mov", "video/quicktime", "MOV"),
    MPEG_TS("mts", "video/mp2t", "AVCHD / TS"),
}
