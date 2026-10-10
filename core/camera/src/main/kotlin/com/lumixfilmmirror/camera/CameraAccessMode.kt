package com.lumixfilmmirror.camera

/** Access replies are transfer modes, not interchangeable success tokens. */
enum class CameraAccessMode(val reply: String) {
    BROWSING("ok"), AUTO_UPLOAD("upload_bt"), MARKED_TRANSFER("transfer");

    val incoming: Boolean get() = this != BROWSING

    companion object {
        internal fun fromReply(reply: String): CameraAccessMode =
            entries.firstOrNull { it.reply == reply } ?: throw CameraProtocolException(reply)
    }
}

/** A camera-advertised sending directory. Root browsing is never a fallback. */
data class CameraIncomingDirectory internal constructor(
    val mode: CameraAccessMode,
    val objectId: String,
) {
    init {
        require(mode.incoming && objectId.isNotBlank() && objectId != "0")
        require(mode != CameraAccessMode.MARKED_TRANSFER || objectId == MARKED_OBJECT_ID)
    }

    companion object {
        internal const val MARKED_OBJECT_ID = "dir_id_mark_list"
    }
}
