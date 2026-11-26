package com.example.ui.util

import java.io.File

/**
 * Adds tappable "jump to page" links to a PDF made by Android's PdfDocument, which cannot
 * make links itself. The statement uses them so tapping a row's photo icon opens the photo
 * page, and "Back to entry" on that page returns to the row (like Khatabook's photo links,
 * but offline: the photos are inside the PDF).
 *
 * It appends a standard incremental update (new link objects, the changed page objects and
 * a new xref section) to the end of the file, so the original bytes are untouched. If the
 * file is not in the expected shape it throws before writing anything.
 */
object PdfLinks {

    /**
     * A tappable box on page [fromPage] (0-based), in points measured from the page's
     * top-left corner, that jumps to page [toPage] scrolled to [toTop] points from its top.
     */
    data class Link(
        val fromPage: Int,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val toPage: Int,
        val toTop: Float = 0f
    )

    fun addLinks(file: File, pageHeight: Float, links: List<Link>) {
        if (links.isEmpty()) return
        val bytes = file.readBytes()
        val extra = buildUpdate(String(bytes, Charsets.ISO_8859_1), pageHeight, links) ?: return
        file.appendBytes(extra.toByteArray(Charsets.ISO_8859_1))
    }

    /** The text to append to [pdf], or null when no link has a valid page. */
    internal fun buildUpdate(pdf: String, pageHeight: Float, links: List<Link>): String? {
        val sx = pdf.lastIndexOf("startxref")
        require(sx >= 0) { "no startxref" }
        val prevXref = Regex("""startxref\s+(\d+)""").find(pdf, sx)!!.groupValues[1].toInt()
        val offsets = readXref(pdf, prevXref)

        val trailerStart = pdf.indexOf("trailer", prevXref)
        require(trailerStart >= 0) { "no trailer" }
        val trailer = pdf.substring(trailerStart, sx)
        val size = Regex("""/Size\s+(\d+)""").find(trailer)!!.groupValues[1].toInt()
        val root = Regex("""/Root\s+(\d+)\s+0\s+R""").find(trailer)!!.groupValues[1].toInt()
        val info = Regex("""/Info\s+\d+\s+0\s+R""").find(trailer)?.value
        val id = Regex("""/ID\s*\[[^\]]*\]""").find(trailer)?.value

        fun body(num: Int): String {
            val at = offsets[num] ?: error("object $num missing")
            val start = pdf.indexOf("obj", at) + 3
            val end = pdf.indexOf("endobj", start)
            require(start > 2 && end > start) { "object $num unreadable" }
            return pdf.substring(start, end).trim()
        }

        // Pages in reading order (Skia nests them in a tree of /Pages nodes).
        val pages = mutableListOf<Int>()
        fun walk(num: Int, depth: Int) {
            require(depth < 32) { "page tree too deep" }
            val b = body(num)
            if (Regex("""/Type\s*/Pages\b""").containsMatchIn(b)) {
                val kids = Regex("""/Kids\s*\[([^\]]*)\]""").find(b)!!.groupValues[1]
                Regex("""(\d+)\s+0\s+R""").findAll(kids).forEach { walk(it.groupValues[1].toInt(), depth + 1) }
            } else if (Regex("""/Type\s*/Page\b""").containsMatchIn(b)) {
                pages += num
            }
        }
        val pagesRoot = Regex("""/Pages\s+(\d+)\s+0\s+R""").find(body(root))!!.groupValues[1].toInt()
        walk(pagesRoot, 0)

        val out = StringBuilder("\n")
        val base = pdf.length
        val newOffsets = sortedMapOf<Int, Int>()
        val annotsByPage = linkedMapOf<Int, MutableList<Int>>()
        var next = size
        for (l in links) {
            if (l.fromPage !in pages.indices || l.toPage !in pages.indices) continue
            val n = next++
            newOffsets[n] = base + out.length
            out.append("$n 0 obj\n<< /Type /Annot /Subtype /Link /Rect [")
                .append("${num(l.left)} ${num(pageHeight - l.bottom)} ${num(l.right)} ${num(pageHeight - l.top)}")
                .append("] /Border [0 0 0] /Dest [${pages[l.toPage]} 0 R /XYZ null ${num(pageHeight - l.toTop)} null] >>\nendobj\n")
            annotsByPage.getOrPut(l.fromPage) { mutableListOf() } += n
        }
        if (annotsByPage.isEmpty()) return null

        for ((pageIndex, annots) in annotsByPage) {
            val num = pages[pageIndex]
            val b = body(num)
            val refs = annots.joinToString(" ") { "$it 0 R" }
            val existing = Regex("""/Annots\s*\[""").find(b)
            val newBody = if (existing != null) {
                b.substring(0, existing.range.last + 1) + refs + " " + b.substring(existing.range.last + 1)
            } else {
                val close = b.lastIndexOf(">>")
                require(close > 0) { "page $num is not a dictionary" }
                b.substring(0, close) + " /Annots [$refs] >>"
            }
            newOffsets[num] = base + out.length
            out.append("$num 0 obj\n$newBody\nendobj\n")
        }

        val xrefAt = base + out.length
        out.append("xref\n0 1\n0000000000 65535 f \n")
        for ((n, off) in newOffsets) out.append("$n 1\n").append(off.toString().padStart(10, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size $next /Root $root 0 R")
        info?.let { out.append(" ").append(it) }
        id?.let { out.append(" ").append(it) }
        out.append(" /Prev $prevXref >>\nstartxref\n$xrefAt\n%%EOF\n")
        return out.toString()
    }

    /** Object number -> byte offset, from a classic xref table (and its /Prev chain). */
    private fun readXref(pdf: String, at: Int): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        var pos: Int? = at
        var guard = 0
        while (pos != null && guard++ < 50) {
            require(pdf.startsWith("xref", pos)) { "xref stream PDFs are not supported" }
            val trailerAt = pdf.indexOf("trailer", pos)
            val lines = pdf.substring(pos + 4, trailerAt).trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            var i = 0
            while (i < lines.size) {
                val (first, count) = lines[i].split(Regex("""\s+""")).map { it.toInt() }
                for (k in 0 until count) {
                    val parts = lines[i + 1 + k].split(Regex("""\s+"""))
                    val n = first + k
                    // Newer sections are read first, so keep what is already there.
                    if (parts[2] == "n" && n !in result) result[n] = parts[0].toInt()
                }
                i += 1 + count
            }
            val trailerEnd = pdf.indexOf("startxref", trailerAt).let { if (it < 0) pdf.length else it }
            pos = Regex("""/Prev\s+(\d+)""").find(pdf.substring(trailerAt, trailerEnd))?.groupValues?.get(1)?.toInt()
        }
        return result
    }

    private fun num(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}
