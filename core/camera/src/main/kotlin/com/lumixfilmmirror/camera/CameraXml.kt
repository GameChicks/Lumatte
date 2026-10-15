package com.lumixfilmmirror.camera

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

internal object CameraXml {
    private fun parser(value: String): XmlPullParser {
        require(!value.contains("<!DOCTYPE", ignoreCase = true)) { "不支持带外部声明的相机 XML" }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(value))
        }
    }

    fun device(xml: String, description: URI): CameraDevice {
        val parser = parser(xml)
        val values = mutableMapOf<String, String>()
        var service: MutableMap<String, String>? = null
        var contentUrl: String? = null
        var urlBase: URI? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (val name = parser.name) {
                    "service" -> service = mutableMapOf()
                    "serviceType", "controlURL" -> service?.set(name, parser.nextText().trim())
                    "URLBase" -> urlBase = URI(parser.nextText().trim())
                    "manufacturer", "friendlyName", "modelName", "modelNumber", "X_FirmVersion", "UDN", "X_CamCapability", "X_CamCategory" ->
                        values.putIfAbsent(name, parser.nextText().trim())
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "service") {
                if (service?.get("serviceType") == "urn:schemas-upnp-org:service:ContentDirectory:1") {
                    contentUrl = service?.get("controlURL")
                }
                service = null
            }
            parser.next()
        }
        // UPnP modelName can be the generic "Panasonic LUMIX". The actual body
        // identifier is modelNumber (the official descriptor parser reads both).
        val model = values["modelNumber"]?.takeIf {
            it.matches(Regex("(?:DC|DMC)-[A-Za-z0-9-]{1,24}", RegexOption.IGNORE_CASE))
        } ?: values["modelName"].orEmpty()
        require(model.isNotBlank()) { "未读取到相机型号" }
        require(values["manufacturer"]?.contains("Panasonic", ignoreCase = true) == true &&
            (values.containsKey("X_CamCapability") || values.containsKey("X_CamCategory") ||
                model.startsWith("DC-", ignoreCase = true) ||
                model.startsWith("DMC-", ignoreCase = true) ||
                description.path.startsWith("/Lumix/"))) { "发现的设备不是松下相机" }
        CameraConnectionLog.info("LFMCamera", "descriptorModelSource=" +
            if (model == values["modelNumber"]) "modelNumber" else "modelName")
        return CameraDevice(description, values["friendlyName"].orEmpty(), model,
            values["X_FirmVersion"].orEmpty(), values["UDN"].orEmpty(),
            values["X_CamCapability"]?.endsWith("1") == true,
            (urlBase ?: description).resolve(contentUrl ?: "/Server0/CDS_control"))
    }

    fun result(xml: String): String {
        if (!xml.trimStart().startsWith("<")) return xml.substringBefore(',').trim()
        val parser = parser(xml)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "result") return parser.nextText().trim()
            parser.next()
        }
        throw CameraProtocolException("响应缺少 result")
    }

    fun requireOk(xml: String) {
        val status = result(xml)
        if (status != "ok") throw CameraProtocolException(status)
    }

    fun contentCount(xml: String): Int {
        requireOk(xml)
        require(xml.trimStart().startsWith("<")) { "待发送数量响应格式尚不支持" }
        val parser = parser(xml)
        var count: Long? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("total_content_number", true)) {
                require(count == null) { "待发送数量响应重复" }
                count = requireNotNull(parser.nextText().trim().toLongOrNull()) { "待发送数量无效" }
            }
            parser.next()
        }
        if (count == 0xffffffffL) throw CameraProtocolException("err_busy")
        val value = requireNotNull(count) { "响应缺少待发送数量" }
        require(value in 0..Int.MAX_VALUE.toLong()) { "待发送数量超出范围" }
        return value.toInt()
    }

    /** Sync selects the last child of the first parent in autouploaddirinfo/autouploaddirlist. */
    fun autoUploadDirectory(xml: String): String {
        val parser = parser(xml)
        var infoDepth: Int? = null
        var listDepth: Int? = null
        var firstParentDepth: Int? = null
        var firstParentSeen = false
        var objectId: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            if (parser.eventType == XmlPullParser.START_TAG) {
                when {
                    name.equals("autouploaddirinfo", true) && infoDepth == null -> infoDepth = parser.depth
                    name.equals("autouploaddirlist", true) && parser.depth == (infoDepth ?: -2) + 1 ->
                        listDepth = parser.depth
                    name.equals("dir", true) && parser.depth == (listDepth ?: -2) + 1 && !firstParentSeen -> {
                        firstParentSeen = true
                        firstParentDepth = parser.depth
                    }
                    name.equals("dir", true) && parser.depth == (firstParentDepth ?: -2) + 1 -> {
                        // Retain source selection semantics: do not silently use an earlier sibling.
                        objectId = parser.getAttributeValue(null, "object_id")?.trim()
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG) {
                if (parser.depth == firstParentDepth) firstParentDepth = null
                if (parser.depth == listDepth) listDepth = null
                if (parser.depth == infoDepth) break
            }
            parser.next()
        }
        return requireNotNull(objectId?.takeIf { it.isNotBlank() && it != "0" }) {
            "相机未提供可识别的自动发送目录；请结束自动发送后使用普通传输"
        }
    }

    fun browse(xml: String, offset: Int, base: URI): CameraMediaPage {
        val parser = parser(xml)
        val fields = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name in
                setOf("Result", "NumberReturned", "TotalMatches", "UpdateID", "faultstring")) {
                fields[parser.name] = parser.nextText()
            }
            parser.next()
        }
        fields["faultstring"]?.let { throw CameraProtocolException(it) }
        val returned = requireNotNull(fields["NumberReturned"]?.trim()?.toIntOrNull()) { "缺少浏览数量" }
        val total = requireNotNull(fields["TotalMatches"]?.trim()?.toIntOrNull()) { "缺少媒体总数" }
        require(returned >= 0 && total >= 0)
        val entries = didl(fields["Result"].orEmpty(), base)
        require(entries.size == returned) { "媒体列表与返回数量不一致" }
        return CameraMediaPage(entries, offset, returned, total, fields["UpdateID"])
    }

    private fun didl(xml: String, base: URI): List<CameraMedia> {
        if (xml.isBlank()) return emptyList()
        val parser = parser(xml)
        val output = mutableListOf<CameraMedia>()
        var id: String? = null
        var parent = ""
        var folder = false
        var title = ""
        var mediaClass: String? = null
        var resources = mutableListOf<CameraResource>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item", "container" -> {
                        id = requireNotNull(parser.getAttributeValue(null, "id"))
                        parent = parser.getAttributeValue(null, "parentID").orEmpty()
                        folder = parser.name == "container"
                        title = ""
                        mediaClass = null
                        resources = mutableListOf()
                    }
                    "title" -> if (id != null) title = parser.nextText()
                    "class" -> if (id != null && parser.namespace == "urn:schemas-upnp-org:metadata-1-0/upnp/") {
                        mediaClass = parser.nextText().trim().takeIf { it.isNotEmpty() }
                    }
                    "res" -> if (id != null) {
                        val info = CameraProtocolInfo.parse(parser.getAttributeValue(null, "protocolInfo").orEmpty())
                        val bytes = parser.getAttributeValue(null, "size")?.toLongOrNull()?.takeIf { it >= 0 }
                        val resourceUri = base.resolve(parser.nextText().trim())
                        resources += CameraResource(resourceUri, info.mimeType, info.profile, info.originalName, bytes)
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name in setOf("item", "container")) {
                id?.let { output += CameraMedia(it, parent, title, folder, resources.toList(), mediaClass) }
                id = null
            }
            parser.next()
        }
        return output
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
