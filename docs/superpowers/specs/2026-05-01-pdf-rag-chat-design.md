# Design: PDF-backed RAG for the LLM Chat UI

**Status:** Approved (brainstorming) — pending implementation plan
**Date:** 2026-05-01
**Scope:** AI Edge Gallery Android app (`/Users/amine/AI/gallery/Android/src`)

## Goal

Let the user attach a single PDF to the text-only LLM chat (`LlmChatScreen`) and have its content inform the model's answers via on-device retrieval-augmented generation (RAG). Provide a clearly visible toggle so the user can enable or disable RAG fetching at any time during a conversation.

## Non-goals

- Multi-document corpus / document library management
- Cloud APIs, remote embeddings, or any network usage for retrieval
- Adding a new ML model (no embedding model)
- OCR / scanned-PDF support
- Wiring RAG into `LlmAskImageScreen`, `LlmAskAudioScreen`, or `AgentChatScreen`
- Showing a "sources" panel in the chat UI (retrieval is invisible to the user)

## High-level approach

- **Retrieval:** Pure-Kotlin BM25 over text chunks. No embeddings, no extra models.
- **Ingestion:** PdfBox-Android extracts text per page → `Chunker` produces overlapping ~500-token chunks tagged with page numbers → `Bm25Index` builds term/doc-frequency tables.
- **Persistence:** A single `RagIndexData` is serialized to `filesDir/rag/active_index.json` (kotlinx.serialization). One PDF at a time; ingesting a new PDF replaces the previous index. The toggle state is a new boolean in the `Settings` proto.
- **Augmentation:** When RAG is enabled and an index exists, `LlmChatViewModel.generateResponse` prepends a formatted `Context:` block of the top-K (K=4) chunks to the user's input before calling `runInference`. The chat history shows only the original user input.
- **UI:** Two additions to the chat input bar in `MessageInputText.kt` (gated to `LlmChatScreen` only): an "Attach PDF" icon button and a RAG toggle. A small chip shows the active document and a clear button.

## Architecture

A new package `app/src/main/java/com/google/ai/edge/gallery/data/rag/` exposes `RagRepository` (provided as a `@Singleton` from `AppModule`). The repository owns three pure-data collaborators and the persistence layer.

```
LlmChatScreen ── ChatViewWrapper ──┬─> LlmChatViewModel.generateResponse
                                   │       └─> RagRepository.retrieve / formatContext
                                   │              └─> Bm25Index.search (in-memory)
                                   └─> LlmChatViewModel.ingestPdf / setRagEnabled
                                           ├─> RagRepository.ingestPdf
                                           │       ├─> PdfTextExtractor (PdfBox)
                                           │       ├─> Chunker
                                           │       ├─> Bm25Index.build
                                           │       └─> persist active_index.json
                                           └─> DataStoreRepository.setRagEnabled
```

The chat layer touches RAG in only two places: the input bar (UI for toggle + attach + chip) and `generateResponse` (one branch at the top that may rewrite the prompt). Nothing else in the LiteRT-LM path, model loading, or chat data model changes.

## Components

### Data types (in `data/rag/`)

```kotlin
@Serializable
data class RagChunk(
  val id: Int,
  val text: String,
  val pageStart: Int,   // 1-indexed
  val pageEnd: Int,
  val tokens: List<String>
)

@Serializable
data class RagIndexData(
  val docName: String,
  val createdAtMs: Long,
  val chunks: List<RagChunk>,
  val docFreq: Map<String, Int>,
  val avgChunkLen: Double
)

data class ScoredChunk(val chunk: RagChunk, val score: Double)

sealed class RagState {
  object Empty : RagState()
  data class Indexing(val progress: Float) : RagState()
  data class Ready(val docName: String, val chunkCount: Int) : RagState()
  data class Error(val error: RagError) : RagState()
}
```

### `PdfTextExtractor` (interface + `PdfBoxTextExtractor` impl)

- `suspend fun extract(uri: Uri): List<PageText>` where `PageText(page: Int, text: String)`.
- Runs on `Dispatchers.IO`. Initializes `PDFBoxResourceLoader` once at app start (or lazily on first use).

### `Chunker` (object)

