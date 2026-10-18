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
    @ColumnInfo(defaultValue = "NULL") val completedAt: Long? = null,
)

enum class TransferState { QUEUED, RUNNING, DOWNLOADED, PUBLISHING, PAUSED, FAILED, IMPORTED, SAVED, CANCELLED }

@Dao
internal abstract class WorkspaceDao {
    @Query("UPDATE processing_entries SET removed = :removed WHERE batchId = :batchId AND photoId IN (:ids)")
    abstract suspend fun markProcessingPhotos(batchId: String, ids: List<String>, removed: Boolean)
    @Transaction
    open suspend fun removeProcessingPhotos(batchId: String, ids: List<String>, edit: BatchEditRecipeRow) {
        markProcessingPhotos(batchId, ids, true)
        saveEditRecipe(edit)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveFrameRecipe(recipe: BatchFrameRecipeRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveEditRecipe(recipe: BatchEditRecipeRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveLutRecipe(recipe: BatchLutRecipeRow)
    @Transaction
    @Query("SELECT * FROM processing_batches ORDER BY createdAt DESC, id")
    abstract fun processingBatches(): Flow<List<ProcessingBatchWithPhotos>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertProcessingBatch(batch: ProcessingBatchRow)
    @Query("SELECT * FROM processing_batches WHERE id = :id")
    abstract suspend fun processingBatch(id: String): ProcessingBatchRow?
    @Query("SELECT * FROM processing_entries WHERE photoId = :id")
    abstract suspend fun processingEntry(id: String): ProcessingEntryRow?
    @Query("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM processing_entries WHERE batchId = :id")
    abstract suspend fun nextProcessingOrdinal(id: String): Long
    @Insert
    abstract suspend fun insertProcessingEntry(entry: ProcessingEntryRow)
    @Transaction
    open suspend fun reserveProcessingPhoto(batchId: String, photoId: String, ordinal: Long? = null) {
        check(processingBatch(batchId) != null) { "处理批次不存在" }
        val existing = processingEntry(photoId)
        if (existing != null) {
            check(existing.batchId == batchId) { "照片已有其他批次归属" }
            return
        }
        val next = nextProcessingOrdinal(batchId)
        insertProcessingEntry(ProcessingEntryRow(photoId, batchId, maxOf(next, ordinal ?: next)))
    }
    @Transaction
    open suspend fun saveRecoveredPhoto(photo: PhotoRow) {
        if (processingEntry(photo.id) == null) {
            val transfer = task(photo.id)
            val batchId = transfer?.batchId ?: "legacy-imports"
            insertProcessingBatch(ProcessingBatchRow(batchId,
                if (transfer == null) "历史与恢复照片" else "相机传入 · ${transfer.cameraModel}", transfer?.createdAt ?: 0L))
            reserveProcessingPhoto(batchId, photo.id, transfer?.ordinal?.toLong())
        }
        savePhoto(photo)
    }
    @Transaction
    open suspend fun enqueueProcessingBatch(batch: ProcessingBatchRow, tasks: List<TransferTask>) {
        insertProcessingBatch(batch)
        enqueue(tasks)
        tasks.filter { it.expectedMedia().videoResource == null }.forEach {
            reserveProcessingPhoto(batch.id, it.id, it.ordinal.toLong())
        }
    }
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
    @Query("SELECT * FROM incoming_transfers WHERE cameraKey = :cameraKey AND state NOT IN ('COMPLETE', 'CANCELLED') ORDER BY createdAt")
    abstract suspend fun unfinishedIncoming(cameraKey: String): List<IncomingTransfer>
    @Insert
    abstract suspend fun insertIncoming(run: IncomingTransfer)
    @Update
    abstract suspend fun updateIncoming(run: IncomingTransfer): Int
    @Query("UPDATE transfers SET cameraAcknowledgement = :acknowledgement WHERE id = :id AND state IN ('IMPORTED', 'SAVED')")
    abstract suspend fun updateAcknowledgement(id: String, acknowledgement: String): Int
    @Transaction
    open suspend fun saveIncomingState(run: IncomingTransfer) {
        if (run.state in setOf("RUNNING", "WAITING")) {
            check(unfinishedIncoming(run.cameraKey).none { it.id != run.id && it.state in setOf("RUNNING", "WAITING") }) {
                "此相机已有另一条活跃接收，请先暂停"
            }
        }
        if (run.currentTaskId != null && run.acknowledgement != "NONE") {
            check(updateAcknowledgement(run.currentTaskId, run.acknowledgement) == 1) { "文件尚未保存，未记录相机确认" }
        }
        check(updateIncoming(run) == 1) { "接收记录未能保存" }
    }
    @Query("SELECT transfers.* FROM transfers INNER JOIN incoming_transfers ON transfers.batchId = incoming_transfers.id WHERE incoming_transfers.cameraKey = :cameraKey AND incoming_transfers.directoryId = :directoryId AND incoming_transfers.mode = :mode AND transfers.state IN ('IMPORTED', 'SAVED')")
    abstract suspend fun savedIncomingTasks(cameraKey: String, directoryId: String, mode: String): List<TransferTask>

    @Transaction
    open suspend fun getOrCreateIncoming(candidate: IncomingTransfer): IncomingTransfer {
        val unfinished = unfinishedIncoming(candidate.cameraKey)
        check(unfinished.none { it.state in setOf("RUNNING", "WAITING") &&
            (it.mode != candidate.mode || it.directoryId != candidate.directoryId) }) { "此相机已有另一条活跃接收，请先暂停" }
        val previous = unfinished.firstOrNull { it.mode == candidate.mode && it.directoryId == candidate.directoryId }
        if (previous != null) {
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
        if (appended.expectedMedia().videoResource == null) {
            insertProcessingBatch(ProcessingBatchRow(run.id, "相机接收 · ${run.cameraModel}", run.createdAt))
            reserveProcessingPhoto(run.id, appended.id, appended.ordinal.toLong())
        }
        check(updateIncoming(run.copy(currentTaskId = appended.id, acknowledgement = "NONE", error = null)) == 1)
        return appended
    }
    @Query("UPDATE transfers SET state = :state, bytesReceived = :bytes, error = :error WHERE id = :id AND state NOT IN ('IMPORTED', 'SAVED')")
    abstract suspend fun checkpoint(id: String, state: String, bytes: Long, error: String?)
    @Query("UPDATE transfers SET state = 'PAUSED', error = '上次传输或保存中断，可继续未完成的任务' WHERE state IN ('RUNNING', 'PUBLISHING')")
    abstract suspend fun recoverInterrupted()
    @Query("UPDATE incoming_transfers SET state = 'PAUSED', acknowledgement = CASE WHEN acknowledgement = 'IN_FLIGHT' THEN 'UNKNOWN' ELSE acknowledgement END, error = '上次接收中断，已保存结果保留；确认响应未知时不会自动重发' WHERE state IN ('RUNNING', 'WAITING') OR acknowledgement = 'IN_FLIGHT'")
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
    @Query("UPDATE transfers SET savedUri = :uri, state = 'SAVED', bytesReceived = :bytes, error = NULL, completedAt = :completedAt WHERE id = :id AND state NOT IN ('IMPORTED', 'SAVED')")
    abstract suspend fun finishVideo(id: String, uri: String, bytes: Long, completedAt: Long)

    @Query("UPDATE transfers SET completedAt = :completedAt WHERE id = :id AND state = 'IMPORTED' AND completedAt IS NULL")
    abstract suspend fun markTransferCompleted(id: String, completedAt: Long)

    @Transaction
    open suspend fun finish(id: String, photo: PhotoRow, bytes: Long) {
        val task = requireNotNull(task(id)) { "传输任务不存在" }
        insertProcessingBatch(ProcessingBatchRow(task.batchId, "相机传入 · ${task.cameraModel}", task.createdAt))
        reserveProcessingPhoto(task.batchId, photo.id, task.ordinal.toLong())
        savePhoto(photo)
        checkpoint(id, "IMPORTED", bytes, null)
        markTransferCompleted(id, System.currentTimeMillis())
    }
}

@Database(entities = [PhotoRow::class, TransferTask::class, IncomingTransfer::class,
    ProcessingBatchRow::class, ProcessingEntryRow::class, BatchLutRecipeRow::class, BatchEditRecipeRow::class,
    BatchFrameRecipeRow::class, ExportRunRow::class, ExportItemRow::class], version = 11, exportSchema = true)
internal abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspace(): WorkspaceDao
    abstract fun exports(): ExportQueueDao
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
        private val version3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS processing_batches (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS processing_entries (photoId TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, ordinal INTEGER NOT NULL, FOREIGN KEY(batchId) REFERENCES processing_batches(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_processing_entries_batchId ON processing_entries(batchId)")
                db.execSQL("INSERT OR IGNORE INTO processing_batches (id, title, createdAt) SELECT batchId, '相机传入 · ' || MIN(cameraModel), MIN(createdAt) FROM transfers GROUP BY batchId")
                db.execSQL("INSERT OR IGNORE INTO processing_entries (photoId, batchId, ordinal) SELECT id, batchId, ordinal FROM transfers")
                db.execSQL("INSERT OR IGNORE INTO processing_batches (id, title, createdAt) SELECT 'legacy-imports', '历史与恢复照片', 0 WHERE EXISTS (SELECT 1 FROM photos WHERE id NOT IN (SELECT photoId FROM processing_entries))")
                db.execSQL("INSERT OR IGNORE INTO processing_entries (photoId, batchId, ordinal) SELECT id, 'legacy-imports', ordinal FROM photos WHERE id NOT IN (SELECT photoId FROM processing_entries)")
            }
        }
        private val version4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS batch_lut_recipes (batchId TEXT NOT NULL PRIMARY KEY, firstId TEXT, firstStrength REAL NOT NULL, secondId TEXT, secondStrength REAL NOT NULL, grain REAL NOT NULL, FOREIGN KEY(batchId) REFERENCES processing_batches(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            }
        }
        private val version5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS batch_edit_recipes (batchId TEXT NOT NULL PRIMARY KEY, treatment TEXT NOT NULL, filmJson TEXT, FOREIGN KEY(batchId) REFERENCES processing_batches(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            }
        }
        private val version6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS batch_frame_recipes (batchId TEXT NOT NULL PRIMARY KEY, frameJson TEXT NOT NULL, FOREIGN KEY(batchId) REFERENCES processing_batches(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
            }
        }
        private val version7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS export_runs (id TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, state TEXT NOT NULL, dimensionPercent INTEGER NOT NULL, compressionQuality INTEGER NOT NULL, frameJson TEXT NOT NULL, folderUri TEXT, FOREIGN KEY(batchId) REFERENCES processing_batches(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_export_runs_batchId ON export_runs(batchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_export_runs_state ON export_runs(state)")
                db.execSQL("CREATE TABLE IF NOT EXISTS export_items (runId TEXT NOT NULL, photoId TEXT NOT NULL, name TEXT NOT NULL, frameNumber INTEGER NOT NULL, recipeJson TEXT NOT NULL, state TEXT NOT NULL, stage TEXT, candidateUri TEXT, savedUri TEXT, error TEXT, PRIMARY KEY(runId, photoId), FOREIGN KEY(runId) REFERENCES export_runs(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_export_items_runId ON export_items(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_export_items_photoId ON export_items(photoId)")
            }
        }
        private val version8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfers ADD COLUMN completedAt INTEGER DEFAULT NULL")
            }
        }
        private val version9To10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE batch_lut_recipes ADD COLUMN grainEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE batch_lut_recipes ADD COLUMN ditheringEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var instance: WorkspaceDatabase? = null
        private val version10To11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE processing_entries ADD COLUMN removed INTEGER NOT NULL DEFAULT 0")
            }
        }
        fun get(context: Context): WorkspaceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkspaceDatabase::class.java,
                "film-mirror-workspace.db").addMigrations(version1To2, version2To3, version3To4, version4To5, version5To6, version6To7, version7To8, version8To9, version9To10, version10To11).build().also { instance = it }
        }
    }
}
