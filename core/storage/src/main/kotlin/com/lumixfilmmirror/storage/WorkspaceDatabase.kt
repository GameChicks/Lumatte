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
    @ColumnInfo(defaultValue = "NULL") val cameraAcknowledgement: String? = null,
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
    @Query("SELECT * FROM transfers WHERE id = :id")
    abstract suspend fun task(id: String): TransferTask?
    @Query("SELECT * FROM incoming_transfers ORDER BY createdAt DESC")
    abstract fun incomingTransfers(): Flow<List<IncomingTransfer>>
    @Query("SELECT * FROM incoming_transfers WHERE id = :id")
    abstract suspend fun incoming(id: String): IncomingTransfer?
    @Query("SELECT * FROM incoming_transfers WHERE cameraKey = :cameraKey AND state NOT IN ('COMPLETE', 'CANCELLED') ORDER BY createdAt LIMIT 1")
    abstract suspend fun unfinishedIncoming(cameraKey: String): IncomingTransfer?
    @Insert
    abstract suspend fun insertIncoming(run: IncomingTransfer)
    @Update
    abstract suspend fun updateIncoming(run: IncomingTransfer): Int
    @Query("UPDATE transfers SET cameraAcknowledgement = :acknowledgement WHERE id = :id AND state IN ('IMPORTED', 'SAVED')")
    abstract suspend fun updateAcknowledgement(id: String, acknowledgement: String): Int
    @Transaction
    open suspend fun saveIncomingState(run: IncomingTransfer) {
        if (run.currentTaskId != null && run.acknowledgement != "NONE") {
            check(updateAcknowledgement(run.currentTaskId, run.acknowledgement) == 1) { "文件尚未保存，未记录相机确认" }
        }
        check(updateIncoming(run) == 1) { "接收记录未能保存" }
    }
    @Query("SELECT transfers.* FROM transfers INNER JOIN incoming_transfers ON transfers.batchId = incoming_transfers.id WHERE incoming_transfers.cameraKey = :cameraKey AND incoming_transfers.directoryId = :directoryId AND incoming_transfers.mode = :mode AND transfers.state IN ('IMPORTED', 'SAVED')")
    abstract suspend fun savedIncomingTasks(cameraKey: String, directoryId: String, mode: String): List<TransferTask>

    @Transaction
    open suspend fun getOrCreateIncoming(candidate: IncomingTransfer): IncomingTransfer {
        val previous = unfinishedIncoming(candidate.cameraKey)
        if (previous != null) {
            require(previous.mode == candidate.mode && previous.directoryId == candidate.directoryId) {
                "此相机还有另一发送模式的接收记录，请先停止该记录"
            }
            return previous
        }
        insertIncoming(candidate)
        return candidate
    }

    @Transaction
    open suspend fun appendIncomingTask(runId: String, task: TransferTask): TransferTask {
        val run = requireNotNull(incoming(runId))
        require(!run.terminal && !run.confirmationUnknown && task.batchId == runId)
        val previous = run.currentTaskId?.let { this.task(it) }
        require(previous == null || (previous.state in setOf("IMPORTED", "SAVED") &&
            run.acknowledgement in setOf("CONFIRMED", "ADVANCED"))) { "上一待发送文件尚未处理完成" }
        val appended = task.copy(ordinal = batch(runId).size)
        enqueue(listOf(appended))
        check(updateIncoming(run.copy(currentTaskId = appended.id, acknowledgement = "NONE", error = null)) == 1)
        return appended
    }
    @Query("UPDATE transfers SET state = :state, bytesReceived = :bytes, error = :error WHERE id = :id AND state NOT IN ('IMPORTED', 'SAVED')")
    abstract suspend fun checkpoint(id: String, state: String, bytes: Long, error: String?)
    @Query("UPDATE transfers SET state = 'PAUSED', error = '上次传输或保存中断，可继续未完成的任务' WHERE state IN ('RUNNING', 'PUBLISHING')")
    abstract suspend fun recoverInterrupted()
    @Query("UPDATE incoming_transfers SET state = 'PAUSED', acknowledgement = CASE WHEN acknowledgement = 'IN_FLIGHT' THEN 'UNKNOWN' ELSE acknowledgement END, error = '上次接收中断，已保存结果保留；确认响应未知时不会自动重发' WHERE state = 'RUNNING' OR acknowledgement = 'IN_FLIGHT'")
    abstract suspend fun recoverIncoming()
    @Query("UPDATE transfers SET cameraAcknowledgement = 'UNKNOWN' WHERE cameraAcknowledgement = 'IN_FLIGHT'")
    abstract suspend fun recoverAcknowledgements()
    @Transaction
    open suspend fun recoverReceivers() { recoverIncoming(); recoverAcknowledgements() }
    @Query("UPDATE transfers SET state = :state, error = :reason WHERE batchId = :batchId AND (state IN ('QUEUED', 'RUNNING', 'DOWNLOADED', 'PUBLISHING', 'PAUSED') OR (state = 'FAILED' AND :state = 'CANCELLED'))")
    abstract suspend fun stopBatch(batchId: String, state: String, reason: String)
    @Query("UPDATE incoming_transfers SET state = :state, acknowledgement = CASE WHEN acknowledgement = 'IN_FLIGHT' THEN 'UNKNOWN' ELSE acknowledgement END, error = :reason WHERE id = :batchId AND state NOT IN ('COMPLETE', 'CANCELLED')")
    abstract suspend fun stopIncoming(batchId: String, state: String, reason: String)
    @Query("UPDATE transfers SET cameraAcknowledgement = 'UNKNOWN' WHERE batchId = :batchId AND cameraAcknowledgement = 'IN_FLIGHT'")
    abstract suspend fun interruptAcknowledgement(batchId: String)
    @Transaction
    open suspend fun stopTransfer(batchId: String, state: String, reason: String) {
        stopBatch(batchId, state, reason)
        stopIncoming(batchId, state, reason)
        interruptAcknowledgement(batchId)
    }
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

@Database(entities = [PhotoRow::class, TransferTask::class, IncomingTransfer::class], version = 3, exportSchema = true)
internal abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspace(): WorkspaceDao
    companion object {
        private val version1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN savedUri TEXT DEFAULT NULL")
            }
        }
        private val version2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN cameraAcknowledgement TEXT DEFAULT NULL")
                db.execSQL("CREATE TABLE IF NOT EXISTS incoming_transfers (id TEXT NOT NULL PRIMARY KEY, cameraKey TEXT NOT NULL, cameraModel TEXT NOT NULL, mode TEXT NOT NULL, directoryId TEXT NOT NULL, state TEXT NOT NULL, currentTaskId TEXT, acknowledgement TEXT NOT NULL, error TEXT, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incoming_transfers_cameraKey ON incoming_transfers(cameraKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incoming_transfers_state ON incoming_transfers(state)")
            }
        }
        @Volatile private var instance: WorkspaceDatabase? = null
        fun get(context: Context): WorkspaceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkspaceDatabase::class.java,
                "film-mirror-workspace.db").addMigrations(version1To2, version2To3).build().also { instance = it }
        }
    }
}
