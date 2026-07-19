# Document ingestion

`BookLoader` converts supported files into a common `BookDocument` representation containing text and available metadata such as title, author, ISBN, chapter, and page.

## TXT

TXT files are read as UTF-8 text. Structure is inferred from headings and text boundaries rather than a formal table of contents.

## PDF

PDFBox extracts text page by page. If a page has no useful extracted text, the page is rendered and passed to ML Kit Text Recognition. Page numbers are retained so retrieved passages can produce page citations.

OCR quality depends on scan resolution, language, page layout, and image quality. Password-protected, corrupt, or unusually encoded PDFs may fail to load.

## EPUB

EPUB files are ZIP containers. The loader reads package/OPF metadata, resolves content order, and uses Jsoup to turn HTML sections into text. Chapter and book metadata are retained when present.

## Chunking

`Chunker` prefers semantic boundaries in this order:

1. chapter headings
2. paragraphs
3. sentences
4. hard size boundaries when necessary

Defaults in `domain/Config.kt` use approximately 1,200-character chunks with 200-character overlap. Overlap preserves context around boundaries but increases embedding work and storage.

Each chunk retains source metadata used during citation formatting. The ingestion output is then passed to the indexing pipeline for embedding and persistence.

## Main implementation

- `domain/loader/BookLoader.kt`
- `domain/loader/BookDocument.kt`
- `domain/chunking/Chunker.kt`
- `domain/Config.kt`
