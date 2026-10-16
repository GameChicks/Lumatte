package com.lumixfilmmirror.camera

import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.NetworkInterface
import java.io.File
import java.util.UUID

/** Connection and media operations only. No remote shooting or parameter-control surface. */
class LumixCameraSession private constructor(
    val device: CameraDevice,
    private val http: CameraHttp,
    val accessMode: CameraAccessMode,
) {
    private val commands = Mutex()
    private var incomingDirectory: CameraIncomingDirectory? = null

    suspend fun prepareBrowsing(includeRaw: Boolean = true) = commands.withLock {
        CameraConnectionLog.info("LFMCamera", "mediaSetup=playmode-request")
        check(accessMode == CameraAccessMode.BROWSING) { "相机当前处于待发送模式，请结束该模式后重新连接以浏览" }
        val playback = commandStatus("camcmd", "value" to "playmode")
        if (playback != "ok") throw CameraProtocolException(playback)
        prepareRawResources(includeRaw)
        CameraConnectionLog.info("LFMCamera", "mediaSetup=playmode-ready")
    }

    /** Read-only incoming setup, apart from enabling advertised RAW transfer resources. No playmode. */
    suspend fun prepareIncoming(includeRaw: Boolean = true): CameraIncomingDirectory = commands.withLock {
        check(accessMode.incoming) { "相机尚未进入待发送模式" }
        incomingDirectory = null
        prepareRawResources(includeRaw)
        val directory = when (accessMode) {
            CameraAccessMode.AUTO_UPLOAD -> {
                val menu = commandReply("getinfo", "type" to "allmenu")
                CameraXml.requireOk(menu)
                CameraIncomingDirectory(accessMode, CameraXml.autoUploadDirectory(menu))
            }
            CameraAccessMode.MARKED_TRANSFER -> CameraIncomingDirectory(accessMode, CameraIncomingDirectory.MARKED_OBJECT_ID)
            CameraAccessMode.BROWSING -> error("相机尚未进入待发送模式")
        }
        incomingDirectory = directory
        directory
    }

    suspend fun incomingContentCount(): Int = commands.withLock {
        val directory = requireNotNull(incomingDirectory) { "待发送目录尚未准备" }
        requireIncomingDirectory(directory)
        // Some firmware returns ok with the unsigned -1 sentinel while its list is still busy.
        repeat(5) { attempt ->
            val reply = http.command("get_content_info", "value" to directory.objectId)
            try { return@withLock CameraXml.contentCount(reply) }
            catch (error: CameraProtocolException) {
                if (error.status != "err_busy" || attempt == 4) throw error
                delay(500)
            }
        }
        error("无法读取待发送数量")
    }

    private fun requireIncomingDirectory(directory: CameraIncomingDirectory) {
        require(directory == incomingDirectory && directory.mode == accessMode) { "待发送目录尚未由此相机会话确认" }
    }

    fun preparedIncomingDirectory(): CameraIncomingDirectory =
        requireNotNull(incomingDirectory) { "相机发送目录尚未准备" }

    /** Transfer keep-alive intentionally runs beside a streaming GET, as in the reference service. */
    suspend fun keepTransferAlive() {
        check(accessMode.incoming)
        CameraXml.requireOk(commandReply("getstate", "type" to "keep_alive"))
    }

    /** Browsing needs the same one-second keep-alive as Sync's service. Never wait for a file GET's lock. */
    suspend fun keepBrowsingAlive() {
        check(accessMode == CameraAccessMode.BROWSING)
        val status = CameraXml.result(http.command("getstate", "type" to "keep_alive"))
        if (status != "ok" && status != "err_busy") throw CameraProtocolException(status)
    }

    /** One attempt only: a lost directory-level confirmation response must never be blindly retried. */
    suspend fun confirmSavedIncoming(directory: CameraIncomingDirectory, saved: CameraMedia): Boolean = commands.withLock {
        requireIncomingDirectory(directory)
        check(accessMode == CameraAccessMode.MARKED_TRANSFER) { "拍后自动发送不使用 transfer 确认" }
        val first = browseLocked(directory.objectId, 0, 1).entries.firstOrNull()
        if (first == null || !first.matchesPreviousMedia(saved)) return@withLock false
        CameraXml.requireOk(http.command("notify", "type" to "transfer", "value" to "ok", "value2" to directory.objectId))
        true
    }

    private suspend fun prepareRawResources(includeRaw: Boolean) {
        val status = commandStatus("setsetting", "type" to "raw_img_send",
            "value" to if (includeRaw) "enable" else "disable")
        if (status !in setOf("ok", "err_non_support", "err_param")) throw CameraProtocolException(status)
    }

    suspend fun browse(objectId: String = "0", offset: Int = 0, count: Int = 50): CameraMediaPage =
        commands.withLock { browseLocked(objectId, offset, count) }

    /** Panasonic's directory is oldest first. Page from its tail, not just reverse the first old page. */
    suspend fun browseNewest(objectId: String, beforeOffset: Int? = null, count: Int = 50): CameraMediaPage = commands.withLock {
        check(accessMode == CameraAccessMode.BROWSING)
        require(count in 1..100 && (beforeOffset == null || beforeOffset > 0))
        val summary = browseLocked(objectId, 0, 1)
        val end = beforeOffset ?: summary.totalMatches
        require(end <= summary.totalMatches) { "相机照片列表已更新，请刷新后重新选择" }
        if (end == 0) return@withLock summary.copy(newestFirst = true)
        val start = (end - count).coerceAtLeast(0)
        val page = browseLocked(objectId, start, end - start)
        require(page.updateId == summary.updateId && page.totalMatches == summary.totalMatches &&
            page.numberReturned == end - start) { "相机照片列表已更新，请刷新后重新选择" }
        CameraConnectionLog.info("LFMCamera", "directoryOrder=newest-first start=$start end=$end total=${page.totalMatches}")
        page.copy(entries = page.entries.asReversed(), newestFirst = true)
    }

    private suspend fun browseLocked(objectId: String, offset: Int, count: Int): CameraMediaPage {
        require(offset >= 0 && count in 1..100)
        if (accessMode.incoming) {
            require(objectId == incomingDirectory?.objectId) { "待发送模式只读取相机指定的发送目录" }
        }
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1" xmlns:pana="urn:schemas-panasonic-com:pana">
            <ObjectID>${CameraXml.escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>
            <Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>$count</RequestedCount>
            <SortCriteria></SortCriteria><pana:X_FromCP>LumixLink2.0</pana:X_FromCP>
            </u:Browse></s:Body></s:Envelope>""".trimIndent()
        val response = http.text(device.contentDirectory, body, mapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPAction" to "urn:schemas-upnp-org:service:ContentDirectory:1#Browse"))
        return withContext(Dispatchers.Default) { CameraXml.browse(response, offset, device.contentDirectory) }.also {
            CameraConnectionLog.info("LFMCamera", "directory=received offset=$offset count=${it.entries.size}")
        }
    }

    suspend fun thumbnail(resource: CameraResource): ByteArray = commands.withLock {
        check(accessMode == CameraAccessMode.BROWSING) { "待发送模式不请求缩略图" }
        require(resource.kind == CameraResourceKind.THUMBNAIL) { "此资源不是相机缩略图" }
        val limit = 4 * 1024 * 1024
        require(resource.bytes == null || resource.bytes in 1..limit.toLong()) { "缩略图尺寸超出限制" }
        http.thumbnailBytes(resource.uri, limit)
    }

    fun close() { http.close() }
    fun interrupt() { http.interrupt() }

    private suspend fun commandStatus(mode: String, vararg fields: Pair<String, String>): String {
        return CameraXml.result(commandReply(mode, *fields))
    }

    private suspend fun commandReply(mode: String, vararg fields: Pair<String, String>): String {
        repeat(5) { attempt ->
            val reply = http.command(mode, *fields)
            if (CameraXml.result(reply) != "err_busy" || attempt == 4) return reply
            delay(500)
        }
        error("无法读取相机状态")
    }

    suspend fun download(resource: CameraResource, directory: File,
        durable: Boolean = false, onProgress: suspend (DownloadProgress) -> Unit = {}): File = commands.withLock {
        downloadCameraResource(http, resource, directory, onProgress, durable)
    }

    companion object {
        /** Save this fresh identity in this app's preferences; never read the official app's identity. */
        fun newClientId(): String = "4D454930-0100-1000-8001-" + UUID.randomUUID().toString().replace("-", "").take(12)

        suspend fun connect(
            network: Network,
            host: String,
            clientId: String,
            clientName: String = "Lumix Film Mirror",
            descriptionUri: URI = CameraAddress.description(host),
            networkInterfaceName: String? = null,
            compactLegacyIdentity: Boolean = false,
            onStep: (ConnectionStep) -> Unit = {},
        ): LumixCameraSession {
            CameraAddress.requireDescription(descriptionUri)
            require(CameraAddress.literal(descriptionUri.host).bytes.contentEquals(CameraAddress.literal(host).bytes))
            require(clientId.isNotBlank() && clientName.isNotBlank())
            val selectedInterface = networkInterfaceName?.let { NetworkInterface.getByName(it) }
            val http = CameraHttp(network, host, selectedInterface)
            var established = false
            try {
                onStep(ConnectionStep.READING_DEVICE)
                val description = descriptionUri
                val device = CameraXml.device(http.text(description), description)
                val modelLabel = device.model.takeIf { it.matches(Regex("(?:DC|DMC)-[A-Za-z0-9-]{1,24}")) } ?: "other"
                val firmwareLabel = device.firmware.takeIf { it.matches(Regex("[Vv]?[0-9]{1,3}(?:\\.[0-9]{1,3}){0,3}")) } ?: "unknown"
                CameraConnectionLog.info("LFMCamera", "deviceModel=$modelLabel firmware=$firmwareLabel requiresChallenge=${device.requiresChallenge}")
                http.validate(device.contentDirectory)
                onStep(ConnectionStep.REQUESTING_ACCESS)
                var identity = clientId
                var name = clientName
                val compactIdentity = !device.requiresChallenge &&
                    (compactLegacyIdentity || device.model.equals("DC-S5", ignoreCase = true))
                if (compactIdentity) {
                    // Same locally owned UUID as BLE, formatted like the successful S5 Sync trace.
                    // Do not substitute the reference app's identity or rewrite stored preferences.
                    identity = UUID.fromString(clientId).toString().replace("-", "").uppercase(java.util.Locale.ROOT)
                }
                if (device.requiresChallenge) {
                    val challengeReply = http.command("accctrl", "type" to "req_acc_g").trim().split(',')
                    if (challengeReply.firstOrNull() != "ok") throw CameraProtocolException(challengeReply.firstOrNull().orEmpty())
                    val challenge = requireNotNull(challengeReply.getOrNull(1)?.trim()?.toLongOrNull(16)) { "相机握手缺少挑战值" }
                    require(challenge in 0..0xffffffffL)
                    identity = CameraHttp.challengeEncode(identity, challenge.toInt())
                    name = CameraHttp.challengeEncode(name, challenge.toInt())
                }
                val approvalDeadline = SystemClock.elapsedRealtime() + 120_000L
                var approvalAttempt = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.elapsedRealtime() < approvalDeadline) { "等待相机确认超时，请在相机屏幕确认连接后重试" }
                    val reply = http.command("accctrl", "type" to if (device.requiresChallenge) "req_acc_e" else "req_acc",
                        "value" to identity, "value2" to name)
                    val fields = accessFields(reply, device.friendlyName)
                    approvalAttempt++
                    // Log only recognized state tokens; no raw reply, client ID, name or session ID.
                    val status = fields.firstOrNull()
                    val loggedStatus = status?.takeIf { it in setOf("ok_under_research", "ok_under_research_no_msg",
                        "err_busy", "ok", "upload_bt", "transfer") } ?: "other"
                    CameraConnectionLog.info("LFMCamera", "accessProtocol=${if (device.requiresChallenge) "challenge" else "legacy"} " +
                        "identityFormat=${if (compactIdentity) "compact-uuid" else "uuid"} " +
                        "attempt=$approvalAttempt status=$loggedStatus")
                    when (fields.firstOrNull()) {
                        "ok_under_research", "ok_under_research_no_msg" -> {
                            onStep(ConnectionStep.WAITING_FOR_CAMERA_APPROVAL)
                            delay(1_000)
                        }
                        "err_busy" -> delay(1_000)
                        "ok", "upload_bt", "transfer" -> {
                            require(fields.size >= 4) { "相机会话响应不完整" }
                            http.setSession(fields.getOrNull(4)?.trim())
                            onStep(ConnectionStep.READY)
                            val session = LumixCameraSession(device, http, CameraAccessMode.fromReply(fields.first()))
                            established = true
                            return session
                        }
                        else -> throw CameraProtocolException(fields.firstOrNull().orEmpty())
                    }
                }
            } finally { if (!established) http.close() }
        }

        private fun accessFields(reply: String, friendlyName: String): List<String> {
            if (reply.trimStart().startsWith("<")) return listOf(CameraXml.result(reply))
            val fields = reply.trim().split(',')
            // The camera's friendly name can itself contain commas; session is field five AFTER that name.
            if (fields.size < 4 || fields[1].equals(friendlyName, ignoreCase = true)) return fields
            for (end in 2 until fields.size - 2) {
                val name = fields.subList(1, end + 1).joinToString(",")
                if (name.equals(friendlyName, ignoreCase = true)) return listOf(fields[0], name) + fields.drop(end + 1)
            }
            return fields
        }

    }
}
