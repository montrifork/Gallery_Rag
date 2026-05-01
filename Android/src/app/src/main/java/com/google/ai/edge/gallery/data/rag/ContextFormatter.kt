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
