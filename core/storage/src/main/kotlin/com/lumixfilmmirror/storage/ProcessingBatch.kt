package com.lumixfilmmirror.storage

import androidx.room.*
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.model.LutRecipe
import com.lumixfilmmirror.model.FilmBatch
import com.lumixfilmmirror.model.PhotoTreatment
import com.lumixfilmmirror.model.FrameOptions

data class ProcessingBatch(val id: String, val title: String, val createdAt: Long,
    val photos: List<SourceImage> = emptyList(), val lut: LutRecipe = LutRecipe(),
    val treatment: PhotoTreatment = PhotoTreatment.LUT, val film: FilmBatch? = null,
    val frame: FrameOptions = FrameOptions())

@Entity(tableName = "batch_frame_recipes", foreignKeys = [ForeignKey(
    entity = ProcessingBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"],
)])
internal data class BatchFrameRecipeRow(@PrimaryKey val batchId: String, val frameJson: String)

@Entity(tableName = "batch_edit_recipes", foreignKeys = [ForeignKey(
    entity = ProcessingBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"],
)])
internal data class BatchEditRecipeRow(@PrimaryKey val batchId: String, val treatment: String, val filmJson: String?)

@Entity(tableName = "batch_lut_recipes", foreignKeys = [ForeignKey(
    entity = ProcessingBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"],
)])
internal data class BatchLutRecipeRow(@PrimaryKey val batchId: String, val firstId: String?, val firstStrength: Float,
    val secondId: String?, val secondStrength: Float, val grain: Float,
    @ColumnInfo(defaultValue = "1") val grainEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val ditheringEnabled: Boolean = true)

@Entity(tableName = "processing_batches")
internal data class ProcessingBatchRow(@PrimaryKey val id: String, val title: String, val createdAt: Long)

/** Reserved before import; a completed photo may be recovered without losing its batch/order. */
@Entity(tableName = "processing_entries", indices = [Index("batchId")], foreignKeys = [ForeignKey(
    entity = ProcessingBatchRow::class, parentColumns = ["id"], childColumns = ["batchId"],
)])
internal data class ProcessingEntryRow(@PrimaryKey val photoId: String, val batchId: String, val ordinal: Long,
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false)

internal data class ProcessingEntryWithPhoto(
    @Embedded val entry: ProcessingEntryRow,
    @Relation(parentColumn = "photoId", entityColumn = "id") val photos: List<PhotoRow>,
)

internal data class ProcessingBatchWithPhotos(
    @Embedded val batch: ProcessingBatchRow,
    @Relation(parentColumn = "id", entityColumn = "batchId", entity = ProcessingEntryRow::class)
    val entries: List<ProcessingEntryWithPhoto>,
    @Relation(parentColumn = "id", entityColumn = "batchId") val lutRecipes: List<BatchLutRecipeRow>,
    @Relation(parentColumn = "id", entityColumn = "batchId") val editRecipes: List<BatchEditRecipeRow>,
    @Relation(parentColumn = "id", entityColumn = "batchId") val frameRecipes: List<BatchFrameRecipeRow>,
)
