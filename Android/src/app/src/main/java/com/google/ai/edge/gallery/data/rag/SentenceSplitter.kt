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

import java.text.BreakIterator
import java.util.Locale

/**
 * Splits a text string into sentence-boundary [IntRange]s using the ICU-backed
 * [BreakIterator.getSentenceInstance]. Each returned range is a half-open `[start, end)`
 * offset pair into the original string, excluding leading/trailing whitespace within each
 * sentence.
 *
 * Empty or whitespace-only segments are filtered out.
 *
 * The ICU sentence breaker handles most abbreviation and Unicode edge cases correctly on
 * Android API 24+. It can still mis-split on domain-specific abbreviations (e.g. medical
 * jargon); this is the practical ceiling without a trained ML segmenter.
 */
object SentenceSplitter {

  /**
   * Returns sentence ranges within [text]. Each range covers one sentence, trimmed of
   * leading/trailing whitespace. The ranges are non-overlapping and in document order.
   */
  fun split(text: String): List<IntRange> {
    if (text.isBlank()) return emptyList()

    val bi = BreakIterator.getSentenceInstance(Locale.ROOT)
    bi.setText(text)

    val ranges = mutableListOf<IntRange>()
    var start = bi.first()
    var end = bi.next()
    while (end != BreakIterator.DONE) {
      // Trim whitespace from each sentence boundary.
      var s = start
      var e = end
      while (s < e && text[s].isWhitespace()) s++
      while (e > s && text[e - 1].isWhitespace()) e--
      if (s < e) {
        ranges.add(s until e)
      }
      start = end
      end = bi.next()
    }
    return ranges
  }
}
