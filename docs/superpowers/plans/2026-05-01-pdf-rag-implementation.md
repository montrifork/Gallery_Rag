# PDF-backed RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a PDF-backed retrieval-augmented generation feature to the text-only LLM chat (`LlmChatScreen`), with an inline toggle to enable/disable RAG fetching.

**Architecture:** A new `data/rag/` package owned by `RagRepository` (Hilt singleton). PdfBox-Android extracts text; a pure-Kotlin `Chunker` produces overlapping ~500-token chunks tagged with page numbers; a pure-Kotlin BM25 index ranks chunks per query. The active index is persisted as a JSON file at `filesDir/rag/active_index.json`. When RAG is enabled and an index exists, `LlmChatViewModel.generateResponse` prepends a formatted `Context:` block (top-K=4 chunks) to the user's input before calling `runInference`. The chat history shows only the original input.

**Tech Stack:** Kotlin 2.2.0, Android (compileSdk 35), Jetpack Compose + Material3, Hilt, Proto DataStore, kotlinx.serialization, PdfBox-Android (`com.tom-roush:pdfbox-android:2.0.27.0`), LiteRT-LM (already integrated).

**Non-goals (per design spec):**
- Multi-document corpus, cloud APIs, embedding models, OCR, sources panel UI
- Wiring RAG into `LlmAskImageScreen` / `LlmAskAudioScreen` / `AgentChatScreen`
- **JVM unit tests are skipped for v1** (per user decision; project has no existing JVM test source set). Verification is manual via the running app.

**Reference design spec:** `docs/superpowers/specs/2026-05-01-pdf-rag-chat-design.md`

---

## File Structure

**New files (all under `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/`):**
- `RagModels.kt` — data classes (`RagChunk`, `RagIndexData`, `ScoredChunk`), `RagState` sealed class, `RagError` sealed class
- `Tokenizer.kt` — pure tokenization helpers + stopword set
- `Chunker.kt` — page-text → list of `RagChunk`
- `Bm25Index.kt` — `build()` and `search()` over `RagIndexData`
- `PdfTextExtractor.kt` — interface + `PdfBoxTextExtractor` impl
- `RagRepository.kt` — interface + `DefaultRagRepository`
- `ContextFormatter.kt` — pure helper that turns `List<ScoredChunk>` into the prompt prefix string

**Modified files:**
- `Android/src/app/src/main/proto/settings.proto` — add `rag_enabled` field
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt` — add accessors
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt` — provide extractor + repository
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt` — inject deps, expose RAG state, augment `generateResponse`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt` — pass RAG flags + callbacks through `ChatViewWrapper`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt` — thread new RAG params through to `MessageInputText`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt` — render Attach-PDF button, RAG toggle, active-doc chip
- `Android/src/gradle/libs.versions.toml` — add PdfBox version + library entry
- `Android/src/app/build.gradle.kts` — add PdfBox dependency
- `Android/src/app/src/main/AndroidManifest.xml` — only if a permission or provider is needed (it isn't for SAF `OpenDocument`; we'll verify in Task 12)

**Working directory for all bash commands:** `/Users/amine/AI/gallery/Android/src` unless noted.

**Branch:** `feat/pdf-rag` (already created and checked out).

---

## Task 1: Add PdfBox-Android dependency

**Files:**
- Modify: `Android/src/gradle/libs.versions.toml`
- Modify: `Android/src/app/build.gradle.kts`

- [ ] **Step 1: Add PdfBox version + library entry**

Edit `Android/src/gradle/libs.versions.toml`:

In the `[versions]` block, add (alphabetical position is fine; place after `mlkit-genai-prompt`):
```toml
pdfboxAndroid = "2.0.27.0"
```

In the `[libraries]` block, add (place near `moshi-kotlin`):
```toml
pdfbox-android = { group = "com.tom-roush", name = "pdfbox-android", version.ref = "pdfboxAndroid" }
```

- [ ] **Step 2: Add PdfBox to app build**

Edit `Android/src/app/build.gradle.kts`. In the `dependencies { ... }` block, add this line right after `implementation(libs.mlkit.genai.prompt)` (line ~123):
```kotlin
  implementation(libs.pdfbox.android)
```

- [ ] **Step 3: Sync / verify the build resolves**

Run from `/Users/amine/AI/gallery/Android/src`:
```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -i pdfbox
```
Expected: a line like `+--- com.tom-roush:pdfbox-android:2.0.27.0`. If it errors, the artifact may have been renamed; check Maven Central for the latest `com.tom-roush:pdfbox-android` and update both files.

- [ ] **Step 4: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/gradle/libs.versions.toml Android/src/app/build.gradle.kts
git commit -m "Add PdfBox-Android dependency for PDF text extraction"
```

---

## Task 2: Add `rag_enabled` field to the Settings proto

**Files:**
- Modify: `Android/src/app/src/main/proto/settings.proto`

- [ ] **Step 1: Add the field**

In `Android/src/app/src/main/proto/settings.proto`, modify the `Settings` message to add a new field. Existing fields use 1–10 (with 9 currently inside `copybara:strip_begin/end` markers — preserve those markers). Add field 11 just before the closing `}` of the `Settings` message (after line 80 `repeated string viewed_promo_id = 10;`):

```proto
  // Whether RAG (PDF-backed retrieval) is enabled in the LLM chat.
  bool rag_enabled = 11;