- `fun chunk(pages: List<PageText>, targetTokens: Int = 500, overlapTokens: Int = 50): List<RagChunk>`
- Tokenization: lowercase → split on `[^\p{L}\p{N}]+` → drop a built-in English stopword set (~40 words) and tokens of length 1.
- Each chunk records the page range it spans (`pageStart`..`pageEnd`).

### `Bm25Index` (object)

- `fun build(chunks: List<RagChunk>): RagIndexData`
- `fun search(index: RagIndexData, query: String, k: Int = 4): List<ScoredChunk>`
- Standard BM25 with `k1 = 1.5`, `b = 0.75`. Query tokenized with the same rules as ingestion.

### `RagRepository` (interface + `DefaultRagRepository`, `@Singleton`)

```kotlin
interface RagRepository {
  val state: StateFlow<RagState>
  suspend fun ingestPdf(uri: Uri): Result<Unit>
  suspend fun clear()
  suspend fun retrieve(query: String, k: Int = 4): List<ScoredChunk>
  fun formatContext(chunks: List<ScoredChunk>): String
}
```

- On construction: probe `filesDir/rag/active_index.json`. If present and parseable, emit `Ready(docName, chunkCount)` with metadata only; defer loading the full chunk list into memory until the first `retrieve` call.
- Holds the live index in `@Volatile var current: RagIndexData?` so reads remain lock-free; writes (ingest, clear) are serialized by a `Mutex`.
- `formatContext` produces:
  ```
  Use the following context from "<docName>" to answer the question. If the context does not contain the answer, say so.

  [page 3] <chunk text>

  [page 7] <chunk text>

  Question:
  ```

### Settings proto change

`app/src/main/proto/settings.proto` gains a new field:

```proto
bool rag_enabled = N;   // N = next available field number
```

`DataStoreRepository` gains:
- `fun ragEnabledFlow(): Flow<Boolean>`
- `suspend fun setRagEnabled(value: Boolean)` (or non-suspend, matching the existing convention in `DataStoreRepository` which currently uses `runBlocking`)

### Hilt module

`AppModule` adds:
```kotlin
@Provides @Singleton
fun providePdfTextExtractor(@ApplicationContext ctx: Context): PdfTextExtractor =
  PdfBoxTextExtractor(ctx)

@Provides @Singleton
fun provideRagRepository(
  @ApplicationContext ctx: Context,
  extractor: PdfTextExtractor
): RagRepository = DefaultRagRepository(ctx, extractor)
```

### View model changes

`LlmChatViewModelBase` gets `ragRepository` and `dataStoreRepository` injected (the latter is likely already available). It exposes:
- `val ragState: StateFlow<RagState>` (forwarded from the repository)
- `val ragEnabled: StateFlow<Boolean>` (mapped from `dataStoreRepository.ragEnabledFlow()`)
- `fun ingestPdf(uri: Uri)` (launches in `viewModelScope`)
- `fun clearRag()`
- `fun setRagEnabled(value: Boolean)`

`generateResponse` gains a single new branch at the top: if `ragEnabled.value && ragState.value is Ready`, compute `prefixed = ragRepository.formatContext(ragRepository.retrieve(input)) + input` and pass `prefixed` to `runInference`. The `ChatMessageText` added to chat history continues to use the original `input`.

Only the text-only `LlmChatViewModel` subclass uses these features. The image/audio subclasses do not opt in for v1.

### UI changes

`MessageInputText.kt` gets two new optional flags threaded from `LlmChatScreen` → `ChatViewWrapper` → `MessageInputText`:
- `showPdfPicker: Boolean = false` — when true, render an "Attach PDF" icon next to the existing image/audio attach buttons. Tapping launches `ActivityResultContracts.OpenDocument(arrayOf("application/pdf"))`.
- `showRagToggle: Boolean = false` — when true, render a small toggle (icon button or filter chip) reflecting `ragEnabled`. Tapping flips it.

A small chip above the input bar shows the active document when `RagState.Ready`: `📄 mydoc.pdf (142 chunks) ✕`. Tapping ✕ calls `clearRag()`.

When the toggle is on and `RagState` is anything other than `Ready`, the chip shows a hint: "Attach a PDF to use RAG." This is the same control surface, no separate UI element.

Only `LlmChatScreen` passes `showPdfPicker = true` and `showRagToggle = true`. Other screens are unchanged.

## Data flow

### Flow A — Ingesting a PDF

