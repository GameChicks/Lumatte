package com.lumixfilmmirror.storage

import androidx.room.*
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.model.*

enum class ExportRunState { QUEUED, RUNNING, PAUSED, COMPLETE, COMPLETE_WITH_FAILURES, NEEDS_REVIEW, CANCELLED }
enum class ExportItemState { QUEUED, RUNNING, PAUSED, FAILED, SAVED, REVIEW, CANCELLED }

data class ExportRequestPhoto(val id: PhotoId, val name: String, val frameNumber: Int, val recipe: PhotoRecipe)
data class ExportQueueItem(val photo: ExportRequestPhoto, val state: ExportItemState,
    val stage: PhotoProcessingStage?, val candidateUri: String?, val savedUri: String?, val error: String?)
data class ExportRun(val id: String, val batchId: String, val createdAt: Long, val updatedAt: Long,
    val state: ExportRunState, val options: BatchExportOptions, val destination: ExportDestination,
    val items: List<ExportQueueItem>) {
    val saved: Int get() = items.count { it.state == ExportItemState.SAVED }
    val failed: Int get() = items.count { it.state == ExportItemState.FAILED }
    val reviews: Int get() = items.count { it.state == ExportItemState.REVIEW }
    val completed: Int get() = items.count { it.state in setOf(ExportItemState.SAVED, ExportItemState.FAILED,
        ExportItemState.REVIEW, ExportItemState.CANCELLED) }
}

@Entity(tableName = "export_runs", indices = [Index("batchId"), Index("state")], foreignKeys = [ForeignKey(
    entity = ProcessingBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"],
)])
internal data class ExportRunRow(@PrimaryKey val id: String, val batchId: String,
    val createdAt: Long, val updatedAt: Long, val state: String,
    val dimensionPercent: Int, val compressionQuality: Int, val frameJson: String, val folderUri: String?)

@Entity(tableName = "export_items", primaryKeys = ["runId", "photoId"],
    indices = [Index("runId"), Index("photoId")], foreignKeys = [ForeignKey(
        entity = ExportRunRow::class, parentColumns = ["id"], childColumns = ["runId"],
    )])
internal data class ExportItemRow(val runId: String, val photoId: String, val name: String,
    val frameNumber: Int, val recipeJson: String, val state: String = "QUEUED",
    val stage: String? = null, val candidateUri: String? = null, val savedUri: String? = null, val error: String? = null)

internal data class ExportRunWithItems(@Embedded val run: ExportRunRow,
    @Relation(parentColumn = "id", entityColumn = "runId") val items: List<ExportItemRow>)
