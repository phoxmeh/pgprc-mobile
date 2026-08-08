package net.packetradio.mobile.data

import net.packetradio.mobile.model.QrzResult
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Thin wrapper around the QRZ XML Data API.
 *
 * Requires a QRZ.com account with an XML Data subscription.
 * API docs: https://www.qrz.com/XML/current_spec.html
 */
class QrzClient {

    /**
     * Authenticates and returns a session key, or null if login failed.
     * The session key is typically valid for several hours.
     */
    fun login(username: String, password: String): String? {
        val url = buildUrl("username" to username, "password" to password, "agent" to "PGPRCMobile-1.0")
        val data = fetch(url) ?: return null
        return data["Key"]?.takeIf { it.isNotBlank() && data["Error"] == null }
    }

    /**
     * Looks up a callsign and returns QRZ operator data, or null if not found /
     * session expired.
     */
    fun lookup(sessionKey: String, callsign: String): QrzResult? {
        val url = buildUrl("s" to sessionKey, "callsign" to callsign.uppercase())
        val data = fetch(url) ?: return null
        if (data["Error"] != null) return null
        val addr1 = data["addr1"]?.ifBlank { null }
        val addr2 = data["addr2"]?.ifBlank { null }
        val address = listOfNotNull(addr1, addr2).joinToString("\n").ifBlank { null }
        return QrzResult(
            firstName = data["fname"]?.ifBlank { null },
            lastName = data["name"]?.ifBlank { null },
            address = address,
            email = data["email"]?.ifBlank { null },
            lat = data["lat"]?.toDoubleOrNull(),
            lon = data["lon"]?.toDoubleOrNull(),
        ).takeIf { it.firstName != null || it.lastName != null }
    }

    private fun buildUrl(vararg params: Pair<String, String>): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "https://xmldata.qrz.com/xml/current/?$query"
    }

    /** GETs [urlStr] and returns a flat map of tag name → text content. */
    private fun fetch(urlStr: String): Map<String, String>? = runCatching {
        val conn = URL(urlStr).openConnection() as HttpsURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.inputStream.use { stream ->
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(stream, null)
            val data = mutableMapOf<String, String>()
            var currentTag: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name
                    XmlPullParser.TEXT -> currentTag?.let { tag -> data[tag] = parser.text }
                    XmlPullParser.END_TAG -> currentTag = null
                }
                event = parser.next()
            }
            data
        }
    }.getOrNull()
}
