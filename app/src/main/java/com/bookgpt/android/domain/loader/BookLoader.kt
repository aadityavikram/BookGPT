package com.bookgpt.android.domain.loader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.bookgpt.android.domain.Config
import com.bookgpt.android.domain.chunking.Chunker
import com.bookgpt.android.domain.chunking.TextSegment
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookLoader @Inject constructor() {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun loadBook(file: File, booksDir: File): BookDocument {
        val ext = file.extension.lowercase()
        if (ext !in Config.SUPPORTED_EXTENSIONS) {
            throw IllegalArgumentException("Unsupported file type: .$ext")
        }

        val loaded = when (ext) {
            "txt" -> readTxt(file)
            "pdf" -> readPdf(file)
            "epub" -> readEpub(file)
            else -> throw IllegalArgumentException("Unsupported file type: .$ext")
        }

        if (loaded.segments.isEmpty()) {
            throw IllegalArgumentException("No text extracted from ${file.name}")
        }

        val title = cleanTitle(loaded.title) ?: titleFromFilename(file)
        return BookDocument(
            title = title,
            filename = file.name,
            relativePath = bookRelativePath(file, booksDir),
            author = loaded.author,
            isbn = loaded.isbn,
            segments = loaded.segments,
        )
    }

    fun makeDisplayTitle(
        title: String,
        author: String? = null,
        isbn: String? = null,
        relativePath: String = "",
        ambiguous: Boolean = false,
    ): String {
        if (!ambiguous) return title
        if (!author.isNullOrBlank()) return "$title ($author)"
        if (!isbn.isNullOrBlank()) return "$title [${formatIsbn(isbn)}]"
        if (relativePath.isNotBlank() && File(relativePath).name != relativePath) {
            return "$title — $relativePath"
        }
        return "$title — ${relativePath.ifBlank { File(relativePath).name }}"
    }

    private data class LoadedContent(
        val segments: List<TextSegment>,
        val title: String? = null,
        val author: String? = null,
        val isbn: String? = null,
    )

    private fun bookRelativePath(path: File, booksDir: File): String {
        val root = booksDir.canonicalFile
        val resolved = path.canonicalFile
        return resolved.relativeTo(root).invariantSeparatorsPath
    }

    private fun cleanTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val cleaned = title.trim().replace(Regex("""\s+"""), " ")
        if (cleaned.lowercase() in setOf("untitled", "unknown", "none")) return null
        return cleaned
    }

    private fun cleanAuthor(author: String?): String? {
        if (author.isNullOrBlank()) return null
        val cleaned = author.trim().replace(Regex("""\s+"""), " ")
        if (cleaned.lowercase() in setOf("unknown", "none", "anonymous")) return null
        return cleaned
    }

    private fun normalizeIsbn(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val compact = value.replace(Regex("""[^\dXx]"""), "")
        val match = Regex("""(97[89]\d{10}|\d{9}[\dXx])""").find(compact)
            ?: Regex("""(97[89][\d-]{10,17}|\d[\d-]{8}[\dXx])""").find(value)
            ?: return null
        val digits = match.groupValues[1].replace(Regex("""[^\dXx]"""), "")
        if (digits.length !in setOf(10, 13)) return null
        return digits.uppercase()
    }

    private fun formatIsbn(isbn: String): String = when (isbn.length) {
        13 -> "${isbn.substring(0, 3)}-${isbn.substring(3, 4)}-${isbn.substring(4, 7)}-${isbn.substring(7, 12)}-${isbn.substring(12)}"
        10 -> "${isbn.substring(0, 1)}-${isbn.substring(1, 4)}-${isbn.substring(4, 9)}-${isbn.substring(9)}"
        else -> isbn
    }

    private fun titleFromFilename(path: File): String =
        path.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
            .split(Regex("""\s+"""))
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

    private fun titleFromText(text: String): String? {
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.length > 120) continue
            if (trimmed.endsWith('.') && trimmed.length > 40) continue
            if (Regex("""^(chapter|part|section|book)\s+[\divxlc]+""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
                continue
            }
            return trimmed
        }
        return null
    }

    private fun readTxt(file: File): LoadedContent {
        val text = file.readText(Charsets.UTF_8)
        return LoadedContent(
            segments = Chunker.splitTextIntoSegments(text),
            title = titleFromText(text),
        )
    }

    private fun readPdf(file: File): LoadedContent {
        val blankPages = mutableListOf<Int>()
        var metadataTitle: String? = null
        var metadataAuthor: String? = null
        var metadataIsbn: String? = null
        val segments = mutableListOf<TextSegment>()
        PDDocument.load(file).use { document ->
            val stripper = PDFTextStripper()
            for (pageNumber in 1..document.numberOfPages) {
                stripper.startPage = pageNumber
                stripper.endPage = pageNumber
                val pageText = stripper.getText(document).trim()
                if (pageText.isEmpty()) {
                    blankPages += pageNumber
                    continue
                }
                for (segment in Chunker.splitTextIntoSegments(pageText)) {
                    segments += TextSegment(
                        text = segment.text,
                        chapter = segment.chapter,
                        page = pageNumber,
                    )
                }
            }

            val info = document.documentInformation
            metadataTitle = cleanTitle(info?.title)
            metadataAuthor = cleanAuthor(info?.author)
            metadataIsbn = normalizeIsbn(info?.title) ?: normalizeIsbn(info?.keywords)
        }

        if (blankPages.isNotEmpty()) {
            segments += readPdfPagesWithOcr(file, blankPages)
        }
        val ordered = segments.sortedBy { it.page ?: 0 }
        val combined = ordered.joinToString("\n\n") { it.text }
        return LoadedContent(
            segments = ordered,
            title = metadataTitle ?: titleFromText(combined),
            author = metadataAuthor,
            isbn = metadataIsbn,
        )
    }

    private fun readPdfPagesWithOcr(file: File, pageNumbers: List<Int>): List<TextSegment> {
        val output = mutableListOf<TextSegment>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                for (pageNumber in pageNumbers) {
                    if (pageNumber !in 1..renderer.pageCount) continue
                    renderer.openPage(pageNumber - 1).use { page ->
                        val scale = 2
                        val bitmap = Bitmap.createBitmap(
                            page.width * scale,
                            page.height * scale,
                            Bitmap.Config.ARGB_8888,
                        )
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val result = Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            val text = result.text.trim()
                            if (text.isNotEmpty()) {
                                output += Chunker.splitTextIntoSegments(text).map {
                                    TextSegment(it.text, it.chapter, pageNumber)
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return output
    }

    private fun readEpub(file: File): LoadedContent {
        val segments = mutableListOf<TextSegment>()
        var metadataTitle: String? = null
        var metadataAuthor: String? = null
        var metadataIsbn: String? = null

        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            val opfEntry = entries.firstOrNull {
                it.name.endsWith(".opf", ignoreCase = true)
            }
            if (opfEntry != null) {
                zip.getInputStream(opfEntry).bufferedReader().use { reader ->
                    val opf = Jsoup.parse(reader.readText(), "", org.jsoup.parser.Parser.xmlParser())
                    metadataTitle = cleanTitle(opf.select("dc|title, title").firstOrNull()?.text())
                    metadataAuthor = cleanAuthor(opf.select("dc|creator, creator").firstOrNull()?.text())
                    for (identifier in opf.select("dc|identifier, identifier")) {
                        val isbn = normalizeIsbn(identifier.text())
                        if (isbn != null) {
                            metadataIsbn = isbn
                            break
                        }
                    }
                }
            }

            val htmlEntries = entries
                .filter {
                    !it.isDirectory && (
                        it.name.endsWith(".xhtml", true) ||
                            it.name.endsWith(".html", true) ||
                            it.name.endsWith(".htm", true)
                        )
                }
                .sortedBy { it.name }

            for (entry in htmlEntries) {
                val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val soup = Jsoup.parse(html)
                val chapter = chapterFromHtml(soup)
                val body = soup.body()?.text()?.trim().orEmpty().ifBlank {
                    soup.text().trim()
                }
                if (body.isBlank()) continue
                for (segment in Chunker.splitTextIntoSegments(body)) {
                    segments += TextSegment(
                        text = segment.text,
                        chapter = segment.chapter.ifBlank { chapter },
                        page = null,
                    )
                }
            }
        }

        val combined = segments.joinToString("\n\n") { it.text }
        return LoadedContent(
            segments = segments,
            title = metadataTitle ?: titleFromText(combined),
            author = metadataAuthor,
            isbn = metadataIsbn,
        )
    }

    private fun chapterFromHtml(soup: org.jsoup.nodes.Document): String {
        for (tag in listOf("h1", "h2", "h3", "title")) {
            val heading = soup.selectFirst(tag)?.text()?.trim().orEmpty()
            if (heading.isNotEmpty()) return heading
        }
        return ""
    }
}
