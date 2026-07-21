# Chat agent

`BookAgent` coordinates scoped library retrieval, conversation context, streaming answer generation, and persistence.

## Book scope

Chat can be focused on one selected book or operate across the library. The focus selector is authoritative:

- selecting a book restricts retrieval and citations to that book
- selecting **All books** searches all indexed books in the library

The text of a question does not change the selected focus. If no relevant passage is found, focused-book retrieval does not fall back to other books.

## Answer flow

1. Save the user message.
2. Read the scope selected in the focus control.
3. retrieve and rerank local passages.
4. If no passage is found, return a scoped not-found response without requesting an answer.
5. Assemble strict grounding instructions, library sources, recent messages, and any rolling summary.
6. Stream the OpenAI response to the UI.
7. Save the completed assistant message and library source information.

## Conversation context

Conversations and messages persist in Room. By default, the most recent 12 messages remain verbatim. Older content can be condensed into a rolling summary to keep prompts bounded while preserving longer-term context.

The app supports multiple conversations, automatic title generation, conversation selection, rename, deletion, and clearing.

## Library-only grounding

Answers use only retrieved passages from the selected library scope. BookGPT does not call a web search service, use web results, or fall back from a focused book to the rest of the library. The system prompt also instructs the answer model not to use general knowledge or unsupported conversation claims as evidence.

This is library-only grounding, not fully offline operation. OpenAI network access is still used for embeddings, reranking, answer generation, conversation summaries, and generated conversation titles.

## Streaming and errors

`ChatViewModel` exposes partial answer text as server-sent events arrive. Failed requests should leave a clear recoverable UI state; authentication, quota, rate-limit, timeout, server, and invalid-request failures are classified separately.

## Main implementation

- `domain/agent/BookAgent.kt`
- `domain/agent/ConversationContextAssembler.kt`
- `domain/agent/ChatSources.kt`
- `ui/chat/ChatViewModel.kt`
