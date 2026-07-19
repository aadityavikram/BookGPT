# Chat agent

`BookAgent` coordinates retrieval, optional web fallback, conversation context, streaming answer generation, and persistence.

## Book scope

Chat can be focused on one selected book or operate across the library. When no explicit focus is selected, the agent can identify a referenced book before retrieval.

## Answer flow

1. Save the user message.
2. resolve the relevant book scope.
3. retrieve and rerank local passages.
4. use DuckDuckGo fallback when the requested content is not locally available.
5. assemble instructions, sources, recent messages, and any rolling summary.
6. stream the OpenAI response to the UI.
7. save the completed assistant message and source information.

## Conversation context

Conversations and messages persist in Room. By default, the most recent 12 messages remain verbatim. Older content can be condensed into a rolling summary to keep prompts bounded while preserving longer-term context.

The app supports multiple conversations, automatic title generation, conversation selection, rename, deletion, and clearing.

## Web fallback

`WebSearch` retrieves DuckDuckGo HTML results when local books do not contain the requested material. Search snippets are external, untrusted context and may be incomplete or inaccurate. Web fallback requires network access and sends the search query to DuckDuckGo.

## Streaming and errors

`ChatViewModel` exposes partial answer text as server-sent events arrive. Failed requests should leave a clear recoverable UI state; authentication, quota, rate-limit, timeout, server, and invalid-request failures are classified separately.

## Main implementation

- `domain/agent/BookAgent.kt`
- `domain/agent/ConversationContextAssembler.kt`
- `domain/agent/ChatSources.kt`
- `ui/chat/ChatViewModel.kt`
- `data/web/WebSearch.kt`
