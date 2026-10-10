package com.lumixfilmmirror.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @ColumnInfo(defaultValue = "NULL") val savedUri: String? = null,
)

enum class TransferState { QUEUED, RUNNING, DOWNLOADED, PUBLISHING, PAUSED, FAILED, IMPORTED, SAVED, CANCELLED }

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
    @Query("SELECT * FROM transfers ORDER BY createdAt, batchId, ordinal")
    abstract suspend fun transferSnapshot(): List<TransferTask>
    @Query("SELECT * FROM transfers WHERE batchId = :batchId ORDER BY ordinal")
    abstract suspend fun batch(batchId: String): List<TransferTask>
    @Insert
    abstract suspend fun enqueue(tasks: List<TransferTask>)
    @Query("UPDATE transfers SET state = :state, bytesReceived = :bytes, error = :error WHERE id = :id AND state NOT IN ('IMPORTED', 'SAVED')")
    abstract suspend fun checkpoint(id: String, state: String, bytes: Long, error: String?)
    @Query("UPDATE transfers SET state = 'PAUSED', error = '上次传输或保存中断，可继续未完成的任务' WHERE state IN ('RUNNING', 'PUBLISHING')")
    abstract suspend fun recoverInterrupted()
    @Query("UPDATE transfers SET state = :state, error = :reason WHERE batchId = :batchId AND (state IN ('QUEUED', 'RUNNING', 'DOWNLOADED', 'PUBLISHING', 'PAUSED') OR (state = 'FAILED' AND :state = 'CANCELLED'))")
    abstract suspend fun stopBatch(batchId: String, state: String, reason: String)
    @Query("UPDATE transfers SET savedUri = :uri, state = 'PUBLISHING', bytesReceived = :bytes, error = NULL WHERE id = :id AND state NOT IN ('IMPORTED', 'SAVED')")
    abstract suspend fun reserveVideo(id: String, uri: String, bytes: Long)
    @Query("UPDATE transfers SET savedUri = :uri, state = 'SAVED', bytesReceived = :bytes, error = NULL WHERE id = :id AND state NOT IN ('IMPORTED', 'SAVED')")
    abstract suspend fun finishVideo(id: String, uri: String, bytes: Long)

    @Transaction
    open suspend fun finish(id: String, photo: PhotoRow, bytes: Long) {
        savePhoto(photo)
        checkpoint(id, "IMPORTED", bytes, null)
    }
}

@Database(entities = [PhotoRow::class, TransferTask::class], version = 2, exportSchema = true)
internal abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspace(): WorkspaceDao
    companion object {
        private val version1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN savedUri TEXT DEFAULT NULL")
            }
        }
        @Volatile private var instance: WorkspaceDatabase? = null
        fun get(context: Context): WorkspaceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkspaceDatabase::class.java,
                "film-mirror-workspace.db").addMigrations(version1To2).build().also { instance = it }
        }
    }
}
