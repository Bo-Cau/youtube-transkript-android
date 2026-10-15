package de.transkript.app

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fehler mit einer verständlichen Meldung für die Oberfläche. */
class TranscriptException(message: String) : Exception(message)

data class Segment(val start: Double, val text: String)

data class CaptionTrack(
    val baseUrl: String,
    val languageCode: String,
    val name: String,
    val isGenerated: Boolean
)

data class TranscriptResult(
    val videoId: String,
    val title: String,
    val track: CaptionTrack,
    val segments: List<Segment>
)

/**
 * Holt die Untertitel eines YouTube-Videos.
 *
 * Ablauf:
 *  1. Videoseite laden (Titel, API-Schlüssel, eingebettete Player-Daten).
 *  2. Untertitelspuren über die interne Player-Schnittstelle abfragen,
 *     ersatzweise aus den Player-Daten der Seite lesen.
 *  3. Passende Sprache wählen und die Untertitel herunterladen (XML, ersatzweise JSON).
 */
object YouTubeTranscripts {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    // Vorab-Zustimmung zum Cookie-Hinweis, den YouTube in der EU zeigt
    private const val CONSENT_COOKIES = "CONSENT=YES+cb; SOCS=CAI"

    private const val ANDROID_CLIENT_VERSION = "20.10.38"

    private val ID_IN_URL =
        Regex("(?:v=|/shorts/|/embed/|/live/|/v/|youtu\\.be/)([A-Za-z0-9_-]{11})")
    private val BARE_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val API_KEY = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")
    private val META_TITLE = Regex("<meta name=\"title\" content=\"([^\"]*)\"")
    private val TEXT_TAG = Regex("<text\\b([^>]*)>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
    private val P_TAG = Regex("<p\\b([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val ANY_TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")
    private val FMT_PARAM = Regex("&fmt=[^&]*")

    // ------------------------------------------------------------------ öffentlich

    fun extractVideoId(input: String): String? {
        val text = input.trim()
        if (BARE_ID.matches(text)) return text
        return ID_IN_URL.find(text)?.groupValues?.get(1)
    }

    fun fetch(videoId: String, preferredLanguages: List<String>): TranscriptResult {
        val html = httpGet("https://www.youtube.com/watch?v=$videoId&hl=de")

        if (html.contains("action=\"https://consent.youtube.com/s\"")) {
            throw TranscriptException(
                "YouTube verlangt eine Cookie-Zustimmung, die die App nicht automatisch geben konnte. " +
                    "Bitte später erneut versuchen."
            )
        }
        if (html.contains("class=\"g-recaptcha\"")) {
            throw TranscriptException(
                "YouTube hat ungewöhnlich viele Anfragen erkannt und eine Sicherheitsabfrage gestellt. " +
                    "Bitte später erneut versuchen oder das Netzwerk wechseln (z. B. WLAN statt Mobilfunk)."
            )
        }

        var title: String? = extractMetaTitle(html)
        var tracks: List<CaptionTrack> = emptyList()
        var problem: String? = null

        // 1. Interne Player-Schnittstelle
        val apiKey = API_KEY.find(html)?.groupValues?.get(1)
        if (apiKey != null) {
            try {
                val player = innertubePlayer(videoId, apiKey)
                if (title == null) title = videoTitle(player)
                tracks = parseTracks(player)
                problem = playabilityProblem(player)
            } catch (e: IOException) {
                // weiter mit Schritt 2
            } catch (e: JSONException) {
                // weiter mit Schritt 2
            }
        }

        // 2. Player-Daten aus der Seite
        if (tracks.isEmpty()) {
            val player = extractJsonObjectAfter(html, "ytInitialPlayerResponse")
            if (player != null) {
                if (title == null) title = videoTitle(player)
                tracks = parseTracks(player)
                if (tracks.isEmpty() && problem == null) problem = playabilityProblem(player)
            }
        }

        if (tracks.isEmpty()) {
            throw TranscriptException(
                problem ?: "Für dieses Video gibt es keine Untertitel, weder manuell erstellte noch automatisch erzeugte."
            )
        }

        val track = chooseTrack(tracks, preferredLanguages)
        val segments = downloadSegments(track)
        if (segments.isEmpty()) {
            throw TranscriptException(
                "Die Untertitel wurden gefunden, konnten aber nicht geladen werden. " +
                    "YouTube schränkt den Zugriff manchmal vorübergehend ein. Bitte später erneut versuchen."
            )
        }
        return TranscriptResult(videoId, title ?: videoId, track, segments)
    }

    // ------------------------------------------------------------------ Spurauswahl

    /** Pro Wunschsprache zuerst manuelle, dann automatische Untertitel. */
    fun chooseTrack(tracks: List<CaptionTrack>, preferredLanguages: List<String>): CaptionTrack {
        for (lang in preferredLanguages) {
            tracks.firstOrNull { !it.isGenerated && matchesLanguage(it, lang) }?.let { return it }
            tracks.firstOrNull { it.isGenerated && matchesLanguage(it, lang) }?.let { return it }
        }
        return tracks.firstOrNull { !it.isGenerated } ?: tracks.first()
    }

    private fun matchesLanguage(track: CaptionTrack, lang: String): Boolean =
        track.languageCode.equals(lang, ignoreCase = true) ||
            track.languageCode.startsWith("$lang-", ignoreCase = true)

    // ------------------------------------------------------------------ Player-Daten

    private fun innertubePlayer(videoId: String, apiKey: String): JSONObject {
        val client = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", ANDROID_CLIENT_VERSION)
            .put("hl", "de")
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
        return JSONObject(httpPost("https://www.youtube.com/youtubei/v1/player?key=$apiKey", body.toString()))
    }

    internal fun parseTracks(player: JSONObject): List<CaptionTrack> {
        val array = player.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: return emptyList()

        val result = ArrayList<CaptionTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val rawUrl = item.optString("baseUrl", "")
            if (rawUrl.isBlank()) continue
            val url = if (rawUrl.startsWith("/")) "https://www.youtube.com$rawUrl" else rawUrl
            val code = item.optString("languageCode", "")
            val nameObject = item.optJSONObject("name")
            val simple = nameObject?.optString("simpleText", "") ?: ""
            val runs = nameObject?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
            val name = when {
                simple.isNotBlank() -> simple
                runs.isNotBlank() -> runs
                else -> code
            }
            result.add(CaptionTrack(url, code, name, item.optString("kind", "") == "asr"))
        }
        return result
    }

