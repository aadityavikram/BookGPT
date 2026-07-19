# Retrieval and RAG

BookGPT uses retrieval-augmented generation (RAG) to ground answers in imported books.

## Retrieval pipeline

1. Generate an OpenAI embedding for the user's question.
2. Restrict retrieval to the focused book when one is selected.
3. Read stored quantized vectors in pages.
4. Compute local cosine similarity against the query.
5. Keep up to 20 candidate chunks.
6. Ask the configured chat model to rerank candidates.
7. Select up to six passages by default.
8. Format passages and metadata as answer context and citations.

Vector scanning is local; the query embedding and reranking request require OpenAI. Paging prevents all vectors from being loaded into memory at once.

## Quantized vectors

Indexing converts floating-point embeddings to int8 vectors with associated scale information. This trades a small amount of ranking precision for much lower storage and memory use. `VectorQuantizerTest` verifies both approximation quality and size.

Embeddings from different models or dimensions must not be mixed. A model change therefore invalidates the existing index.

## Reranking

Cosine similarity supplies broad candidates. `Reranker` uses the language model to improve semantic ordering before context is sent to the answer-generation request. The retrieval limits in `domain/Config.kt` control candidate and final passage counts.

## Sources

Chunks preserve book, chapter, and page information where available. `ChatSources` turns this metadata into displayed citations. Generated answers can still be wrong; citations should be treated as traceability aids, not proof that every statement is supported.

## Main implementation

- `domain/retrieve/BookRetriever.kt`
- `domain/retrieve/Reranker.kt`
- `domain/retrieve/Cosine.kt`
- `domain/retrieve/VectorQuantizer.kt`
- `domain/agent/ChatSources.kt`
- `data/db/Daos.kt`
