package de.transkript.app

import java.util.Locale

/** Macht aus den Untertitel-Schnipseln einen gut lesbaren Text. */
object TranscriptFormatter {

    private val NOISE = Regex(
        "\\[(Musik|Music|Applaus|Applause|Lachen|Laughter|Gelächter|Musique|Música|Musica)]",
        RegexOption.IGNORE_CASE
    )
    private val WHITESPACE = Regex("\\s+")
    private val FORBIDDEN_IN_FILENAME = Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]")

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

    /** Fließtext mit einem Absatz pro Minute, oder eine Zeile pro Schnipsel mit Zeitstempel. */
    fun body(segments: List<Segment>, withTimestamps: Boolean): String {
        val cleaned = segments.mapNotNull { segment ->
            val text = clean(segment.text)
            if (text.isEmpty()) null else Segment(segment.start, text)
        }
        if (withTimestamps) {
            return cleaned.joinToString("\n") { "[${time(it.start)}] ${it.text}" }
        }

        val paragraphs = ArrayList<String>()
        val current = ArrayList<String>()
        var paragraphStart = 0.0
        for (segment in cleaned) {
            if (current.isNotEmpty() && segment.start - paragraphStart > PARAGRAPH_SECONDS) {
                paragraphs.add(current.joinToString(" "))
                current.clear()
                paragraphStart = segment.start
            }
            if (current.isEmpty()) paragraphStart = segment.start
            current.add(segment.text)
        }
        if (current.isNotEmpty()) paragraphs.add(current.joinToString(" "))
        return paragraphs.joinToString("\n\n")
    }

    fun header(result: TranscriptResult): String {
        val kind = if (result.track.isGenerated) {
            "automatisch erzeugte YouTube-Untertitel"
        } else {
            "manuell erstellte YouTube-Untertitel"
        }
        return "Titel: ${result.title}\n" +
            "Link: https://www.youtube.com/watch?v=${result.videoId}\n" +
            "Sprache: ${result.track.name} (${result.track.languageCode}), $kind\n\n"
    }

    fun document(result: TranscriptResult, withTimestamps: Boolean): String =
        header(result) + body(result.segments, withTimestamps) + "\n"

    fun wordCount(text: String): Int =
        text.split(WHITESPACE).count { it.isNotEmpty() }

    fun fileName(title: String): String {
        val cleaned = WHITESPACE.replace(FORBIDDEN_IN_FILENAME.replace(title, ""), " ").trim(' ', '.')
        val shortened = if (cleaned.length > 100) cleaned.substring(0, 100).trim() else cleaned
        return (shortened.ifEmpty { "Transkript" }) + ".txt"
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
