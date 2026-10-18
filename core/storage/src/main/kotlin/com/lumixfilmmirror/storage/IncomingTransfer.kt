package com.lumixfilmmirror.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lumixfilmmirror.camera.CameraAccessMode
import com.lumixfilmmirror.camera.CameraDevice

/** The receiving session is distinct from files that have already been durably saved. */
@Entity(tableName = "incoming_transfers", indices = [Index("cameraKey"), Index("state")])
data class IncomingTransfer(
    @PrimaryKey val id: String,
    val cameraKey: String,
    val cameraModel: String,
    val mode: String,
    val directoryId: String,
    val state: String = "PAUSED",
    val currentTaskId: String? = null,
    val acknowledgement: String = "NONE",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun belongsTo(device: CameraDevice): Boolean = cameraKey == device.incomingKey()
}

val IncomingTransfer.accessMode: CameraAccessMode get() = CameraAccessMode.valueOf(mode)
val IncomingTransfer.terminal: Boolean get() = state in setOf("COMPLETE", "CANCELLED")
val IncomingTransfer.confirmationUnknown: Boolean get() = acknowledgement in setOf("IN_FLIGHT", "UNKNOWN")

internal fun CameraDevice.incomingKey(): String =
    if (udn.isNotBlank()) "udn:$udn" else "description:$model:$descriptionUri"