```

- [ ] **Step 2: Verify proto compiles**

```bash
./gradlew :app:generateDebugProto
```
Expected: BUILD SUCCESSFUL. Generated Java should now contain `getRagEnabled()`/`setRagEnabled(boolean)` on `Settings.Builder`. If the build fails, double-check no existing field uses `11`.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/proto/settings.proto
git commit -m "Add rag_enabled flag to Settings proto"
```

---

## Task 3: Expose `ragEnabled` accessors on `DataStoreRepository`

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt`

- [ ] **Step 1: Add interface methods**

In `DataStoreRepository.kt`, inside the `interface DataStoreRepository { ... }` block, add (place after `hasViewedPromo` near line 111, before the closing `}`):

```kotlin
  /** Returns whether RAG (PDF retrieval) is enabled in the LLM chat. */
  fun getRagEnabled(): Boolean

  /** Sets whether RAG (PDF retrieval) is enabled in the LLM chat. */
  fun setRagEnabled(enabled: Boolean)

  /** Flow of the current rag_enabled value. */
  fun ragEnabledFlow(): kotlinx.coroutines.flow.Flow<Boolean>
```

- [ ] **Step 2: Add implementations**

In the same file, inside `class DefaultDataStoreRepository : DataStoreRepository { ... }`, add at the end of the class (just before the closing `}` near line 436):

```kotlin
  override fun getRagEnabled(): Boolean {
    return runBlocking {
      val settings = dataStore.data.first()
      settings.ragEnabled
    }
  }

  override fun setRagEnabled(enabled: Boolean) {
    runBlocking {
      dataStore.updateData { settings -> settings.toBuilder().setRagEnabled(enabled).build() }
    }
  }

  override fun ragEnabledFlow(): kotlinx.coroutines.flow.Flow<Boolean> {
    return kotlinx.coroutines.flow.map(dataStore.data) { it.ragEnabled }
  }
```

(Note: prefer existing `import kotlinx.coroutines.flow.first` style — add `import kotlinx.coroutines.flow.Flow` and `import kotlinx.coroutines.flow.map` at the top of the file, then drop the fully-qualified names from the signatures above.)

- [ ] **Step 3: Build to verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DataStoreRepository.kt
git commit -m "Add rag_enabled accessors to DataStoreRepository"
```

---

## Task 4: Create RAG data models, state, and errors

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/RagModels.kt`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag
```

- [ ] **Step 2: Write `RagModels.kt`**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/RagModels.kt`:

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

import kotlinx.serialization.Serializable

/** A single text chunk extracted from a PDF, with its page span and pre-tokenized terms. */
@Serializable
data class RagChunk(
  val id: Int,
  val text: String,
  val pageStart: Int,
  val pageEnd: Int,
  val tokens: List<String>,
)

/** Persisted RAG index for the active document. */
@Serializable
data class RagIndexData(
  val docName: String,
  val createdAtMs: Long,
  val chunks: List<RagChunk>,
  /** term -> number of chunks containing the term */
  val docFreq: Map<String, Int>,
  val avgChunkLen: Double,
)

/** A chunk plus its BM25 score for a given query. */
data class ScoredChunk(val chunk: RagChunk, val score: Double)

/** A page of raw text extracted from a PDF (1-indexed page number). */
data class PageText(val page: Int, val text: String)

/** Public state of the RAG repository. */
sealed class RagState {
  object Empty : RagState()
  data class Indexing(val progress: Float) : RagState()
  data class Ready(val docName: String, val chunkCount: Int) : RagState()
  data class Error(val error: RagError) : RagState()
}

/** Typed errors surfaced by the RAG repository. */
sealed class RagError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class FileOpenFailed(cause: Throwable) :
    RagError("Could not open the selected file.", cause)

  class NotAPdf(cause: Throwable? = null) :
    RagError("The selected file is not a valid PDF.", cause)

  class NoTextExtracted :
    RagError("No text could be extracted from this PDF (it may be a scanned image).")

  class TooLarge(val pages: Int) :
    RagError("This PDF is too large ($pages pages). Please choose one under 500 pages.")

  class IndexBuildFailed(cause: Throwable) :
    RagError("Failed to build the search index.", cause)

  class PersistenceFailed(cause: Throwable) :
    RagError("Failed to save the index to storage.", cause)

  class CorruptIndex(cause: Throwable) :
    RagError("Saved index is corrupt and was discarded.", cause)
}
```

- [ ] **Step 3: Build to verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/RagModels.kt
git commit -m "Add RAG data models, state, and error types"
```

---

## Task 5: Tokenizer

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/Tokenizer.kt`

- [ ] **Step 1: Write `Tokenizer.kt`**

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

/** Tokenization helpers shared by ingestion and querying. */
object Tokenizer {
  /** A small built-in English stopword set. */
  val STOPWORDS: Set<String> = setOf(
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from",
    "has", "have", "he", "her", "him", "his", "i", "in", "is", "it", "its",
    "of", "on", "or", "she", "so", "that", "the", "their", "them", "they",
    "this", "to", "was", "we", "were", "will", "with", "you", "your",
  )

  private val SPLIT_REGEX = Regex("[^\\p{L}\\p{N}]+")

