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
   * With a rough words-to-tokens ratio of ~1.3, this caps the RAG prefix at ~3250 tokens —
   * leaving room for a large shadow history and the user's input under the 10k context cap.
   * The token-budget trimmer in `LlmChatViewModel` may shrink the prefix further per-turn.
   */
  private const val MAX_PREFIX_WORDS = 1500

  /**
   * Produces a prefix that wraps retrieved chunks in XML-style structural markers the model
   * is trained to recognize as authoritative context fences. Empty list returns empty string
   * (caller should skip RAG augmentation in that case).
   *
   * The emitted shape is:
   *
   *     <knowledge_base source="document.md">
   *     <excerpt index="1">chunk body</excerpt>
   *     <excerpt index="2">chunk body</excerpt>
   *     ...
   *     </knowledge_base>
   *
   * The caller is expected to follow this prefix with a `<user_question>…</user_question>`
   * block carrying the user's actual input — see `LlmChatViewModel`. The system prompt
   * documents both fences so the model knows their semantics. This three-region structure
   * (system instruction / knowledge_base / user_question) gives the model unambiguous
   * boundaries between the retrieved documentation and the current user question, which
   * would otherwise both share a single `user` chat-template turn with only prose cues to
   * tell them apart.
   *
   * Chunks are appended in retrieval-score order; once [MAX_PREFIX_WORDS] would be exceeded
   * the remaining chunks are dropped (and the current chunk is truncated to fit). When every
   * retrieved chunk is from "page 1" (the case for markdown sources, treated as a single
   * synthetic page) no `page=` attribute is emitted on the excerpt tag.
   */
  fun format(docName: String, chunks: List<ScoredChunk>): String {
    if (chunks.isEmpty()) return ""

    // If every chunk is from a single synthetic "page 1" (markdown source), drop the page
    // attribute entirely — it conveys no information.
    val emitPageAttr =
      chunks.any { it.chunk.pageStart != 1 || it.chunk.pageEnd != 1 }

    val sb = StringBuilder()
    sb.append("<knowledge_base source=\"")
    sb.append(escapeAttr(docName))
    sb.append("\">\n")

    var wordBudget = MAX_PREFIX_WORDS
    var excerptIndex = 1
    for (sc in chunks) {
      if (wordBudget <= 0) break
      val words = sc.chunk.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
      val truncated = words.size > wordBudget
      val keep = if (truncated) words.subList(0, wordBudget) else words
      val body = keep.joinToString(" ")

      sb.append("<excerpt index=\"").append(excerptIndex).append("\"")
      if (emitPageAttr) {
        val pageAttr =
          if (sc.chunk.pageStart == sc.chunk.pageEnd) sc.chunk.pageStart.toString()
          else "${sc.chunk.pageStart}-${sc.chunk.pageEnd}"
        sb.append(" page=\"").append(pageAttr).append("\"")
      }
      sb.append(">")
      sb.append(body)
      if (truncated) sb.append(" ...")
      sb.append("</excerpt>\n")

      wordBudget -= keep.size
      excerptIndex++
    }
    sb.append("</knowledge_base>\n")
    return sb.toString()
  }

  /** Minimal XML attribute escaping for the doc-name attribute value. */
  private fun escapeAttr(s: String): String =
    s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
