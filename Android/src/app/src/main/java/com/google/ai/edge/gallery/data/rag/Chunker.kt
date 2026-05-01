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
