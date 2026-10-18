package com.lumixfilmmirror.camera

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class CameraSendingStopped(message: String) : CancellationException(message)

/** Ready bursts coalesce; stop/disconnect uses a separate signal that cannot be overwritten. */
class IncomingCameraEvents internal constructor() {
    private val ready = Channel<Unit>(Channel.CONFLATED)
    internal val ended = CompletableDeferred<Exception>()

    internal fun filesReady() { if (!ended.isCompleted) ready.trySend(Unit) }
    internal fun stop(error: Exception) { ended.complete(error); ready.close() }

    suspend fun awaitFiles() {
        select<Unit> {
            ended.onAwait { throw it }
            ready.onReceive { }
        }
        if (ended.isCompleted) throw ended.await()
    }

    internal fun close() { stop(CameraSendingStopped("后续照片接收已停止")) }
}
