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
            val entriesByName = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .associateBy { it.name.trimStart('/') }

            val opfPath = findOpfPath(zip, entriesByName.keys)
            val documentPaths = if (opfPath != null) {
                val opfEntry = entriesByName[opfPath]
                    ?: entriesByName.entries.firstOrNull {
                        it.key.equals(opfPath, ignoreCase = true)
                    }?.value
                if (opfEntry != null) {
                    val opfText = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
                    val opf = Jsoup.parse(opfText, "", org.jsoup.parser.Parser.xmlParser())
                    metadataTitle = cleanTitle(opf.select("dc|title, title").firstOrNull()?.text())
                    metadataAuthor = cleanAuthor(opf.select("dc|creator, creator").firstOrNull()?.text())
                    for (identifier in opf.select("dc|identifier, identifier")) {
                        val isbn = normalizeIsbn(identifier.text())
                        if (isbn != null) {
                            metadataIsbn = isbn
                            break
                        }
                    }
                    spineDocumentPaths(opf, opfPath, entriesByName.keys)
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }.ifEmpty {
                // Fallback only when OPF/spine is missing.
                entriesByName.keys
                    .filter { isHtmlPath(it) && !isSkippableEpubPath(it) }
                    .sorted()
            }

            for (path in documentPaths) {
                val entry = entriesByName[path]
                    ?: entriesByName.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value
                    ?: continue
                val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val soup = Jsoup.parse(html)
                if (isNavigationDocument(soup, path)) continue
                val chapter = chapterFromHtml(soup)
                val body = soup.body()?.text()?.trim().orEmpty().ifBlank {
                    soup.text().trim()
                }
                if (body.isBlank()) continue
                if (isMostlyTableOfContents(body)) continue
                for (segment in Chunker.splitTextIntoSegments(body)) {
                    if (segment.text.isBlank()) continue
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

    private fun findOpfPath(zip: ZipFile, names: Set<String>): String? {
        val container = names.firstOrNull {
            it.equals("META-INF/container.xml", ignoreCase = true)
        }
        if (container != null) {
            val entry = zip.getEntry(container) ?: zip.entries().asSequence().firstOrNull {
                it.name.equals(container, ignoreCase = true)
            }
            if (entry != null) {
                val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                val fullPath = doc.select("rootfile").firstOrNull()?.attr("full-path")?.trim().orEmpty()
                if (fullPath.isNotEmpty()) {
                    return normalizeZipPath(fullPath)
                }
            }
        }
        return names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }?.let(::normalizeZipPath)
    }

    private fun spineDocumentPaths(
        opf: org.jsoup.nodes.Document,
        opfPath: String,
        zipNames: Set<String>,
    ): List<String> {
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
        val manifest = mutableMapOf<String, ManifestItem>()
        for (item in opf.select("manifest > item")) {
            val id = item.attr("id").trim()
            val href = item.attr("href").trim()
            if (id.isEmpty() || href.isEmpty()) continue
            val properties = item.attr("properties")
            val mediaType = item.attr("media-type")
            val resolved = resolveOpfHref(opfDir, href)
            val match = matchZipPath(resolved, zipNames) ?: continue
            manifest[id] = ManifestItem(
                path = match,
                properties = properties,
                mediaType = mediaType,
            )
        }

        val ordered = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (itemRef in opf.select("spine > itemref")) {
            val idRef = itemRef.attr("idref").trim()
            val item = manifest[idRef] ?: continue
            if (!isHtmlPath(item.path)) continue
            if (item.isNav) continue
            if (isSkippableEpubPath(item.path)) continue
            if (seen.add(item.path)) {
                ordered += item.path
            }
        }
        if (ordered.isNotEmpty()) return ordered

        // Spine missing/unusable: use manifest HTML in declaration order.
        return manifest.values
            .asSequence()
            .filter { isHtmlPath(it.path) && !it.isNav && !isSkippableEpubPath(it.path) }
            .map { it.path }
            .distinct()
            .toList()
    }

    private data class ManifestItem(
        val path: String,
        val properties: String,
        val mediaType: String,
    ) {
        val isNav: Boolean
            get() = properties.split(Regex("""\s+""")).any { it.equals("nav", ignoreCase = true) } ||
                mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)
    }

    private fun resolveOpfHref(opfDir: String, href: String): String {
        val cleaned = href.substringBefore('#').substringBefore('?').trim().replace('\\', '/')
        val decoded = try {
            java.net.URLDecoder.decode(cleaned, Charsets.UTF_8.name())
        } catch (_: Exception) {
            cleaned
        }
        val joined = when {
            decoded.startsWith('/') -> decoded.trimStart('/')
            opfDir.isEmpty() -> decoded
            else -> "$opfDir/$decoded"
        }
        return normalizeZipPath(joined)
    }

    private fun normalizeZipPath(path: String): String {
        val parts = path.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." }
        val resolved = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            } else {
                resolved += part
            }
        }
        return resolved.joinToString("/")
    }

    private fun matchZipPath(path: String, zipNames: Set<String>): String? {
        if (path in zipNames) return path
        return zipNames.firstOrNull { it.equals(path, ignoreCase = true) }
    }

    private fun isHtmlPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.endsWith(".xhtml", true) ||
            name.endsWith(".html", true) ||
            name.endsWith(".htm", true)
    }

    private fun isSkippableEpubPath(path: String): Boolean {
        val name = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        return name == "nav" ||
            name == "toc" ||
            name == "tocnav" ||
            name == "ncx" ||
            name == "cover" ||
            name == "coverpage" ||
            name == "titlepage" ||
            name == "title-page" ||
            name == "copyright" ||
            name == "colophon" ||
            name.startsWith("toc_") ||
            name.startsWith("nav_")
    }

    private fun isNavigationDocument(soup: org.jsoup.nodes.Document, path: String): Boolean {
        if (isSkippableEpubPath(path)) return true
        val hasTocNav = soup.select("nav").any { nav ->
            val epubType = nav.attr("epub:type").ifBlank { nav.attr("type") }
            epubType.split(Regex("""\s+""")).any { it.equals("toc", ignoreCase = true) } ||
                nav.id().equals("toc", ignoreCase = true) ||
                nav.classNames().any { it.equals("toc", ignoreCase = true) }
        }
        if (!hasTocNav) return false
        val bodyText = soup.body()?.text()?.trim().orEmpty()
        // Standalone TOC/nav documents are skippable; mixed chapter pages are not.
        return bodyText.length < 2_000 || isMostlyTableOfContents(bodyText)
    }

    private fun isMostlyTableOfContents(body: String): Boolean {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 5) return false
        val headingLike = lines.count { TOC_CHAPTER_LINE.matches(it) || it.length < 60 }
        return headingLike.toFloat() / lines.size >= 0.8f && body.length < 4_000
    }

    private fun chapterFromHtml(soup: org.jsoup.nodes.Document): String {
        for (tag in listOf("h1", "h2", "h3", "title")) {
            val heading = soup.selectFirst(tag)?.text()?.trim().orEmpty()
            if (heading.isNotEmpty()) return heading
        }
        return ""
    }

    companion object {
        private val TOC_CHAPTER_LINE = Regex(
            """^(?:chapter|ch\.?|part|book|section)\s+[\divxlc]+\b.*$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
