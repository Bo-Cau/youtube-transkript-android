package de.transkript.app

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Erzeugt eine Word-Datei (.docx) ohne zusätzliche Bibliotheken.
 * Eine .docx-Datei ist ein ZIP-Archiv mit einigen XML-Dateien.
 */
object DocxWriter {

    private const val FONT = "Calibri"

    fun write(doc: TranscriptDocument, out: OutputStream) {
        val zip = ZipOutputStream(out)
        put(zip, "[Content_Types].xml", CONTENT_TYPES)
        put(zip, "_rels/.rels", ROOT_RELS)
        put(zip, "docProps/core.xml", coreProperties(doc.title))
        put(zip, "word/document.xml", documentXml(doc))
        zip.finish()
        zip.flush()
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    internal fun documentXml(doc: TranscriptDocument): String {
        val body = StringBuilder()
        body.append(paragraph(doc.title, sizeHalfPoints = 32, bold = true, spacingAfter = 120))
        for (line in doc.meta) {
            body.append(paragraph(line, sizeHalfPoints = 18, color = "6B665E", spacingAfter = 0))
        }
        body.append(paragraph("", sizeHalfPoints = 22, spacingAfter = 120))

        val after = if (doc.layout == Layout.TIMESTAMPS) 40 else 160
        for (text in doc.paragraphs) {
            body.append(paragraph(text, sizeHalfPoints = 22, spacingAfter = after, justify = doc.layout != Layout.TIMESTAMPS))
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>" + body +
            "<w:sectPr>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1134\" w:left=\"1417\" " +
            "w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr>" +
            "</w:body></w:document>"
    }

    private fun paragraph(
        text: String,
        sizeHalfPoints: Int,
        bold: Boolean = false,
        color: String? = null,
        spacingAfter: Int,
        justify: Boolean = false
    ): String {
        val pPr = StringBuilder("<w:pPr><w:spacing w:after=\"$spacingAfter\" w:line=\"276\" w:lineRule=\"auto\"/>")
        if (justify) pPr.append("<w:jc w:val=\"both\"/>")
        pPr.append("</w:pPr>")

        val rPr = StringBuilder("<w:rPr>")
        rPr.append("<w:rFonts w:ascii=\"$FONT\" w:hAnsi=\"$FONT\" w:eastAsia=\"$FONT\" w:cs=\"$FONT\"/>")
        if (bold) rPr.append("<w:b/>")
        if (color != null) rPr.append("<w:color w:val=\"$color\"/>")
        rPr.append("<w:sz w:val=\"$sizeHalfPoints\"/><w:szCs w:val=\"$sizeHalfPoints\"/>")
        rPr.append("</w:rPr>")

        if (text.isEmpty()) return "<w:p>$pPr</w:p>"
        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r></w:p>"
    }

    private fun coreProperties(title: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<cp:coreProperties " +
            "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<dc:creator>YouTube-Transkript</dc:creator>" +
            "</cp:coreProperties>"

    /** Maskiert XML-Sonderzeichen und entfernt Zeichen, die in XML verboten sind. */
    internal fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == 0x9 || cp == 0xA || cp == 0xD -> sb.append(' ')
                cp < 0x20 || cp == 0xFFFE || cp == 0xFFFF || cp in 0xD800..0xDFFF -> Unit
                else -> sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" " +
            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
            "Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" " +
            "Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" " +
            "Target=\"docProps/core.xml\"/>" +
            "</Relationships>"
}
