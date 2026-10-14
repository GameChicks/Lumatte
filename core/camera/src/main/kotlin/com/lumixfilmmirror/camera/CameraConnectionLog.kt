package com.lumixfilmmirror.camera

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraLogFile(val name: String, val bytes: Long)
data class CameraConnectionLogState(
    val ready: Boolean = false,
    val enabled: Boolean = true,
    val activeFile: String? = null,
    val files: List<CameraLogFile> = emptyList(),
    val revision: Long = 0,
    val problem: String? = null,
)

/** App-owned connection events only: never reads logcat, packets or another app's files. */
object CameraConnectionLog {
    private const val MAX_BYTES = 256 * 1024L
    private const val MAX_FILES = 8
    private val fileName = Regex("ConnectLog-\\d{8}_\\d{6}_\\d{3}(?:-\\d+)?\\.log")
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lfm-connection-log").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(CameraConnectionLogState())
    val state = mutable.asStateFlow()
    // Only the serial writer accesses these fields.
    private var folder: File? = null
    private var preferences: SharedPreferences? = null
    private var active: File? = null
    private var startedAt = 0L
    private var header = ""

    fun initialize(context: Context) {
        val app = context.applicationContext
        enqueue {
            if (folder != null) return@enqueue
            @Suppress("DEPRECATION")
            val version = app.packageManager.getPackageInfo(app.packageName, 0)
            header = "=== Lumix Film Mirror Connection Log / schema=1 ===\n" +
                "app=${clean(version.versionName.orEmpty())} code=${version.longVersionCode}\n" +
                "phone=${clean(Build.BRAND)} ${clean(Build.MODEL)} Android=${clean(Build.VERSION.RELEASE)} API=${Build.VERSION.SDK_INT}\n" +
                "flow=sync-trace-v3\n" +
                "privacy=No passwords, SSIDs, camera/phone IDs, raw replies or photo names.\n"
            val directory = File(app.noBackupFilesDir, "connection-logs")
            check(directory.isDirectory || directory.mkdirs())
            preferences = app.getSharedPreferences("camera-connection-log", Context.MODE_PRIVATE)
            folder = directory
            prune()
            mutable.value = mutable.value.copy(ready = true,
                enabled = preferences?.getBoolean("enabled", true) ?: true)
            publish()
        }
    }

    /** Messages must be controlled protocol summaries, never raw responses or exception messages. */
    fun info(tag: String, message: String) = record(false, tag, message)
    fun warn(tag: String, message: String) = record(true, tag, message)
    private fun record(warning: Boolean, tag: String, message: String) {
        val safe = clean(message)
        if (warning) Log.w(tag, safe) else Log.i(tag, safe)
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val elapsed = SystemClock.elapsedRealtime()
        enqueue {
            if (folder == null || !mutable.value.enabled) return@enqueue
            append(timestamp, elapsed, if (warning) "WARN" else "INFO", clean(tag), safe)
        }
    }

    fun setEnabled(enabled: Boolean) = enqueue {
        if (folder == null) return@enqueue
        if (!enabled && active != null) append(OffsetDateTime.now().toString(), SystemClock.elapsedRealtime(),
            "INFO", "Recorder", "recording=stopped")
        active = null
        preferences?.edit()?.putBoolean("enabled", enabled)?.apply()
        mutable.value = mutable.value.copy(enabled = enabled)
        publish()
    }

    suspend fun clear() = withContext(dispatcher) {
        active = null
        try { files().forEach { check(it.delete()) } }
        finally { publish() }
    }

    suspend fun read(name: String): String = withContext(dispatcher) {
        val file = selected(name)
        check(file.length() <= MAX_BYTES)
        file.readText(Charsets.UTF_8)
    }

    /** Snapshot on the writer queue; later logging/rotation cannot change the exported bytes. */
    suspend fun export(context: Context, name: String, uri: Uri): Long {
        val bytes = withContext(dispatcher) {
            val file = selected(name)
            check(file.length() <= MAX_BYTES)
            file.readBytes()
        }
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                "无法打开日志保存位置"
            }.use { output -> output.write(bytes); output.flush() }
            bytes.size.toLong()
        }
    }

    private fun selected(name: String): File {
        require(fileName.matches(name)) { "日志名称无效" }
        return File(requireNotNull(folder), name).also { require(it.isFile) { "日志已被清除或轮换" } }
    }

    private fun append(timestamp: String, elapsed: Long, level: String, tag: String, message: String) {
        var line = "[$timestamp] [+${elapsed - startedAt}ms] [$level] [$tag] $message\n"
        if (active == null || requireNotNull(active).length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            startedAt = elapsed
            val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT))
            var serial = 0
            var next = File(requireNotNull(folder), "ConnectLog-$stamp.log")
            while (next.exists()) { serial++; next = File(requireNotNull(folder), "ConnectLog-$stamp-$serial.log") }
            next.writeText(header + "startedAt=$timestamp\n\n", Charsets.UTF_8)
            active = next
            line = "[$timestamp] [+0ms] [$level] [$tag] $message\n"
            prune()
        }
        requireNotNull(active).appendText(line, Charsets.UTF_8) // Open/close each event to retain already written failures.
        publish()
    }

    private fun files(): List<File> = folder?.listFiles()?.filter { it.isFile && fileName.matches(it.name) }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }).orEmpty()
    private fun prune() { files().filter { it != active }.drop(if (active == null) MAX_FILES else MAX_FILES - 1).forEach { check(it.delete()) } }
    private fun publish() {
        mutable.value = mutable.value.copy(activeFile = active?.name,
            files = files().map { CameraLogFile(it.name, it.length()) }, revision = mutable.value.revision + 1,
            problem = null)
    }
    private fun enqueue(block: () -> Unit) {
        scope.launch {
            try { block() }
            catch (error: Exception) {
                active = null
                mutable.value = mutable.value.copy(activeFile = null, enabled = false,
                    problem = "日志存储失败（${error.javaClass.simpleName}）", revision = mutable.value.revision + 1)
            }
        }
    }

    private val protocolUuids by lazy { setOf(LumixBleProtocol.service, LumixBleProtocol.clientId,
        LumixBleProtocol.friendlyName, LumixBleProtocol.apsClientId, LumixBleProtocol.apsFriendlyName,
        LumixBleProtocol.challenge, LumixBleProtocol.ssid, LumixBleProtocol.password, LumixBleProtocol.wifiService,
        LumixBleProtocol.wifiConnectionType, LumixBleProtocol.wifiChoiceReply, LumixBleProtocol.routerSsid,
        LumixBleProtocol.connectionMode, LumixBleProtocol.connectionStatus, LumixBleProtocol.sendingService,
        LumixBleProtocol.connectionAddress, LumixBleProtocol.imageTransferEvent, LumixBleProtocol.sendingModeEvent,
        LumixBleProtocol.cccd).map { it.toString() }.toSet() }
    private fun clean(value: String): String {
        var text = value.replace(Regex("[\\r\\n\\p{Cc}]"), " ").take(1500)
        text = text.replace(Regex("(?i)https?://\\S+"), "<endpoint>")
            .replace(Regex("(?i)\\b(password|ssid|sessionId|challenge|clientId|deviceId|phoneName)\\s*[:=]\\s*\\S+"), "$1=<hidden>")
            .replace(Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b"), "<address>")
            .replace(Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "<address>")
            .replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")) {
                if (it.value.lowercase(Locale.ROOT) in protocolUuids) it.value else "<id>"
            }.replace(Regex("(?i)\\b[0-9a-f]{32,}\\b"), "<id>")
        return text
    }
}