    private fun videoTitle(player: JSONObject): String? =
        player.optJSONObject("videoDetails")?.optString("title", "")?.takeIf { it.isNotBlank() }

    private fun playabilityProblem(player: JSONObject): String? {
        val status = player.optJSONObject("playabilityStatus") ?: return null
        val code = status.optString("status", "")
        if (code.isEmpty() || code == "OK") return null
        val reason = status.optString("reason", "").trim()
        return when (code) {
            "LOGIN_REQUIRED" ->
                "Das Video ist nur mit Anmeldung abrufbar (z. B. altersbeschränkt oder privat)." +
                    if (reason.isNotEmpty()) "\n\nYouTube meldet: $reason" else ""
            "ERROR" ->
                "Das Video ist nicht verfügbar." + if (reason.isNotEmpty()) "\n\nYouTube meldet: $reason" else ""
            else ->
                "Das Video kann nicht abgerufen werden." +
                    if (reason.isNotEmpty()) "\n\nYouTube meldet: $reason" else ""
        }
    }

    private fun extractMetaTitle(html: String): String? =
        META_TITLE.find(html)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() }

    /** Liest das JSON-Objekt, das in der Seite nach `name = ` steht (Klammern werden mitgezählt). */
    internal fun extractJsonObjectAfter(html: String, name: String): JSONObject? {
        var index = html.indexOf("$name = ")
        if (index < 0) index = html.indexOf("$name=")
        if (index < 0) return null
        val start = html.indexOf('{', index)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JSONObject(html.substring(start, i + 1))
                            } catch (e: JSONException) {
                                null
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Untertitel laden

    private fun downloadSegments(track: CaptionTrack): List<Segment> {
        val base = FMT_PARAM.replace(track.baseUrl, "")

        try {
            val segments = parseXml(httpGet(base))
            if (segments.isNotEmpty()) return segments
        } catch (e: IOException) {
            // nächstes Format versuchen
        }

        try {
            val segments = parseJson3(httpGet("$base&fmt=json3"))
            if (segments.isNotEmpty()) return segments
        } catch (e: IOException) {
            // nichts mehr zu versuchen
        } catch (e: JSONException) {
            // nichts mehr zu versuchen
        }
        return emptyList()
    }

    /** Versteht das klassische Format (<text start=..>) und srv3 (<p t=..>). */
    internal fun parseXml(xml: String): List<Segment> {
        val result = ArrayList<Segment>()
        for (m in TEXT_TAG.findAll(xml)) {
            val start = attribute(m.groupValues[1], "start")?.toDoubleOrNull() ?: continue
            val text = cleanMarkup(m.groupValues[2])
            if (text.isNotBlank()) result.add(Segment(start, text))
        }
        if (result.isNotEmpty()) return result

        for (m in P_TAG.findAll(xml)) {
            val startMs = attribute(m.groupValues[1], "t")?.toDoubleOrNull() ?: continue
            val text = cleanMarkup(m.groupValues[2])
            if (text.isNotBlank()) result.add(Segment(startMs / 1000.0, text))
        }
        return result
    }

    internal fun parseJson3(json: String): List<Segment> {
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        val result = ArrayList<Segment>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val segs = event.optJSONArray("segs") ?: continue
            val builder = StringBuilder()
            for (j in 0 until segs.length()) {
                builder.append(segs.optJSONObject(j)?.optString("utf8", "") ?: "")
            }
            val text = builder.toString()
            if (text.isNotBlank()) result.add(Segment(event.optLong("tStartMs", 0L) / 1000.0, text))
        }
        return result
    }

    private fun attribute(attributes: String, name: String): String? =
        Regex("(?:^|\\s)$name=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1)

    /** Entfernt Tags und löst (teils doppelt kodierte) HTML-Entities auf. */
    private fun cleanMarkup(raw: String): String {
        val withoutTags = ANY_TAG.replace(raw, "")
        val decoded = decodeEntities(decodeEntities(withoutTags))
        return ANY_TAG.replace(decoded, "")
    }

    internal fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    entity.substring(2).toIntOrNull(16)
                } else {
                    entity.substring(1).toIntOrNull()
                }
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
        connection.setRequestProperty("Cookie", CONSENT_COOKIES)
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val code = connection.responseCode
        if (code == 429) {
            throw TranscriptException(
                "YouTube meldet zu viele Anfragen. Bitte ein paar Minuten warten und erneut versuchen."
            )
        }
        if (code !in 200..299) throw IOException("HTTP $code")
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun httpGet(url: String): String {
        val connection = openConnection(url)
        try {
            return readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, json: String): String {
        val connection = openConnection(url)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return readBody(connection)
        } finally {
            connection.disconnect()
        }
    }
}
