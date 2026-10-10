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
                    "friendlyName", "modelName", "X_FirmVersion", "UDN", "X_CamCapability" ->
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
        require(values["modelName"]?.isNotBlank() == true) { "未读取到相机型号" }
        return CameraDevice(description, values["friendlyName"].orEmpty(), values.getValue("modelName"),
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
        var resources = mutableListOf<CameraResource>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item", "container" -> {
                        id = requireNotNull(parser.getAttributeValue(null, "id"))
                        parent = parser.getAttributeValue(null, "parentID").orEmpty()
                        folder = parser.name == "container"
                        title = ""
                        resources = mutableListOf()
                    }
                    "title" -> if (id != null) title = parser.nextText()
                    "res" -> if (id != null) {
                        val info = parser.getAttributeValue(null, "protocolInfo").orEmpty().split(':', limit = 4)
                        val properties = info.getOrNull(3).orEmpty().split(';').mapNotNull { entry ->
                            val parts = entry.split('=', limit = 2)
                            if (parts.size == 2) parts[0] to parts[1].trim('\'', '"') else null
                        }.toMap()
                        val bytes = parser.getAttributeValue(null, "size")?.toLongOrNull()?.takeIf { it >= 0 }
                        val resourceUri = base.resolve(parser.nextText().trim())
                        resources += CameraResource(resourceUri, info.getOrNull(2).orEmpty(),
                            properties["PANASONIC.COM_PN"], properties["OriginalFileName"], bytes)
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name in setOf("item", "container")) {
                id?.let { output += CameraMedia(it, parent, title, folder, resources.toList()) }
                id = null
            }
            parser.next()
        }
        return output
    }

    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
