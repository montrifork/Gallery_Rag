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

/**
 * A single text chunk extracted from a knowledge-base document, with the page span it covers
 * and pre-tokenized terms. For markdown sources [pageStart] / [pageEnd] both default to `1`
 * (the document is treated as a single synthetic page). Legacy PDF-derived indices still load
 * fine because the schema is unchanged.
 */
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

/**
 * A page of raw text from a knowledge-base document (1-indexed). For markdown sources only
 * a single [PageText] with `page = 1` is produced; for legacy PDF sources one per page.
 */
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

  class NotAMarkdownFile(cause: Throwable? = null) :
    RagError("The selected file is not a valid Markdown file.", cause)

  class NoTextExtracted :
    RagError("No text could be extracted from this file.")

  class TooLarge(val pages: Int) :
    RagError("This document is too large ($pages sections). Please choose a smaller one.")

  class IndexBuildFailed(cause: Throwable) :
    RagError("Failed to build the search index.", cause)

  class PersistenceFailed(cause: Throwable) :
    RagError("Failed to save the index to storage.", cause)

  class CorruptIndex(cause: Throwable) :
    RagError("Saved index is corrupt and was discarded.", cause)
}
