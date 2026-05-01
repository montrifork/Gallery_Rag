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
