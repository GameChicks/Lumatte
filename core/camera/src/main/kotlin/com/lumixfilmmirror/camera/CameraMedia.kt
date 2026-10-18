package com.lumixfilmmirror.camera

import java.net.URI
import java.util.Locale
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
    private val profileName: String? get() = profile?.trim()?.uppercase(Locale.ROOT)
    private val contentType: String get() = mimeType.substringBefore(';').trim()
    val kind: CameraResourceKind get() {
        val name = profileName
        return when {
            (name == "CAM_ORG" && contentType.equals("image/jpeg", true)) || name == "CAM_HSP_JPG" -> CameraResourceKind.ORIGINAL_JPEG
            name in setOf("CAM_RAW_JPG", "CAM_HSP_RAW_JPG") -> CameraResourceKind.RAW_PREVIEW_JPEG
            name in setOf("CAM_TN", "CAM_LRGTN") -> CameraResourceKind.THUMBNAIL
            name in setOf("CAM_RAW", "CAM_HSP_RAW") -> CameraResourceKind.RAW
            contentType.startsWith("video/", true) || name?.startsWith("CAM_AVC_") == true || name == "CAM_FOCUS_SELECT_MP4" ||
                (contentType.equals("application/octet-stream", true) && name?.contains("IFRAME") == true) -> CameraResourceKind.VIDEO
            else -> CameraResourceKind.UNKNOWN
        }
    }

    /** Panasonic may advertise the original video as application/octet-stream. */
    val videoContainer: VideoContainer? get() {
        if (kind != CameraResourceKind.VIDEO) return null
        val name = profileName
        if (name?.contains("IFRAME") == true) return null
        return when {
            name?.startsWith("CAM_AVC_MOV_") == true -> VideoContainer.MOV
            name?.startsWith("CAM_AVC_TS_HP_") == true -> VideoContainer.MPEG_TS
            name?.startsWith("CAM_AVC_MP4_") == true || name == "CAM_FOCUS_SELECT_MP4" -> VideoContainer.MP4
            contentType.equals("video/quicktime", true) -> VideoContainer.MOV
            contentType.equals("video/mp4", true) -> VideoContainer.MP4
            contentType.equals("video/mp2t", true) || contentType.equals("video/vnd.dlna.mpeg-tts", true) -> VideoContainer.MPEG_TS
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
    val mediaClass: String? = null,
) {
    /** A video item can advertise only a JPEG poster; resource MIME alone cannot identify the item. */
    val isVideo: Boolean get() {
        val declared = mediaClass?.trim().orEmpty()
        return declared.equals("object.item.videoItem", true) || declared.startsWith("object.item.videoItem.", true) ||
            resources.any { it.kind == CameraResourceKind.VIDEO }
    }

    /** Never silently substitute a thumbnail for an original photo. */
    val editableResource: CameraResource? get() {
        if (isVideo) return null
        return resources.firstOrNull { it.kind == CameraResourceKind.ORIGINAL_JPEG }
            ?: resources.firstOrNull { it.kind == CameraResourceKind.RAW_PREVIEW_JPEG }
            ?: resources.firstOrNull { it.kind == CameraResourceKind.RAW }
    }
    val thumbnailResources: List<CameraResource> get() = listOfNotNull(
        resources.firstOrNull { it.profile?.trim().equals("CAM_LRGTN", true) },
        resources.firstOrNull { it.profile?.trim().equals("CAM_TN", true) },
    ).distinctBy { it.uri }
    val thumbnail: CameraResource? get() = thumbnailResources.firstOrNull()
    val videoResource: CameraResource? get() =
        resources.firstOrNull { it.videoContainer != null && it.profile?.trim()?.endsWith("_ORG", true) == true }
            ?: resources.firstOrNull { it.videoContainer != null }
    val transferResource: CameraResource? get() =
        if (isVideo) videoResource else editableResource

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
    val newestFirst: Boolean = false,
) {
    val nextOffset: Int? get() = if (newestFirst) offset.takeIf { it > 0 } else (offset + numberReturned).takeIf {
        numberReturned > 0 && it < totalMatches
    }
}

enum class ConnectionStep { REQUESTING_WIFI, DISCOVERING, READING_DEVICE, REQUESTING_ACCESS, WAITING_FOR_CAMERA_APPROVAL, READY }

class CameraProtocolException(val status: String) : java.io.IOException("相机返回：$status") {
    val requiresReconnect: Boolean get() = status in setOf("HTTP 401", "HTTP 403", "下载 HTTP 401", "下载 HTTP 403",
        "err_reject", "err_user_refused", "err_not_connected", "err_session")
}
