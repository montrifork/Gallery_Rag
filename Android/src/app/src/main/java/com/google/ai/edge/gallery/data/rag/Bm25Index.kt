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
