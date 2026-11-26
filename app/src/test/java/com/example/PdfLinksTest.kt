package com.example

import com.example.ui.util.PdfLang
import com.example.ui.util.PdfLinks
import com.example.ui.util.PdfText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLinksTest {

    /** A small PDF shaped like Android's (Skia) output: a nested page tree and a classic xref. */
    private fun samplePdf(): String {
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 3 >>",
            "<< /Type /Pages /Parent 2 0 R /Kids [4 0 R 5 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 360 640] /Contents 7 0 R >>",
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 360 640] /Contents 7 0 R >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 360 640] /Contents 7 0 R >>",
            "<< /Length 0 >>\nstream\n\nendstream"
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objs.forEachIndexed { i, o ->
            offsets += sb.length
            sb.append("${i + 1} 0 obj\n$o\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R /ID [<AB> <AB>] >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString()
    }

    @Test
    fun addsLinkToThirdPageAndPointsXrefAtObjects() {
        val pdf = samplePdf()
        val update = PdfLinks.buildUpdate(
            pdf, 640f,
            listOf(
                PdfLinks.Link(0, 16f, 100f, 80f, 120f, toPage = 2),
                PdfLinks.Link(2, 16f, 40f, 140f, 60f, toPage = 0, toTop = 100f)
            )
        )!!
        val full = pdf + update

        // Each new xref entry points exactly at "<n> 0 obj".
        val xrefAt = Regex("""startxref\s+(\d+)\s+%%EOF\s*$""").find(full)!!.groupValues[1].toInt()
        assertTrue(full.startsWith("xref", xrefAt))
        val entries = Regex("""(\d+) 1\n(\d{10}) 00000 n """).findAll(full.substring(xrefAt)).toList()
        assertEquals(4, entries.size) // 2 links + pages 4 and 6
        for (e in entries) {
            val n = e.groupValues[1]
            val off = e.groupValues[2].toInt()
            assertTrue("object $n offset", full.startsWith("$n 0 obj", off))
        }

        // Page 1 (object 4) jumps to page 3 (object 6); page 3 gets the back link.
        assertTrue(update.contains("/Dest [6 0 R /XYZ null 640.00 null]"))
        assertTrue(update.contains("/Dest [4 0 R /XYZ null 540.00 null]"))
        assertTrue(Regex("""4 0 obj\n<<[^\n]*/Annots \[8 0 R\] >>""").containsMatchIn(update))
        assertTrue(Regex("""6 0 obj\n<<[^\n]*/Annots \[9 0 R\] >>""").containsMatchIn(update))
        // Rect flipped to PDF's bottom-left origin.
        assertTrue(update.contains("/Rect [16.00 520.00 80.00 540.00]"))
        assertTrue(update.contains("/Size 10 /Root 1 0 R /ID [<AB> <AB>] /Prev "))
    }

    @Test
    fun linksToMissingPagesAreDropped() {
        val pdf = samplePdf()
        assertEquals(null, PdfLinks.buildUpdate(pdf, 640f, listOf(PdfLinks.Link(0, 0f, 0f, 1f, 1f, toPage = 9))))
    }

    @Test
    fun aSecondUpdateChainsOnTheFirst() {
        val once = samplePdf().let { it + PdfLinks.buildUpdate(it, 640f, listOf(PdfLinks.Link(0, 0f, 0f, 10f, 10f, 1)))!! }
        val twice = PdfLinks.buildUpdate(once, 640f, listOf(PdfLinks.Link(0, 0f, 20f, 10f, 30f, 2)))!!
        // Page 1 already has /Annots from the first update; the new link joins that array.
        assertTrue(Regex("""4 0 obj\n<<[^\n]*/Annots \[9 0 R 8 0 R\]""").containsMatchIn(twice))
    }

    @Test
    fun pdfWordsExistInEveryLanguage() {
        for (key in PdfText.keys) for (lang in PdfLang.entries) {
            assertTrue("$key/$lang", PdfText.get(key, lang).isNotBlank())
        }
        assertEquals("Udhar", PdfText.get("debit", PdfLang.GUJLISH))
        assertEquals("Jama", PdfText.get("credit", PdfLang.HINGLISH))
        assertNotEquals(PdfText.get("balance_due", PdfLang.HINGLISH), PdfText.get("balance_due", PdfLang.GUJLISH))
    }
}
