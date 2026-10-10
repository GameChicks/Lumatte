package com.lumixfilmmirror.camera

import android.net.Network
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.io.File
import java.util.UUID

/** Connection and media operations only. No remote shooting or parameter-control surface. */
class LumixCameraSession private constructor(
    val device: CameraDevice,
    private val http: CameraHttp,
) {
    private val commands = Mutex()

    suspend fun prepareBrowsing(includeRaw: Boolean = true) = commands.withLock {
        val playback = commandStatus("camcmd", "value" to "playmode")
        if (playback != "ok") throw CameraProtocolException(playback)
        val status = commandStatus("setsetting", "type" to "raw_img_send",
            "value" to if (includeRaw) "enable" else "disable")
        // Older cameras may not support RAW transfer; use only resources they advertise.
        if (status !in setOf("ok", "err_non_support", "err_param")) throw CameraProtocolException(status)
    }

    suspend fun browse(objectId: String = "0", offset: Int = 0, count: Int = 50): CameraMediaPage =
        commands.withLock {
            require(offset >= 0 && count in 1..100)
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
            CameraXml.browse(response, offset, device.contentDirectory)
        }

    fun close() { http.setSession(null) }

    private suspend fun commandStatus(mode: String, vararg fields: Pair<String, String>): String {
        repeat(5) { attempt ->
            val status = CameraXml.result(http.command(mode, *fields))
            if (status != "err_busy" || attempt == 4) return status
            delay(500)
        }
        error("无法读取相机状态")
    }

    suspend fun download(resource: CameraResource, directory: File,
        onProgress: (DownloadProgress) -> Unit = {}): File = commands.withLock {
        downloadCameraResource(http, resource, directory, onProgress)
    }

    companion object {
        /** Save this fresh identity in this app's preferences; never read the official app's identity. */
        fun newClientId(): String = "4D454930-0100-1000-8001-" + UUID.randomUUID().toString().replace("-", "").take(12)

        suspend fun connect(
            network: Network,
            host: String,
            clientId: String,
            clientName: String = "Lumix Film Mirror",
            onStep: (ConnectionStep) -> Unit = {},
        ): LumixCameraSession {
            require(host.matches(Regex("[A-Za-z0-9.-]+"))) { "无效的相机地址" }
            require(clientId.isNotBlank() && clientName.isNotBlank())
            val http = CameraHttp(network, host)
            onStep(ConnectionStep.READING_DEVICE)
            val description = URI("http://$host:60606/Lumix/Server0/ddd")
            val device = CameraXml.device(http.text(description), description)
            http.validate(device.contentDirectory)
            onStep(ConnectionStep.REQUESTING_ACCESS)
            var identity = clientId
            var name = clientName
            if (device.requiresChallenge) {
                val challengeReply = http.command("accctrl", "type" to "req_acc_g").trim().split(',')
                if (challengeReply.firstOrNull() != "ok") throw CameraProtocolException(challengeReply.firstOrNull().orEmpty())
                val challenge = requireNotNull(challengeReply.getOrNull(1)?.trim()?.toLongOrNull(16)) { "相机握手缺少挑战值" }
                require(challenge in 0..0xffffffffL)
                identity = CameraHttp.challengeEncode(identity, challenge.toInt())
                name = CameraHttp.challengeEncode(name, challenge.toInt())
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val reply = http.command("accctrl", "type" to if (device.requiresChallenge) "req_acc_e" else "req_acc",
                    "value" to identity, "value2" to name)
                val fields = accessFields(reply, device.friendlyName)
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
                        return LumixCameraSession(device, http)
                    }
                    else -> throw CameraProtocolException(fields.firstOrNull().orEmpty())
                }
            }
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
