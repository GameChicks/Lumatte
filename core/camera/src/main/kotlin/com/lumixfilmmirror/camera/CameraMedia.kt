package com.lumixfilmmirror.camera

import java.net.URI
import com.lumixfilmmirror.model.VideoContainer

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
        (profile == "CAM_ORG" && mimeType.equals("image/jpeg", true)) || profile == "CAM_HSP_JPG" -> CameraResourceKind.ORIGINAL_JPEG
        profile in setOf("CAM_RAW_JPG", "CAM_HSP_RAW_JPG") -> CameraResourceKind.RAW_PREVIEW_JPEG
        profile in setOf("CAM_TN", "CAM_LRGTN") -> CameraResourceKind.THUMBNAIL
        profile in setOf("CAM_RAW", "CAM_HSP_RAW") -> CameraResourceKind.RAW
        mimeType.startsWith("video/", true) || profile?.startsWith("CAM_AVC_") == true || profile == "CAM_FOCUS_SELECT_MP4" -> CameraResourceKind.VIDEO
        else -> CameraResourceKind.UNKNOWN
    }

    /** Panasonic may advertise the original video as application/octet-stream. */
    val videoContainer: VideoContainer? get() {
        if (kind != CameraResourceKind.VIDEO) return null
        if (profile?.contains("IFRAME", true) == true) return null
        return when {
            profile?.startsWith("CAM_AVC_MOV_") == true -> VideoContainer.MOV
            profile?.startsWith("CAM_AVC_TS_HP_") == true -> VideoContainer.MPEG_TS
            profile?.startsWith("CAM_AVC_MP4_") == true || profile == "CAM_FOCUS_SELECT_MP4" -> VideoContainer.MP4
            mimeType.equals("video/quicktime", true) -> VideoContainer.MOV
            mimeType.equals("video/mp4", true) -> VideoContainer.MP4
            mimeType.equals("video/mp2t", true) || mimeType.equals("video/vnd.dlna.mpeg-tts", true) -> VideoContainer.MPEG_TS
            else -> null
        }
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
    val thumbnailResources: List<CameraResource> get() = listOfNotNull(
        resources.firstOrNull { it.profile == "CAM_LRGTN" },
        resources.firstOrNull { it.profile == "CAM_TN" },
    ).distinctBy { it.uri }
    val thumbnail: CameraResource? get() = thumbnailResources.firstOrNull()
    val videoResource: CameraResource? get() =
        resources.firstOrNull { it.videoContainer != null && it.profile?.endsWith("_ORG") == true }
            ?: resources.firstOrNull { it.videoContainer != null }
    val transferResource: CameraResource? get() =
        if (resources.any { it.kind == CameraResourceKind.VIDEO }) videoResource else editableResource

    /** A reconnect may change URLs, but reused IDs must not silently select different media. */
    fun matchesPreviousPhoto(previous: CameraMedia): Boolean {
        val current = editableResource ?: return false
        val old = previous.editableResource ?: return false
        return matchesResource(previous, current, old)
    }

    fun matchesPreviousMedia(previous: CameraMedia): Boolean {
        val current = transferResource ?: return false
        val old = previous.transferResource ?: return false
        return matchesResource(previous, current, old)
    }

    private fun matchesResource(previous: CameraMedia, current: CameraResource, old: CameraResource): Boolean {
        return id == previous.id && parentId == previous.parentId && title == previous.title &&
            current.kind == old.kind && current.videoContainer == old.videoContainer &&
            current.originalName == old.originalName && current.bytes == old.bytes
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
