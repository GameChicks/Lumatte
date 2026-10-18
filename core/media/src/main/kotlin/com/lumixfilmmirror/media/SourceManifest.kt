package com.lumixfilmmirror.media

import android.util.AtomicFile
import com.lumixfilmmirror.model.InputOrigin
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.model.PixelSize
import org.json.JSONObject
import java.io.File

data class ImportRecovery(val photos: List<SourceImage>, val problems: List<String>)

/** A ready record is written last, independently of the database, so interrupted commits can recover. */
internal object SourceManifest {
    fun write(photo: SourceImage) {
        val metadata = with(photo.metadata) { JSONObject().put("camera", camera).put("lens", lens)
            .put("capturedAt", capturedAt).put("iso", iso).put("aperture", aperture)
            .put("shutter", shutter).put("focalLength", focalLength) }
        val json = JSONObject().put("version", 1).put("id", photo.id.value).put("name", photo.name)
            .put("width", photo.size.width).put("height", photo.size.height).put("orientation", photo.orientation)
            .put("rawPreview", photo.origin is InputOrigin.RawEmbeddedJpeg).put("metadata", metadata)
            .put("createdAt", System.currentTimeMillis())
        val atomic = AtomicFile(File(photo.jpeg.parentFile, "source.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    fun exists(directory: File): Boolean = File(directory, "source.json").isFile || File(directory, "source.json.bak").isFile

    fun read(directory: File, id: PhotoId): SourceImage? {
        if (!exists(directory)) return null
        val atomic = AtomicFile(File(directory, "source.json"))
        val bytes = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 64 * 1024) { "导入记录尺寸异常" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("id") == id.value) { "导入记录不匹配" }
        val size = PixelSize(json.getInt("width"), json.getInt("height"))
        val orientation = json.getInt("orientation")
        require(orientation in 1..8)
        val jpeg = File(directory, "source.jpg")
        require(jpeg.isFile && jpeg.length() > 0) { "导入照片文件缺失" }
        val metadata = json.getJSONObject("metadata")
        return SourceImage(id, json.getString("name"), jpeg, size, orientation,
            if (json.getBoolean("rawPreview")) InputOrigin.RawEmbeddedJpeg(size) else InputOrigin.Jpeg,
            PhotoMetadata(metadata.optString("camera"), metadata.optString("lens"), metadata.optString("capturedAt"),
                metadata.optString("iso"), metadata.optString("aperture"), metadata.optString("shutter"), metadata.optString("focalLength")))
    }
}
