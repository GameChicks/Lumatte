package com.lumixfilmmirror.camera

import java.net.URI

data class CameraDevice(
    val descriptionUri: URI,
    val friendlyName: String,
    val model: String,
    val firmware: String,
    val udn: String,
    val requiresChallenge: Boolean,
    val contentDirectory: URI,
)

enum class CameraResourceKind { ORIGINAL_JPEG, RAW_PREVIEW_JPEG, THUMBNAIL, RAW, VIDEO, UNKNOWN }

data class CameraResource(
    val uri: URI,
    val mimeType: String,
    val profile: String?,
    val originalName: String?,
    val bytes: Long?,
) {
    val kind: CameraResourceKind get() = when {
        (profile == "CAM_ORG" && mimeType == "image/jpeg") || profile == "CAM_HSP_JPG" -> CameraResourceKind.ORIGINAL_JPEG
        profile in setOf("CAM_RAW_JPG", "CAM_HSP_RAW_JPG") -> CameraResourceKind.RAW_PREVIEW_JPEG
        profile in setOf("CAM_TN", "CAM_LRGTN") -> CameraResourceKind.THUMBNAIL
        profile in setOf("CAM_RAW", "CAM_HSP_RAW") -> CameraResourceKind.RAW
        mimeType.startsWith("video/") || profile?.startsWith("CAM_AVC_") == true -> CameraResourceKind.VIDEO
        else -> CameraResourceKind.UNKNOWN
    }
}

data class CameraMedia(
    val id: String,
    val parentId: String,
    val title: String,
    val isFolder: Boolean,
    val resources: List<CameraResource>,
) {
    /** Never silently substitute a thumbnail for an original photo. */
    val editableResource: CameraResource? get() =
        resources.firstOrNull { it.kind == CameraResourceKind.ORIGINAL_JPEG }
            ?: resources.firstOrNull { it.kind == CameraResourceKind.RAW_PREVIEW_JPEG }
            ?: resources.firstOrNull { it.kind == CameraResourceKind.RAW }
    val thumbnail: CameraResource? get() = resources.firstOrNull { it.profile == "CAM_LRGTN" }
        ?: resources.firstOrNull { it.profile == "CAM_TN" }

    /** A reconnect may change URLs, but reused IDs must not silently select different media. */
    fun matchesPreviousPhoto(previous: CameraMedia): Boolean {
        val current = editableResource ?: return false
        val old = previous.editableResource ?: return false
        return id == previous.id && parentId == previous.parentId && title == previous.title &&
            current.kind == old.kind && current.originalName == old.originalName && current.bytes == old.bytes
    }
}

data class CameraMediaPage(
    val entries: List<CameraMedia>,
    val offset: Int,
    val numberReturned: Int,
    val totalMatches: Int,
    val updateId: String?,
) {
    val nextOffset: Int? get() = (offset + numberReturned).takeIf {
        numberReturned > 0 && it < totalMatches
    }
}

enum class ConnectionStep { REQUESTING_WIFI, DISCOVERING, READING_DEVICE, REQUESTING_ACCESS, WAITING_FOR_CAMERA_APPROVAL, READY }

class CameraProtocolException(val status: String) : java.io.IOException("相机返回：$status")
