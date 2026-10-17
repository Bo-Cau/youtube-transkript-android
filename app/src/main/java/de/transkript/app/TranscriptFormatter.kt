package de.transkript.app

import java.util.Locale

/** Wie das Transkript dargestellt wird. */
enum class Layout { PARAGRAPHS, COMPACT, TIMESTAMPS }

/** Fertig aufbereitetes Dokument, aus dem TXT, Word und PDF erzeugt werden. */
data class TranscriptDocument(
    val title: String,
    val meta: List<String>,
    val paragraphs: List<String>,
    val layout: Layout
)

/** Macht aus den Untertitel-Schnipseln einen gut lesbaren Text. */
object TranscriptFormatter {

    private val NOISE = Regex(
        "\\[(Musik|Music|Applaus|Applause|Lachen|Laughter|Gelächter|Musique|Música|Musica)]",
        RegexOption.IGNORE_CASE
    )
    private val WHITESPACE = Regex("\\s+")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,.;:!?…])")
    private val DOUBLE_COMMA = Regex(",\\s*,+")
    private val LEADING_PUNCTUATION = Regex("^[\\s,;:]+")
    private val FORBIDDEN_IN_FILENAME = Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]")

    // Füllwörter, die in keiner der unterstützten Sprachen eine eigene Bedeutung haben
    private const val FILLERS_ALL = "äh+m*|ö+h+m*|e+h+m+|h+m+|m+h+m+|m{2,}|u+h+m*|e+r+m+|e+u+h+"

    // "um" ist im Deutschen ein echtes Wort, "eh" im Deutschen auch – daher nur sprachabhängig
    private val FILLERS_BY_LANGUAGE = mapOf(
        "en" to "u+m+|a+h+",
        "es" to "e+h+|e+m+",
        "it" to "e+h+",
        "pt" to "e+h+|h+u+m+",
        "fr" to "b+e+n+"
    )

    private const val PARAGRAPH_SECONDS = 60.0

    fun clean(text: String): String =
        WHITESPACE.replace(NOISE.replace(text.replace('\n', ' '), ""), " ").trim()

    fun time(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", m, s)
        }
    }

    // ------------------------------------------------------------------ Absätze

    private fun cleanedSegments(segments: List<Segment>): List<Segment> = segments.mapNotNull { segment ->
        val text = clean(segment.text)
        if (text.isEmpty()) null else Segment(segment.start, text)
    }

    fun paragraphs(segments: List<Segment>, layout: Layout, languageCode: String = ""): List<String> {
        val cleaned = cleanedSegments(segments)
        return when (layout) {
            Layout.TIMESTAMPS -> cleaned.map { "[${time(it.start)}] ${it.text}" }
            Layout.COMPACT -> listOf(compact(cleaned.map { it.text }, languageCode)).filter { it.isNotEmpty() }
            Layout.PARAGRAPHS -> byMinute(cleaned)
        }
    }

    private fun byMinute(cleaned: List<Segment>): List<String> {
        val result = ArrayList<String>()
        val current = ArrayList<String>()
        var paragraphStart = 0.0
        for (segment in cleaned) {
            if (current.isNotEmpty() && segment.start - paragraphStart > PARAGRAPH_SECONDS) {
                result.add(current.joinToString(" "))
                current.clear()
            }
            if (current.isEmpty()) paragraphStart = segment.start
            current.add(segment.text)
        }
        if (current.isNotEmpty()) result.add(current.joinToString(" "))
        return result
    }

    /** Ein einziger Fließtext: ohne Füllwörter, ohne doppelte Zeilen, ohne überflüssige Leerzeichen. */
    fun compact(texts: List<String>, languageCode: String): String {
        val withoutDuplicates = ArrayList<String>()
        for (text in texts) {
            if (withoutDuplicates.isEmpty() || !withoutDuplicates.last().equals(text, ignoreCase = true)) {
                withoutDuplicates.add(text)
            }
        }
        var result = removeFillers(withoutDuplicates.joinToString(" "), languageCode)
        result = DOUBLE_COMMA.replace(result, ",")
        result = SPACE_BEFORE_PUNCTUATION.replace(result, "$1")
        result = WHITESPACE.replace(result, " ")
        result = LEADING_PUNCTUATION.replace(result, "")
        return result.trim()
    }

    fun removeFillers(text: String, languageCode: String): String {
        val base = languageCode.lowercase(Locale.ROOT).substringBefore('-')
        val extra = FILLERS_BY_LANGUAGE[base]
        val words = if (extra != null) "$FILLERS_ALL|$extra" else FILLERS_ALL
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}'’-])(?:$words)(?![\\p{L}\\p{N}'’-])[,.…]*",
            RegexOption.IGNORE_CASE
        )
        return pattern.replace(text, "")
    }

    // ------------------------------------------------------------------ Dokument

    fun build(result: TranscriptResult, layout: Layout): TranscriptDocument {
        val link = "https://www.youtube.com/watch?v=${result.videoId}"
        val kind = if (result.track.isGenerated) "automatisch erzeugt" else "manuell erstellt"
        // YouTube nennt automatische Spuren oft schon "Deutsch (automatisch erzeugt)"
        val language = if (result.track.name.contains('(')) {
            "${result.track.name}, ${result.track.languageCode}"
        } else {
            "${result.track.name} (${result.track.languageCode}), $kind"
        }
        val meta = if (layout == Layout.COMPACT) {
            listOf("$link · $language")
        } else {
            listOf("Link: $link", "Sprache: $language")
        }
        return TranscriptDocument(
            title = result.title,
            meta = meta,
            paragraphs = paragraphs(result.segments, layout, result.track.languageCode),
            layout = layout
        )
    }

    fun toText(doc: TranscriptDocument): String {
        val separator = if (doc.layout == Layout.TIMESTAMPS) "\n" else "\n\n"
        val head = if (doc.layout == Layout.COMPACT) {
            doc.title + "\n" + doc.meta.joinToString("\n")
        } else {
            "Titel: ${doc.title}\n" + doc.meta.joinToString("\n")
        }
        return head + "\n\n" + doc.paragraphs.joinToString(separator) + "\n"
    }

    /** Ersatz, falls nur noch der angezeigte Text vorhanden ist (z. B. nach einem App-Neustart). */
    fun fromText(title: String, text: String): TranscriptDocument {
        val blocks = text.trim().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val meta = blocks.firstOrNull()?.lines()?.drop(1) ?: emptyList()
        val paragraphs = blocks.drop(1)
        return TranscriptDocument(title.ifBlank { "Transkript" }, meta, paragraphs, Layout.PARAGRAPHS)
    }

    // ------------------------------------------------------------------ Hilfen

    fun wordCount(text: String): Int =
        text.split(WHITESPACE).count { it.isNotEmpty() }

    fun fileName(title: String, extension: String): String {
        val cleaned = WHITESPACE.replace(FORBIDDEN_IN_FILENAME.replace(title, ""), " ").trim(' ', '.')
        val shortened = if (cleaned.length > 100) cleaned.substring(0, 100).trim() else cleaned
        return (shortened.ifEmpty { "Transkript" }) + "." + extension
    }

    /** "de, EN; fr" -> [de, en, fr] */
    fun parseLanguages(input: String): List<String> {
        val list = input.split(',', ';', ' ')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
        return list.ifEmpty { listOf("de", "en") }
    }
}