  /**
   * Lowercases, splits on non-letter/non-digit, drops stopwords and tokens of length 1.
   * Pure function, safe to call from any thread.
   */
  fun tokenize(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return SPLIT_REGEX.split(text.lowercase())
      .asSequence()
      .filter { it.length > 1 && it !in STOPWORDS }
      .toList()
  }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/Tokenizer.kt
git commit -m "Add Tokenizer with stopword filtering for RAG"
```

---

## Task 6: Chunker

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/Chunker.kt`

- [ ] **Step 1: Write `Chunker.kt`**

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

/**
 * Splits per-page text into overlapping chunks of approximately [targetTokens] tokens with
 * [overlapTokens] overlap. Each chunk records the page range it spans.
 *
 * "Tokens" here are the ones produced by [Tokenizer.tokenize] (lowercased, stopwords removed).
 * The chunk's `text` field preserves the original (un-lowercased, un-filtered) substring so it
 * can be shown back to the LLM verbatim. Token boundaries are computed in raw whitespace-split
 * units so we can round-trip back to the original text.
 */
object Chunker {

  fun chunk(
    pages: List<PageText>,
    targetTokens: Int = 500,
    overlapTokens: Int = 50,
  ): List<RagChunk> {
    require(targetTokens > 0) { "targetTokens must be > 0" }
    require(overlapTokens in 0 until targetTokens) {
      "overlapTokens must be in [0, targetTokens)"
    }
    if (pages.isEmpty()) return emptyList()

    // Build a flat sequence of (rawWord, pageNumber) so we can reconstruct chunk text and
    // determine which page(s) each chunk spans.
    data class W(val raw: String, val page: Int)
    val words = mutableListOf<W>()
    for (p in pages) {
      // Split on whitespace; keep raw casing/punctuation for the chunk text.
      for (w in p.text.split(Regex("\\s+"))) {
        if (w.isNotEmpty()) words.add(W(w, p.page))
      }
    }
    if (words.isEmpty()) return emptyList()

    val out = mutableListOf<RagChunk>()
    val step = targetTokens - overlapTokens
    var start = 0
    var id = 0
    while (start < words.size) {
      val end = minOf(start + targetTokens, words.size)
      val slice = words.subList(start, end)
      val rawText = slice.joinToString(" ") { it.raw }
      val tokens = Tokenizer.tokenize(rawText)
      out.add(
        RagChunk(
          id = id++,
          text = rawText,
          pageStart = slice.first().page,
          pageEnd = slice.last().page,
          tokens = tokens,
        )
      )
      if (end == words.size) break
      start += step
    }
    return out
  }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/Chunker.kt
git commit -m "Add Chunker for splitting PDF text into overlapping windows"
```

---

## Task 7: BM25 index

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/Bm25Index.kt`

- [ ] **Step 1: Write `Bm25Index.kt`**

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

import kotlin.math.ln

/** Pure-Kotlin BM25 over a list of [RagChunk]. */
object Bm25Index {

  private const val K1 = 1.5
  private const val B = 0.75

  /** Builds the index data (doc-frequency table + average length). Pure function. */
  fun build(chunks: List<RagChunk>, docName: String, createdAtMs: Long): RagIndexData {
    if (chunks.isEmpty()) {
      return RagIndexData(
        docName = docName,
        createdAtMs = createdAtMs,
        chunks = chunks,
        docFreq = emptyMap(),
        avgChunkLen = 0.0,
      )
    }
    val df = HashMap<String, Int>()
    var totalLen = 0L
    for (c in chunks) {
      totalLen += c.tokens.size
      val seen = HashSet<String>()
      for (t in c.tokens) if (seen.add(t)) df.merge(t, 1, Int::plus)
    }
    return RagIndexData(
      docName = docName,
      createdAtMs = createdAtMs,
      chunks = chunks,
      docFreq = df,
      avgChunkLen = totalLen.toDouble() / chunks.size,
    )
  }

  /** Returns the top-[k] scored chunks for [query]. Stable order on ties (by chunk id ASC). */
  fun search(index: RagIndexData, query: String, k: Int = 4): List<ScoredChunk> {
    if (index.chunks.isEmpty() || k <= 0) return emptyList()
    val qTokens = Tokenizer.tokenize(query).distinct()
    if (qTokens.isEmpty()) return emptyList()

    val n = index.chunks.size.toDouble()
    // Precompute IDF per query term: ln( (N - df + 0.5) / (df + 0.5) + 1 )  (BM25+ smoothing)
    val idf = HashMap<String, Double>(qTokens.size)
    for (t in qTokens) {
      val df = index.docFreq[t] ?: 0
      idf[t] = ln((n - df + 0.5) / (df + 0.5) + 1.0)
    }

    val scored = ArrayList<ScoredChunk>(index.chunks.size)
    for (chunk in index.chunks) {
      if (chunk.tokens.isEmpty()) continue
      val tf = HashMap<String, Int>()
      for (t in chunk.tokens) tf.merge(t, 1, Int::plus)
      val len = chunk.tokens.size.toDouble()
      val lenNorm = 1.0 - B + B * (len / index.avgChunkLen)
      var score = 0.0
      for (t in qTokens) {
        val f = tf[t] ?: continue
        val termIdf = idf[t] ?: continue
        score += termIdf * (f * (K1 + 1.0)) / (f + K1 * lenNorm)
      }
      if (score > 0.0) scored.add(ScoredChunk(chunk, score))
    }
    return scored
      .sortedWith(compareByDescending<ScoredChunk> { it.score }.thenBy { it.chunk.id })
      .take(k)
  }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/Bm25Index.kt
git commit -m "Add BM25 index for RAG chunk retrieval"
```

---

## Task 8: Context formatter

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/ContextFormatter.kt`

- [ ] **Step 1: Write `ContextFormatter.kt`**

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

/** Builds the prompt prefix that wraps retrieved chunks for the LLM. */
object ContextFormatter {

