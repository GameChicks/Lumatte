package com.lumixfilmmirror.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class ExportQueueDao {
    @Transaction
    @Query("SELECT * FROM export_runs ORDER BY createdAt DESC, id")
    abstract fun observe(): Flow<List<ExportRunWithItems>>
    @Transaction
    @Query("SELECT * FROM export_runs WHERE id = :id")
    abstract suspend fun snapshot(id: String): ExportRunWithItems?
    @Query("SELECT * FROM export_runs WHERE state IN ('RUNNING', 'QUEUED')")
    abstract suspend fun interrupted(): List<ExportRunRow>
    @Query("SELECT COUNT(*) FROM export_runs WHERE state = 'RUNNING' AND id != :id")
    abstract suspend fun otherActive(id: String): Int
    @Query("SELECT COUNT(*) FROM processing_entries INNER JOIN photos ON photos.id = processing_entries.photoId WHERE processing_entries.batchId = :batchId AND processing_entries.photoId = :photoId AND processing_entries.removed = 0")
    abstract suspend fun belongs(batchId: String, photoId: String): Int
    @Insert abstract suspend fun insertRun(run: ExportRunRow)
    @Insert abstract suspend fun insertItems(items: List<ExportItemRow>)
    @Query("UPDATE export_runs SET state = :state, updatedAt = :now WHERE id = :id")
    abstract suspend fun runState(id: String, state: String, now: Long): Int
    @Query("UPDATE export_items SET state = 'QUEUED', stage = NULL, candidateUri = NULL, error = NULL WHERE runId = :id AND (state IN ('QUEUED', 'PAUSED') OR (:retryFailed AND state = 'FAILED'))")
    abstract suspend fun prepareItems(id: String, retryFailed: Boolean)
    @Query("UPDATE export_items SET state = 'RUNNING', stage = :stage, error = NULL WHERE runId = :id AND photoId = :photoId AND state IN ('QUEUED', 'RUNNING')")
    abstract suspend fun checkpoint(id: String, photoId: String, stage: String): Int
    @Query("UPDATE export_items SET candidateUri = :uri WHERE runId = :id AND photoId = :photoId AND state = 'RUNNING' AND stage = 'PUBLISHING'")
    abstract suspend fun targetCreated(id: String, photoId: String, uri: String): Int
    @Query("UPDATE export_items SET state = 'SAVED', savedUri = :uri, error = NULL WHERE runId = :id AND photoId = :photoId AND state = 'RUNNING' AND stage = 'PUBLISHING'")
    protected abstract suspend fun recordSaved(id: String, photoId: String, uri: String): Int

    // Keep the receipt and pending-list change atomic. A newer request for the same
    // photo must not be cleared by an older export finishing late.
    @Query("""
        UPDATE processing_entries SET removed = 1
        WHERE removed = 0 AND EXISTS (
            SELECT 1 FROM export_runs AS completed
            INNER JOIN export_items AS receipt ON receipt.runId = completed.id
            WHERE completed.batchId = processing_entries.batchId
              AND receipt.photoId = processing_entries.photoId
              AND receipt.state = 'SAVED' AND receipt.savedUri IS NOT NULL
              AND (:runId IS NULL OR completed.id = :runId)
              AND (:photoId IS NULL OR receipt.photoId = :photoId)
              AND NOT EXISTS (
                  SELECT 1 FROM export_runs AS newer
                  INNER JOIN export_items AS pending ON pending.runId = newer.id
                  WHERE newer.batchId = completed.batchId AND pending.photoId = receipt.photoId
                    AND newer.rowid > completed.rowid
              )
        )
    """)
    abstract suspend fun retireSavedPhotos(runId: String?, photoId: String?): Int

    @Transaction
    open suspend fun saved(id: String, photoId: String, uri: String): Int {
        val changed = recordSaved(id, photoId, uri)
        if (changed == 1) retireSavedPhotos(id, photoId)
        return changed
    }
    @Query("UPDATE export_items SET state = CASE WHEN stage = 'PUBLISHING' THEN 'REVIEW' ELSE 'FAILED' END, error = :error WHERE runId = :id AND photoId = :photoId AND state IN ('QUEUED', 'RUNNING')")
    abstract suspend fun failed(id: String, photoId: String, error: String): Int
    @Query("UPDATE export_items SET state = CASE WHEN state = 'RUNNING' AND stage = 'PUBLISHING' THEN 'REVIEW' ELSE :state END, error = CASE WHEN state = 'RUNNING' AND stage = 'PUBLISHING' THEN '保存阶段中断，请核对目标后再决定是否重试' ELSE :reason END WHERE runId = :id AND state IN ('QUEUED', 'RUNNING', 'PAUSED')")
    abstract suspend fun stopItems(id: String, state: String, reason: String)
    @Query("UPDATE export_items SET state = :state, savedUri = :uri, error = :error WHERE runId = :id AND photoId = :photoId AND state = 'REVIEW'")
    abstract suspend fun resolveReview(id: String, photoId: String, state: String, uri: String?, error: String?): Int

    @Transaction
    open suspend fun enqueue(run: ExportRunRow, items: List<ExportItemRow>) {
        require(items.isNotEmpty() && items.all { it.runId == run.id })
        require(items.map { it.photoId }.distinct().size == items.size)
        require(items.map { it.frameNumber }.distinct().size == items.size && items.all { it.frameNumber > 0 })
        items.forEach { check(belongs(run.batchId, it.photoId) == 1) { "导出照片不属于此处理批次" } }
        insertRun(run); insertItems(items)
    }

    @Transaction
    open suspend fun begin(id: String, retryFailed: Boolean): ExportRunWithItems {
        val original = requireNotNull(snapshot(id)) { "导出任务不存在" }
        require(original.run.state != "RUNNING" && original.run.state != "CANCELLED") { "任务正在执行或已取消" }
        check(otherActive(id) == 0) { "已有批量导出正在执行" }
        prepareItems(id, retryFailed)
        val ready = requireNotNull(snapshot(id))
        require(ready.items.any { it.state == "QUEUED" }) { "没有可继续的项目；保存结果未知的照片需先核对" }
        check(runState(id, "RUNNING", System.currentTimeMillis()) == 1)
        return requireNotNull(snapshot(id))
    }

    @Transaction
    open suspend fun finish(id: String, stopped: String? = null, reason: String = "上次导出中断，可继续未完成的项目") {
        if (stopped != null) stopItems(id, stopped, reason)
        val items = requireNotNull(snapshot(id)).items
        val state = when {
            items.any { it.state == "REVIEW" } -> "NEEDS_REVIEW"
            items.any { it.state in setOf("QUEUED", "RUNNING", "PAUSED") } -> "PAUSED"
            items.any { it.state == "CANCELLED" } -> "CANCELLED"
            items.any { it.state == "FAILED" } -> "COMPLETE_WITH_FAILURES"
            else -> "COMPLETE"
        }
        check(runState(id, state, System.currentTimeMillis()) == 1)
    }

    @Transaction
    open suspend fun recoverInterrupted() {
        interrupted().forEach { finish(it.id, "PAUSED") }
    }

    @Transaction
    open suspend fun confirmReview(id: String, photoId: String, savedUri: String?) {
        require(snapshot(id)?.run?.state != "RUNNING") { "请等待当前导出结束后核对" }
        check(resolveReview(id, photoId, if (savedUri == null) "FAILED" else "SAVED", savedUri,
            if (savedUri == null) "用户核对后允许重试" else null) == 1) { "此项目不需要核对" }
        if (savedUri != null) retireSavedPhotos(id, photoId)
        finish(id)
    }

    @Transaction
    open suspend fun stopIdle(id: String, cancel: Boolean) {
        val current = requireNotNull(snapshot(id)) { "导出任务不存在" }
        require(current.run.state != "RUNNING") { "请通过执行任务的取消入口停止当前导出" }
        finish(id, if (cancel) "CANCELLED" else "PAUSED", if (cancel)
            "已取消后续导出，已保存照片保留" else "导出已暂停，可继续未完成的项目")
    }
}