1. User taps "Attach PDF" in the input bar.
2. `OpenDocument` returns a `Uri`.
3. `ChatViewWrapper` calls `viewModel.ingestPdf(uri)` → `ragRepository.ingestPdf(uri)`.
4. Repository (on `Dispatchers.IO`, under `Mutex`):
   - emit `Indexing(0f)`
   - extract pages via `PdfTextExtractor` (progress 0.0 → 0.4)
   - `Chunker.chunk(pages)` (0.4 → 0.6)
   - `Bm25Index.build(chunks)` (0.6 → 0.9)
   - serialize to `filesDir/rag/active_index.json.tmp`, then atomic rename to `active_index.json` (0.9 → 1.0)
   - update in-memory `current`; emit `Ready(docName, chunkCount)`
5. Input bar observes `state` and updates the chip.

### Flow B — Sending a message with RAG enabled

1. User types question, taps send.
2. `LlmChatViewModel.generateResponse(model, input, ...)` runs.
3. New branch: if `ragEnabled.value && ragState.value is Ready`:
   - `val scored = ragRepository.retrieve(input, k = 4)`
   - `val prefixed = ragRepository.formatContext(scored) + input`
   - call `runInference` with `prefixed`.
4. The `ChatMessageText` placed in the chat history uses the **original** `input`.
5. Streaming, thinking channel, and error handling are unchanged.

### Flow C — Toggling RAG

1. User taps the toggle.
2. `viewModel.setRagEnabled(!current)` → `dataStoreRepository.setRagEnabled(...)`.
3. The toggle UI is bound to a `StateFlow<Boolean>` mapped over the DataStore flow, so it reflects the change instantly.
4. Toggle on with no index: send-time logic skips RAG injection silently; chip shows the "Attach a PDF" hint.

## Error handling

```kotlin
sealed class RagError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class FileOpenFailed(cause: Throwable) : RagError("Could not open the selected file.", cause)
  class NotAPdf : RagError("The selected file is not a valid PDF.")
  class NoTextExtracted : RagError("No text could be extracted from this PDF (it may be a scanned image).")
  class TooLarge(pages: Int) : RagError("This PDF is too large ($pages pages). Please choose one under 500 pages.")
  class IndexBuildFailed(cause: Throwable) : RagError("Failed to build the search index.", cause)
  class PersistenceFailed(cause: Throwable) : RagError("Failed to save the index to storage.", cause)
  class CorruptIndex(cause: Throwable) : RagError("Saved index is corrupt and was discarded.", cause)
}
```

| Source | Error | UI handling |
|---|---|---|
| `contentResolver.openInputStream` returns null or throws | `FileOpenFailed` | Snackbar in chat screen |
| PdfBox fails to parse | `NotAPdf` | Snackbar; previous state preserved |
| All extracted pages are empty | `NoTextExtracted` | Snackbar; suggest a non-scanned PDF |
| `pages.size > 500` | `TooLarge(pageCount)` | Snackbar; ingestion aborted |
| Chunker/BM25 throws | `IndexBuildFailed` | Snackbar; log |
| File write fails | `PersistenceFailed` | Snackbar; in-memory index still works for current session |
| JSON load fails on startup | `CorruptIndex` | Silently delete file, log, state `Empty` |

**Recovery:**
- `ingestPdf` failure preserves the previous `Ready` state and the existing on-disk index (atomic rename means `active_index.json` is never half-written).
- A retrieval failure inside `generateResponse` is caught, logged, and the message is sent without RAG context. The user gets an answer; we never break the chat.
- Toggle on with no index is not an error — just a silent no-op with a UI hint.

**Concurrency:**
- `ingestPdf` is guarded by a `Mutex`; the UI disables the "Attach PDF" button while `state is Indexing`.
- `retrieve` reads `@Volatile var current: RagIndexData?` and operates on an immutable value — safe to call concurrently.