  /**
   * Produces a prefix that instructs the model to use the provided context. Empty list returns
   * empty string (caller should skip RAG augmentation in that case).
   */
  fun format(docName: String, chunks: List<ScoredChunk>): String {
    if (chunks.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("Use the following context from \"")
    sb.append(docName)
    sb.append("\" to answer the question. ")
    sb.append("If the context does not contain the answer, say so.\n\n")
    for (sc in chunks) {
      val pageLabel =
        if (sc.chunk.pageStart == sc.chunk.pageEnd) "page ${sc.chunk.pageStart}"
        else "pages ${sc.chunk.pageStart}-${sc.chunk.pageEnd}"
      sb.append("[").append(pageLabel).append("] ").append(sc.chunk.text).append("\n\n")
    }
    sb.append("Question:\n")
    return sb.toString()
  }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/ContextFormatter.kt
git commit -m "Add ContextFormatter for RAG prompt prefixing"
```

---

## Task 9: PDF text extractor

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/PdfTextExtractor.kt`

- [ ] **Step 1: Write `PdfTextExtractor.kt`**

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts page-by-page text from a PDF Uri. */
interface PdfTextExtractor {
  /**
   * @throws RagError.FileOpenFailed if the Uri cannot be opened.
   * @throws RagError.NotAPdf if PdfBox cannot parse the file.
   */
  suspend fun extract(uri: Uri): List<PageText>
}

class PdfBoxTextExtractor(private val context: Context) : PdfTextExtractor {

  init {
    // Safe to call multiple times; PdfBox guards against re-init.
    PDFBoxResourceLoader.init(context.applicationContext)
  }

  override suspend fun extract(uri: Uri): List<PageText> = withContext(Dispatchers.IO) {
    val input = try {
      context.contentResolver.openInputStream(uri)
        ?: throw RagError.FileOpenFailed(IllegalStateException("openInputStream returned null"))
    } catch (e: SecurityException) {
      throw RagError.FileOpenFailed(e)
    } catch (e: java.io.IOException) {
      throw RagError.FileOpenFailed(e)
    }

    input.use { stream ->
      val doc = try {
        PDDocument.load(stream)
      } catch (e: Throwable) {
        Log.w(TAG, "PdfBox failed to load document", e)
        throw RagError.NotAPdf(e)
      }
      doc.use { d ->
        val stripper = PDFTextStripper()
        val pageCount = d.numberOfPages
        val out = ArrayList<PageText>(pageCount)
        for (pageNum in 1..pageCount) {
          stripper.startPage = pageNum
          stripper.endPage = pageNum
          val text = stripper.getText(d)
          out.add(PageText(page = pageNum, text = text))
        }
        return@withContext out
      }
    }
  }

  companion object {
    private const val TAG = "AGPdfBoxTextExtractor"
  }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. If PdfBox classes are unresolved, re-check Task 1.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/PdfTextExtractor.kt
git commit -m "Add PdfBox-backed PDF text extractor"
```

---

## Task 10: RagRepository

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/RagRepository.kt`

- [ ] **Step 1: Write `RagRepository.kt`**

```kotlin
/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Hilt-singleton repository owning the active RAG index. */
interface RagRepository {
  val state: StateFlow<RagState>
  suspend fun ingestPdf(uri: Uri): Result<Unit>
  suspend fun clear()
  suspend fun retrieve(query: String, k: Int = 4): List<ScoredChunk>

  /** Convenience: returns the prompt prefix for [chunks], using the active doc name. Empty string if no index. */
  fun formatContext(chunks: List<ScoredChunk>): String
}

class DefaultRagRepository(
  private val context: Context,
  private val extractor: PdfTextExtractor,
) : RagRepository {

  private val _state = MutableStateFlow<RagState>(RagState.Empty)
  override val state: StateFlow<RagState> = _state.asStateFlow()

  private val mutex = Mutex()
  @Volatile private var current: RagIndexData? = null

  private val json = Json { ignoreUnknownKeys = true }

  init {
    // Probe for an existing index file. Don't load chunks into memory yet (lazy on first retrieve).
    val f = indexFile()
    if (f.exists()) {
      try {
        val loaded = json.decodeFromString(RagIndexData.serializer(), f.readText())
        current = loaded
        _state.value = RagState.Ready(loaded.docName, loaded.chunks.size)
      } catch (t: Throwable) {
        Log.w(TAG, "Discarding corrupt index file", t)
        f.delete()
        _state.value = RagState.Empty
      }
    }
  }

  override suspend fun ingestPdf(uri: Uri): Result<Unit> = mutex.withLock {
    val previous = current
    val previousState = _state.value
    try {
      _state.value = RagState.Indexing(0f)

      val docName = queryDisplayName(uri) ?: "document.pdf"

      // Extract (0.0 -> 0.4)
      val pages = extractor.extract(uri)
      _state.value = RagState.Indexing(0.4f)

      if (pages.all { it.text.isBlank() }) throw RagError.NoTextExtracted()
      if (pages.size > MAX_PAGES) throw RagError.TooLarge(pages.size)

      // Chunk (0.4 -> 0.6)
      val chunks = try {
        Chunker.chunk(pages)
      } catch (t: Throwable) {
        throw RagError.IndexBuildFailed(t)
      }
      _state.value = RagState.Indexing(0.6f)

      // Index (0.6 -> 0.9)
      val index = try {
        Bm25Index.build(chunks, docName = docName, createdAtMs = System.currentTimeMillis())
      } catch (t: Throwable) {
        throw RagError.IndexBuildFailed(t)
      }
      _state.value = RagState.Indexing(0.9f)

      // Persist atomically (0.9 -> 1.0)
      withContext(Dispatchers.IO) {
        try {
          val dir = indexDir().also { it.mkdirs() }
          val tmp = File(dir, "active_index.json.tmp")
          tmp.writeText(json.encodeToString(RagIndexData.serializer(), index))
          val target = indexFile()
          if (!tmp.renameTo(target)) {
            // Fallback: copy then delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
          }
        } catch (t: Throwable) {
          throw RagError.PersistenceFailed(t)
        }
      }

      current = index
      _state.value = RagState.Ready(docName, chunks.size)
      Result.success(Unit)
    } catch (e: RagError) {
      Log.w(TAG, "Ingestion failed", e)
      // Restore previous good state.
      current = previous
      _state.value = if (previous != null && previousState is RagState.Ready) previousState
                     else RagState.Empty
      Result.failure(e)
    } catch (t: Throwable) {
      Log.e(TAG, "Unexpected ingestion error", t)
      current = previous
      _state.value = if (previous != null && previousState is RagState.Ready) previousState
                     else RagState.Empty
      Result.failure(RagError.IndexBuildFailed(t))
    }
  }

  override suspend fun clear() = mutex.withLock {
    withContext(Dispatchers.IO) { indexFile().delete() }
    current = null
    _state.value = RagState.Empty
  }

  override suspend fun retrieve(query: String, k: Int): List<ScoredChunk> {
    val idx = current ?: return emptyList()
    return try {
      withContext(Dispatchers.Default) { Bm25Index.search(idx, query, k) }
    } catch (t: Throwable) {
      Log.w(TAG, "Retrieval failed; returning no results", t)
      emptyList()
    }
  }

  override fun formatContext(chunks: List<ScoredChunk>): String {
    val docName = current?.docName ?: return ""
    return ContextFormatter.format(docName, chunks)
  }

  // ---------- helpers ----------

  private fun indexDir(): File = File(context.filesDir, "rag")
  private fun indexFile(): File = File(indexDir(), "active_index.json")

  private fun queryDisplayName(uri: Uri): String? {
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
          if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
      null
    }
  }

  companion object {
    private const val TAG = "AGRagRepository"
    private const val MAX_PAGES = 500
  }
}
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/data/rag/RagRepository.kt
git commit -m "Add RagRepository orchestrating ingestion, persistence, and retrieval"
```

---

## Task 11: Wire `RagRepository` into Hilt

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt`

- [ ] **Step 1: Add provider methods**

In `AppModule.kt`, add these imports near the top (alongside other `import com.google.ai.edge.gallery.data...` lines):

```kotlin
import com.google.ai.edge.gallery.data.rag.DefaultRagRepository
import com.google.ai.edge.gallery.data.rag.PdfBoxTextExtractor
import com.google.ai.edge.gallery.data.rag.PdfTextExtractor
import com.google.ai.edge.gallery.data.rag.RagRepository
```

Then, inside the `internal object AppModule { ... }` block, add (place after `provideDownloadRepository`, near the end):

```kotlin
  // Provides PdfTextExtractor
  @Provides
  @Singleton
  fun providePdfTextExtractor(@ApplicationContext context: Context): PdfTextExtractor {
    return PdfBoxTextExtractor(context)
  }

  // Provides RagRepository
  @Provides
  @Singleton
  fun provideRagRepository(
    @ApplicationContext context: Context,
    extractor: PdfTextExtractor,
  ): RagRepository {
    return DefaultRagRepository(context, extractor)
  }
```

- [ ] **Step 2: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt
git commit -m "Wire RagRepository and PdfTextExtractor into Hilt"
```

---

## Task 12: Augment `LlmChatViewModel` with RAG state and prompt prefixing

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt`

This task only modifies the **text-only** subclass (`LlmChatViewModel`). The base `LlmChatViewModelBase.generateResponse` accepts `input: String`; the simplest non-invasive approach is to override or wrap `generateResponse` in the text-only subclass and rewrite `input` before delegating to `super.generateResponse(...)`.

- [ ] **Step 1: Read current shape of the ViewModel**

```bash
sed -n '1,60p;320,360p' Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt
```
Confirm:
- The `LlmChatViewModel` Hilt subclass exists around line 344-348.
- The base class signature is `LlmChatViewModelBase` and `generateResponse(model, input, images, audioMessages, onFirstToken, onDone, onError, allowThinking)` is `open` (or can be made `open`). If it's not `open`, in this same step add the `open` modifier to the base method signature so the subclass can override it.

(If the base method is already non-open and you don't want to modify the base, the alternative is to inject `RagRepository` into the base class and apply the prefixing there, gated by `model.runtimeType` and a flag. Choose whichever is least invasive given the actual code; both are valid.)

- [ ] **Step 2: Inject dependencies into `LlmChatViewModel`**

Find the `@HiltViewModel class LlmChatViewModel @Inject constructor(...) : LlmChatViewModelBase(...)` declaration. Add two new constructor parameters:

```kotlin
@HiltViewModel
class LlmChatViewModel @Inject constructor(
  private val ragRepository: com.google.ai.edge.gallery.data.rag.RagRepository,
  private val dataStoreRepository: com.google.ai.edge.gallery.data.DataStoreRepository,
  // ... existing params unchanged ...
) : LlmChatViewModelBase(/* existing args */) {
```

(Use top-of-file imports rather than fully-qualified names.)

- [ ] **Step 3: Expose RAG state to the UI**

Inside `LlmChatViewModel`, add:

```kotlin
  val ragState: kotlinx.coroutines.flow.StateFlow<com.google.ai.edge.gallery.data.rag.RagState> =
    ragRepository.state

  val ragEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> =
    dataStoreRepository.ragEnabledFlow().stateIn(
      scope = viewModelScope,
      started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
      initialValue = dataStoreRepository.getRagEnabled(),
    )

  fun setRagEnabled(enabled: Boolean) {
    dataStoreRepository.setRagEnabled(enabled)
  }

  fun ingestPdf(uri: android.net.Uri, onError: (String) -> Unit = {}) {
    viewModelScope.launch {
      val result = ragRepository.ingestPdf(uri)
      result.exceptionOrNull()?.let { e ->
        onError(e.message ?: "Failed to ingest PDF")
      }
    }
  }

  fun clearRag() {
    viewModelScope.launch { ragRepository.clear() }
  }
```

(Replace fully-qualified names with top-of-file imports: `import androidx.lifecycle.viewModelScope`, `import kotlinx.coroutines.flow.SharingStarted`, `import kotlinx.coroutines.flow.StateFlow`, `import kotlinx.coroutines.flow.stateIn`, `import kotlinx.coroutines.launch`, `import com.google.ai.edge.gallery.data.rag.RagState`, `import com.google.ai.edge.gallery.data.rag.RagRepository`, `import com.google.ai.edge.gallery.data.DataStoreRepository`, `import android.net.Uri`.)

- [ ] **Step 4: Override `generateResponse` to prepend RAG context**

Add to the `LlmChatViewModel` class:

```kotlin
  override fun generateResponse(
    model: com.google.ai.edge.gallery.data.Model,
    input: String,
    images: List<android.graphics.Bitmap>,
    audioMessages: List<com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip>,
    onFirstToken: (com.google.ai.edge.gallery.data.Model) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  ) {
    val effectiveInput =
      if (ragEnabled.value && ragState.value is com.google.ai.edge.gallery.data.rag.RagState.Ready) {
        // Retrieval is suspending; do it in a coroutine and then call super on the main path.
        viewModelScope.launch {
          val scored = ragRepository.retrieve(input, k = 4)
          val prefix = ragRepository.formatContext(scored)
          val prefixed = if (prefix.isEmpty()) input else prefix + input
          super@LlmChatViewModel.generateResponse(
            model, prefixed, images, audioMessages, onFirstToken, onDone, onError, allowThinking
          )
        }
        return
      } else input

    super.generateResponse(
      model, effectiveInput, images, audioMessages, onFirstToken, onDone, onError, allowThinking
    )
  }
```

Match the actual base-class method signature in this file — names/types may differ slightly (e.g., `audioMessages` could be `audioClips`). Adjust accordingly. The key invariant: `input` becomes `prefixed` only when handing off to `super.generateResponse`; the chat-history `ChatMessageText` is added in `ChatViewWrapper` (Task 14) using the original `text`, so this stays invisible to the user.

- [ ] **Step 5: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. If `generateResponse` is not `open` in the base, add `open` to it (in the same task).

- [ ] **Step 6: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatViewModel.kt
git commit -m "Augment LlmChatViewModel with RAG state and prompt prefixing"
```

---

## Task 13: Add RAG UI affordances to `MessageInputText` (and thread params through `ChatView`)

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt`

This task adds three UI affordances, all gated to text chat:

1. An **Attach PDF** icon button next to the existing image/audio attach buttons.
2. A **RAG toggle** (icon button that toggles tint/state) next to it.
3. A small **active-doc chip** above the input row showing `📄 <filename> (<n> chunks) ✕` when `RagState.Ready`, or "Attach a PDF to use RAG" when toggle is on but no index.

- [ ] **Step 1: Read current `MessageInputText` parameter list**

```bash
sed -n '1,80p' Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt
```
Identify the existing `showImagePicker`/`showAudioPicker` flag pattern and the parameter list of the composable. The new params will follow the same pattern.

- [ ] **Step 2: Add new params to `MessageInputText`**

In `MessageInputText.kt`, add these parameters (default values keep all other call sites compiling unchanged):

```kotlin
  showPdfPicker: Boolean = false,
  showRagToggle: Boolean = false,
  ragEnabled: Boolean = false,
  ragState: com.google.ai.edge.gallery.data.rag.RagState = com.google.ai.edge.gallery.data.rag.RagState.Empty,
  onPickPdf: () -> Unit = {},
  onToggleRag: () -> Unit = {},
  onClearRag: () -> Unit = {},
```

(Add imports at file top: `import com.google.ai.edge.gallery.data.rag.RagState`.)

- [ ] **Step 3: Render the active-doc chip above the input row**

Inside the composable, just above the row that holds the text field and attach buttons, add:

```kotlin
  if (showRagToggle || showPdfPicker) {
    val s = ragState
    when (s) {
      is com.google.ai.edge.gallery.data.rag.RagState.Ready -> {
        androidx.compose.material3.AssistChip(
          onClick = { /* no-op */ },
          label = { androidx.compose.material3.Text("\uD83D\uDCC4 ${s.docName} (${s.chunkCount} chunks)") },
          trailingIcon = {
            androidx.compose.material3.IconButton(onClick = onClearRag) {
              androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Close,
                contentDescription = "Clear PDF"
              )
            }
          },
          modifier = androidx.compose.ui.Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
      }
      is com.google.ai.edge.gallery.data.rag.RagState.Indexing -> {
        androidx.compose.material3.AssistChip(
          onClick = {},
          label = { androidx.compose.material3.Text("Indexing… ${(s.progress * 100).toInt()}%") },
          modifier = androidx.compose.ui.Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
      }
      is com.google.ai.edge.gallery.data.rag.RagState.Error -> {
        androidx.compose.material3.AssistChip(
          onClick = {},
          label = { androidx.compose.material3.Text("RAG: ${s.error.message}") },
          modifier = androidx.compose.ui.Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
      }
      com.google.ai.edge.gallery.data.rag.RagState.Empty -> {
        if (ragEnabled) {
          androidx.compose.material3.AssistChip(
            onClick = onPickPdf,
            label = { androidx.compose.material3.Text("Attach a PDF to use RAG") },
            modifier = androidx.compose.ui.Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
          )
        }
      }
    }
  }
```

(Refactor with proper imports rather than inline FQNs: `import androidx.compose.material3.AssistChip`, `import androidx.compose.material3.Icon`, `import androidx.compose.material3.IconButton`, `import androidx.compose.material.icons.Icons`, `import androidx.compose.material.icons.filled.Close`, `import androidx.compose.material.icons.filled.PictureAsPdf`, `import com.google.ai.edge.gallery.data.rag.RagState`.)

- [ ] **Step 4: Add the Attach-PDF and RAG-toggle icon buttons**

Find the row of icon buttons that holds image/audio attach buttons (look for `showImagePicker`/`showAudioPicker` blocks). Add **immediately after** the audio button block:

```kotlin
  if (showPdfPicker) {
    androidx.compose.material3.IconButton(onClick = onPickPdf) {
      androidx.compose.material3.Icon(
        imageVector = androidx.compose.material.icons.Icons.Default.PictureAsPdf,
        contentDescription = "Attach PDF",
      )
    }
  }
  if (showRagToggle) {
    androidx.compose.material3.IconButton(onClick = onToggleRag) {
      androidx.compose.material3.Icon(
        imageVector = androidx.compose.material.icons.Icons.Default.PictureAsPdf,
        contentDescription = if (ragEnabled) "RAG enabled" else "RAG disabled",
        tint = if (ragEnabled)
          androidx.compose.material3.MaterialTheme.colorScheme.primary
        else
          androidx.compose.material3.LocalContentColor.current,
      )
    }
  }
```

(In practice, pick a different icon for the toggle if `PictureAsPdf` clashes — `Icons.Default.LibraryBooks` or `Icons.AutoMirrored.Filled.MenuBook` are reasonable. The exact icon is a deferred choice from the spec.)

- [ ] **Step 5: Thread the new params through `ChatView.kt`**

Open `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt` and find where it accepts `showImagePicker` / `showAudioPicker` and forwards to `MessageInputText`. Add the same set of new params (same defaults) to `ChatView`'s signature, and forward them when constructing `MessageInputText`.

```bash
grep -n "showImagePicker" Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt
grep -n "MessageInputText" Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt
```
Mirror exactly the pattern used for `showImagePicker`.

- [ ] **Step 6: Build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt
git commit -m "Add RAG UI affordances (Attach PDF, toggle, active-doc chip) to chat input"
```

---

## Task 14: Wire RAG UI in `LlmChatScreen` / `ChatViewWrapper`

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt`

Only the text-only `LlmChatScreen` opts in to RAG UI. `LlmAskImageScreen` and `LlmAskAudioScreen` keep the new params at their defaults (off).

- [ ] **Step 1: Add RAG flag params to `ChatViewWrapper`**

In `LlmChatScreen.kt`, add to the `ChatViewWrapper` parameter list (after `showAudioPicker`):

```kotlin
  showPdfPicker: Boolean = false,
  showRagToggle: Boolean = false,
```

- [ ] **Step 2: Set up the SAF launcher and observe RAG state**

Inside `ChatViewWrapper`, near the top of the composable body (after `val context = LocalContext.current` line ~196), add:

```kotlin
  // Cast viewModel to the text-only subclass to access RAG APIs. Other subclasses won't have them.
  val llmVm = viewModel as? com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
  val ragState by (llmVm?.ragState
    ?: kotlinx.coroutines.flow.MutableStateFlow(com.google.ai.edge.gallery.data.rag.RagState.Empty))
    .collectAsStateWithLifecycle()
  val ragEnabled by (llmVm?.ragEnabled
    ?: kotlinx.coroutines.flow.MutableStateFlow(false))
    .collectAsStateWithLifecycle()

  val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
  ) { uri: android.net.Uri? ->
    if (uri != null) {
      llmVm?.ingestPdf(uri) { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
      }
    }
  }
```

(Imports needed: `import androidx.compose.runtime.getValue`, `import androidx.lifecycle.compose.collectAsStateWithLifecycle`. The `lifecycle-compose-runtime` artifact may need to be added to the build if `collectAsStateWithLifecycle` is not yet on the classpath — check first; many Compose projects already have it transitively. If missing, add `androidx-lifecycle-runtime-compose` to libs and `app/build.gradle.kts` as part of this step.)

- [ ] **Step 3: Pass RAG params to `ChatView`**

In the `ChatView( ... )` call inside `ChatViewWrapper`, add at the end of the parameter list (before the closing `)`):

```kotlin
    showPdfPicker = showPdfPicker,
    showRagToggle = showRagToggle,
    ragEnabled = ragEnabled,
    ragState = ragState,
    onPickPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
    onToggleRag = { llmVm?.setRagEnabled(!ragEnabled) },
    onClearRag = { llmVm?.clearRag() },
```

(Task 13 added matching params to `ChatView`; if any name doesn't match, fix in `ChatView.kt`.)

- [ ] **Step 4: Opt in from `LlmChatScreen`**

In the `LlmChatScreen` composable (around line 78 — the call to `ChatViewWrapper`), add:

```kotlin
    showPdfPicker = true,
    showRagToggle = true,
```

`LlmAskImageScreen` and `LlmAskAudioScreen` are unchanged (defaults off).

- [ ] **Step 5: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. (Full assembleDebug here to catch any resource/manifest issue early.)

- [ ] **Step 6: Commit**

```bash
cd /Users/amine/AI/gallery
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt
git commit -m "Wire RAG picker, toggle, and chip into LlmChatScreen"
```

---

## Task 15: Manual verification

No automated tests in this plan — verify on a real device or emulator.

- [ ] **Step 1: Install the debug build**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Smoke test the RAG flow**

On the device, with a model already downloaded:

1. Open the **LLM Chat** task.
2. Confirm a new "📄" (Attach PDF) icon and a "RAG" toggle icon appear in the input bar.
3. Tap Attach PDF, pick a small text-based PDF (e.g., a 5–20 page document with selectable text).
4. Confirm the chip shows "Indexing… X%" briefly, then "📄 yourfile.pdf (N chunks) ✕".
5. Tap the RAG toggle ON (icon tints primary color).
6. Ask a question whose answer is in the PDF. Verify the model's response references content from the PDF.
7. Tap RAG toggle OFF. Ask the same question. Verify the response is now generic.
8. Verify the chat history shows your original questions (not the augmented prompt).
9. Tap ✕ on the chip — confirm chip disappears and `filesDir/rag/active_index.json` is deleted (`adb shell run-as com.google.aiedge.gallery ls files/rag` should be empty or missing).
10. Re-attach a PDF, force-stop the app, reopen — confirm the chip restores and RAG queries still work (persistence verified).

- [ ] **Step 3: Negative cases**

1. Attach a non-PDF file (rename a `.jpg` to `.pdf` if your picker doesn't filter strictly): confirm a Toast appears with "The selected file is not a valid PDF." and the previous chip (if any) is preserved.
2. Attach an image-only PDF (scanned document with no text): confirm Toast says "No text could be extracted…".
3. Attach a >500 page PDF: confirm Toast says "This PDF is too large…".

- [ ] **Step 4: Restart-with-corrupt-index test (optional)**

```bash
adb shell run-as com.google.aiedge.gallery sh -c 'echo not-json > files/rag/active_index.json'
```
Force-stop and reopen the app. Open LLM Chat — there should be no chip, no crash, and `files/rag/active_index.json` should be deleted.

---

## Self-review checklist

(Author runs this after writing the plan; not part of the plan execution.)

**Spec coverage:**
- ✅ PDF ingestion via PdfBox-Android — Tasks 1, 9
- ✅ Chunker with overlap + page tags — Task 6
- ✅ BM25 index — Task 7
- ✅ Persistence to `filesDir/rag/active_index.json` with atomic rename — Task 10
- ✅ `rag_enabled` proto + accessors — Tasks 2, 3
- ✅ `RagRepository` Hilt singleton — Tasks 10, 11
- ✅ `formatContext` prepended to prompt; chat history shows original input — Task 12 (override) + Task 14 (`ChatViewWrapper.onSendMessage` already adds the original `ChatMessageText` before calling `generateResponse`, so this is already correct without further change)
- ✅ Inline toggle + Attach PDF + active-doc chip — Tasks 13, 14
- ✅ Lazy index probe at startup — Task 10 (init block reads file but defers nothing here since loading the JSON is the same operation; effectively eager. Acceptable for v1.)
- ✅ Errors + UI Toast handling — Task 14 (Toast in `ingestPdf` callback) plus repository state transitions
- ✅ Tests skipped per user decision — documented in plan header

**Placeholder scan:** No "TBD" / "implement later" / "add appropriate error handling" without concrete code. Where I wrote "match exact base-class signature" (Task 12) it's because the signature must be discovered from current code; the step gives explicit instructions on how. Acceptable.

**Type consistency:** `RagState` / `RagChunk` / `RagIndexData` / `ScoredChunk` / `RagRepository` / `PdfTextExtractor` names used identically in Tasks 4 onward. `formatContext(chunks: List<ScoredChunk>): String` consistent. `ingestPdf(uri): Result<Unit>` consistent. `ragEnabledFlow(): Flow<Boolean>` consistent.

---

## Open implementation questions (to resolve during execution)

1. Is `LlmChatViewModelBase.generateResponse` `open`? If not, add `open` modifier in Task 12 Step 1.
2. Is `androidx.lifecycle.runtime.compose` (for `collectAsStateWithLifecycle`) already on the classpath? If not, add `androidx-lifecycle-runtime-compose` in Task 14 Step 2.
3. Final icon choice for the RAG toggle (Task 13 Step 4) — `Icons.AutoMirrored.Filled.MenuBook` is recommended; pick whatever reads cleanly on-device.
