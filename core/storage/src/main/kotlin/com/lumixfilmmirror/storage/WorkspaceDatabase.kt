package com.lumixfilmmirror.storage

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "photos")
internal data class PhotoRow(
    @PrimaryKey val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val rawPreview: Boolean,
    val camera: String,
    val lens: String,
    val capturedAt: String,
    val iso: String,
    val aperture: String,
    val shutter: String,
    val focalLength: String,
    val ordinal: Long = 0,
)

@Entity(tableName = "transfers", indices = [Index("batchId"), Index("state")])
data class TransferTask(
    @PrimaryKey val id: String,
    val batchId: String,
    val ordinal: Int,
    val cameraUdn: String,
    val cameraModel: String,
    val descriptionUri: String,
    val itemId: String,
    val parentId: String,
    val title: String,
    val mimeType: String,
    val profile: String?,
    val originalName: String?,
    val expectedBytes: Long?,
    val resourceUri: String,
    val state: String = "QUEUED",
    val bytesReceived: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TransferState { QUEUED, RUNNING, DOWNLOADED, PAUSED, FAILED, IMPORTED, CANCELLED }

@Dao
internal abstract class WorkspaceDao {
    @Query("SELECT * FROM photos ORDER BY ordinal")
    abstract fun photos(): Flow<List<PhotoRow>>
    @Query("SELECT * FROM photos WHERE id = :id")
    abstract suspend fun photo(id: String): PhotoRow?
    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM photos")
    abstract suspend fun nextOrdinal(): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPhoto(photo: PhotoRow)
    @Transaction
    open suspend fun savePhoto(photo: PhotoRow) { insertPhoto(photo.copy(ordinal = nextOrdinal())) }

    @Query("SELECT * FROM transfers ORDER BY createdAt, batchId, ordinal")
    abstract fun transfers(): Flow<List<TransferTask>>
    @Query("SELECT * FROM transfers WHERE batchId = :batchId ORDER BY ordinal")
    abstract suspend fun batch(batchId: String): List<TransferTask>
    @Insert
    abstract suspend fun enqueue(tasks: List<TransferTask>)
    @Query("UPDATE transfers SET state = :state, bytesReceived = :bytes, error = :error WHERE id = :id AND state != 'IMPORTED'")
    abstract suspend fun checkpoint(id: String, state: String, bytes: Long, error: String?)
    @Query("UPDATE transfers SET state = 'PAUSED', error = '上次传输中断，请连接相机后继续' WHERE state = 'RUNNING'")
    abstract suspend fun recoverInterrupted()
    @Query("UPDATE transfers SET state = :state, error = :reason WHERE batchId = :batchId AND (state IN ('QUEUED', 'RUNNING', 'DOWNLOADED', 'PAUSED') OR (state = 'FAILED' AND :state = 'CANCELLED'))")
    abstract suspend fun stopBatch(batchId: String, state: String, reason: String)

    @Transaction
    open suspend fun finish(id: String, photo: PhotoRow, bytes: Long) {
        savePhoto(photo)
        checkpoint(id, "IMPORTED", bytes, null)
    }
}

@Database(entities = [PhotoRow::class, TransferTask::class], version = 1, exportSchema = true)
internal abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspace(): WorkspaceDao
    companion object {
        @Volatile private var instance: WorkspaceDatabase? = null
        fun get(context: Context): WorkspaceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkspaceDatabase::class.java,
                "film-mirror-workspace.db").build().also { instance = it }
        }
    }
}