**Logging:** Use the existing app logging convention (matching `LlmChatModelHelper`'s tag style). Log filenames, chunk counts, page counts, error types. Never log PDF content or query strings.

## Testing

Mirror the existing layout: JVM tests in `app/src/test/...`, instrumentation tests in `app/src/androidTest/...`. Match whatever assertion library the existing tests use (likely JUnit4 + kotlin.test or Truth).

### JVM unit tests (the bulk of coverage)

| Component | Tests |
|---|---|
| `Chunker` | empty pages → empty list; single short page → one chunk; long page → multiple chunks with ~50-token overlap; multi-page text → correct page ranges; tokenization strips stopwords/punctuation; very long single token doesn't crash |
| `Bm25Index.build` | term/doc frequencies match a hand-computed example on a 3-chunk corpus; `avgChunkLen` correct; empty input → empty index |
| `Bm25Index.search` | hand-crafted 5-chunk corpus returns expected top-K in expected order; all-stopwords query → empty/low-score results; k larger than corpus → returns all chunks; identical chunks → stable ordering |
| `RagRepository.formatContext` | output contains doc name, all chunks with `[page N]` prefix, ends with `Question:`; empty input → empty string |
| `RagError` mapping | each error type produces the expected user-facing message |

### Repository tests with fakes (JVM)

`DefaultRagRepositoryTest` with a fake `PdfTextExtractor` and a temp directory:
- `ingestPdf` happy path → state Empty → Indexing → Ready, file written, retrieve returns sensible top-K
- empty extracted text → `NoTextExtracted`, state stays `Empty`, no file written
- over-page-limit → `TooLarge`, state unchanged
- failure mid-build → previous `Ready` index preserved (atomic rename verified)
- restart simulation: pre-write a valid index file, construct a fresh repository → state `Ready` with correct metadata; first `retrieve` lazy-loads chunks
- restart with corrupt JSON → file deleted, state `Empty`, no crash
- concurrent `ingestPdf` calls → mutex serializes them
- `clear()` → file deleted, state `Empty`, in-memory cache nulled

### ViewModel tests (JVM)

`LlmChatViewModelRagTest`:
- RAG disabled → `runInference` called with original input
- RAG enabled, no index → `runInference` called with original input (silent skip)
- RAG enabled, index ready → `runInference` called with prefixed input; chat history `ChatMessageText` still contains original input
- Retrieval throws → `runInference` called with original input, error logged, no crash

### Instrumentation test (`androidTest`)

One end-to-end PdfBox test using a small bundled fixture PDF in `app/src/androidTest/assets/`:
- `PdfBoxTextExtractor.extract(uri)` returns expected page count and text per page

UI tests for the input bar additions are skipped for v1; behavior is covered by the ViewModel test.

## Dependencies

Add to `gradle/libs.versions.toml` and `app/build.gradle.kts`:

- `com.tom-roush:pdfbox-android:2.0.27.0` (or current stable)
- `org.jetbrains.kotlinx:kotlinx-serialization-json` (verify whether already present; likely yes)

## Files touched

**New:**
- `app/src/main/java/com/google/ai/edge/gallery/data/rag/PdfTextExtractor.kt` (interface + `PdfBoxTextExtractor`)
- `app/src/main/java/com/google/ai/edge/gallery/data/rag/Chunker.kt`
- `app/src/main/java/com/google/ai/edge/gallery/data/rag/Bm25Index.kt`
- `app/src/main/java/com/google/ai/edge/gallery/data/rag/RagRepository.kt` (interface + `DefaultRagRepository`)
- `app/src/main/java/com/google/ai/edge/gallery/data/rag/RagModels.kt` (`RagChunk`, `RagIndexData`, `ScoredChunk`, `RagState`, `RagError`)
- `app/src/test/.../data/rag/*Test.kt` (one per component)
- `app/src/androidTest/.../data/rag/PdfBoxTextExtractorTest.kt`
- `app/src/androidTest/assets/sample.pdf` (small fixture)

**Modified:**
- `app/src/main/proto/settings.proto` — add `rag_enabled`
- `app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt` — add accessors
- `app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt` — provide repository + extractor
- `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt` — inject deps, expose RAG state, augment `generateResponse`
- `app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt` — pass `showPdfPicker`/`showRagToggle` flags, wire callbacks, render chip
- `app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt` — add the two new optional UI affordances
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — PdfBox dependency

## Open implementation questions (deferred to plan)

- Exact field number for `rag_enabled` in `Settings` proto (depends on existing fields).
- Whether to load the full index lazily on first `retrieve` or eagerly on app start. Lean lazy for fast cold-start; revisit if first-query latency is poor.
- Exact icon choice for the RAG toggle and "Attach PDF" button (Material Icons).
- Whether the page-count cap (500) needs to be configurable; v1 is hard-coded.
