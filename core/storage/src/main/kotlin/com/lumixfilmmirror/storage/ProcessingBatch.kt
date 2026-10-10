package com.lumixfilmmirror.storage

import androidx.room.*
import com.lumixfilmmirror.media.SourceImage

data class ProcessingBatch(val id: String, val title: String, val createdAt: Long,
    val photos: List<SourceImage> = emptyList())

@Entity(tableName = "processing_batches")
internal data class ProcessingBatchRow(@PrimaryKey val id: String, val title: String, val createdAt: Long)

/** Reserved before import; a completed photo may be recovered without losing its batch/order. */
@Entity(tableName = "processing_entries", indices = [Index("batchId")], foreignKeys = [ForeignKey(
    entity = ProcessingBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"],
)])
internal data class ProcessingEntryRow(@PrimaryKey val photoId: String, val batchId: String, val ordinal: Long)

internal data class ProcessingEntryWithPhoto(
    @Embedded val entry: ProcessingEntryRow,
    @Relation(parentColumn = "photoId", entityColumn = "id") val photos: List<PhotoRow>,
)

internal data class ProcessingBatchWithPhotos(
    @Embedded val batch: ProcessingBatchRow,
    @Relation(parentColumn = "id", entityColumn = "batchId", entity = ProcessingEntryRow::class)
    val entries: List<ProcessingEntryWithPhoto>,
)
