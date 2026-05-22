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
   * Hard cap on the number of whitespace-split words emitted across all retrieved chunks.
   * With a rough words-to-tokens ratio of ~1.3, this caps the RAG prefix at ~6500 tokens — well
   * under the 32k context window even when combined with prior conversational history and the
   * user's question.
   */
  private const val MAX_PREFIX_WORDS = 5000

  /**
   * Produces a prefix that instructs the model to use the provided context. Empty list returns
   * empty string (caller should skip RAG augmentation in that case).
   *
   * Chunks are appended in order; once [MAX_PREFIX_WORDS] would be exceeded the remaining chunks
   * are dropped (and the current chunk is truncated to fit). This prevents an unusually large
   * retrieval from overflowing the model's context window.
   */
  fun format(docName: String, chunks: List<ScoredChunk>): String {
    if (chunks.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("Use the following context from \"")
    sb.append(docName)
    sb.append("\" to answer the question. ")
    sb.append("If the context does not contain the answer, say so.\n\n")

    var wordBudget = MAX_PREFIX_WORDS
    for (sc in chunks) {
      if (wordBudget <= 0) break
      val pageLabel =
        if (sc.chunk.pageStart == sc.chunk.pageEnd) "page ${sc.chunk.pageStart}"
        else "pages ${sc.chunk.pageStart}-${sc.chunk.pageEnd}"

      val words = sc.chunk.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
      val truncated = words.size > wordBudget
      val keep = if (truncated) words.subList(0, wordBudget) else words
      val body = keep.joinToString(" ")
      sb.append("[").append(pageLabel).append("] ").append(body)
      if (truncated) sb.append(" …")
      sb.append("\n\n")
      wordBudget -= keep.size
    }
    sb.append("Question:\n")
    return sb.toString()
  }
}
