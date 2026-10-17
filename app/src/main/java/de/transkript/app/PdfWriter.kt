package de.transkript.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.OutputStream

/** Erzeugt eine PDF-Datei (A4) mit den Bordmitteln von Android. */
object PdfWriter {

    // Maße in Punkt (1/72 Zoll), A4
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 56
    private const val FOOTER_SPACE = 24

    private const val BODY_SIZE = 11f
    private const val TITLE_SIZE = 17
    private const val META_SIZE = 9

    fun write(doc: TranscriptDocument, out: OutputStream) {
        val text = buildText(doc)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_SIZE
            color = Color.BLACK
        }
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val contentHeight = PAGE_HEIGHT - 2 * MARGIN - FOOTER_SPACE

        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(true)
            .build()

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
        }

        val pdf = PdfDocument()
        try {
            var line = 0
            var pageNumber = 1
            val lineCount = layout.lineCount
            do {
                val top = if (lineCount > 0) layout.getLineTop(line) else 0
                var end = line
                while (end < lineCount && layout.getLineBottom(end) - top <= contentHeight) end++
                if (end == line && lineCount > 0) end = line + 1

                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                val page = pdf.startPage(info)
                val canvas = page.canvas

                if (lineCount > 0) {
                    val bottom = layout.getLineBottom(end - 1)
                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
                    canvas.clipRect(0f, top.toFloat(), contentWidth.toFloat(), bottom.toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                }

                canvas.drawText(
                    pageNumber.toString(),
                    PAGE_WIDTH / 2f,
                    (PAGE_HEIGHT - MARGIN + FOOTER_SPACE / 2).toFloat(),
                    footerPaint
                )
                pdf.finishPage(page)

                line = end
                pageNumber++
            } while (line < lineCount)

            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun buildText(doc: TranscriptDocument): CharSequence {
        val sb = SpannableStringBuilder()

        val titleStart = sb.length
        sb.append(doc.title)
        sb.setSpan(StyleSpan(Typeface.BOLD), titleStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(TITLE_SIZE), titleStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")

        if (doc.meta.isNotEmpty()) {
            val metaStart = sb.length
            sb.append(doc.meta.joinToString("\n"))
            sb.setSpan(AbsoluteSizeSpan(META_SIZE), metaStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.rgb(107, 102, 94)), metaStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n")
        }
        sb.append("\n")

        val separator = if (doc.layout == Layout.TIMESTAMPS) "\n" else "\n\n"
        sb.append(doc.paragraphs.joinToString(separator))
        return sb
    }
}
