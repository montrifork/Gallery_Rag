/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data.rag

import android.util.Log

/**
 * Pre-generation number replacement for RAG context. Substitutes every numeric
 * span in the given text with a globally-unique uppercase letter tag
 * (e.g. `[A]`, `[B]`, ..., `[Z]`, then `[AA]`, `[AB]`, ..., `[ZZ]`), returning
 * the rewritten text plus the tag -> "original string" mapping. The model is
 * then instructed (via the system prompt) to quote tags rather than digits;
 * after generation the tags are substituted back via [untag] before the answer
 * is shown to the user.
 *
 * Why single uppercase letters (and not [N1] / [ALFA] / sentence-derived words):
 *
 *   - Digit-bearing tags like [N1] reintroduce exactly the int4 number-token
 *     confusion they were meant to avoid: the model conflates [N1] with [N12].
 *
 *   - Pronounceable opaque words like [ALFA] are still words: the model can
 *     "inflect" them autoregressively into [ALFAFA] / [ALFALFA] because the
 *     interior pieces are real BPE chunks with plausible continuations. NATO
 *     callsigns were designed for human radio clarity (i.e. distinctive *as
 *     words*) - the opposite of what we need.
 *
 *   - Sentence-derived multi-word tags like [WAITING_PERIOD_MONTHS] are
 *     grammatical but leak semantics: the model selects tags by topical match
 *     to the question rather than by the surrounding sentence, and can invent
 *     near-paraphrases ([WAITING_PERIODS_MONTHS], [DENTAL_WORK_MONTHS]).
 *
 *   - Single uppercase letters in brackets are: (a) tokenizer-singletons in
 *     practically every modern BPE, (b) zero-semantic, (c) have no morphology
 *     to inflect - the model cannot extend `A` into a longer word without first
 *     emitting `]`, which the bracket grammar discourages. Disambiguation is
 *     forced onto the surrounding plain-text words (units, topic nouns),
 *     which is exactly the behavior we want.
 *
 * Pool capacity: 26 single-letter + 26*26 two-letter = 702 unique tags per
 * turn, far above the observed ~5-20 numbers in realistic RAG retrievals.
 * Beyond 702 the pool recycles deterministically (logged warning); collisions
 * would surface as duplicate map entries which the verifier would flag.
 *
 * Scope: only retrieved <excerpt> content is tagged. The user's question and
 * shadow history are left as-is. Tags are turn-scoped (numberMap is rebuilt
 * fresh per retrieval); the user-visible answer stored in shadow history is
 * the post-substitution form so future turns see clean, self-consistent text.
 */
object NumberTagger {
  private const val TAG = "NumberTagger"

  data class TaggedContext(val text: String, val numberMap: Map<String, String>)

  /**
   * Matches integers and decimals with optional thousands separators:
   *   250, 12.5, 1,000.50, 30.0
   *
   * The first alternative captures grouped-thousands forms ("1,000"); the
   * second alternative captures bare integers and decimals.
   */
  val NUMBER_REGEX =
    Regex("""\b\d{1,3}(?:[.,]\d{3})+(?:\.\d+)?\b|\b\d+(?:\.\d+)?\b""")

  /**
   * Matches a complete bracketed one- or two-letter uppercase tag produced by
   * [tag]. Three-or-more-letter bracketed strings (e.g. `[ALFA]`, `[ALFAFA]`),
   * lowercase, digits-in-brackets, and underscores all FAIL this regex and so
   * remain as literal text in the rendered display - where the verifier's
   * "unknown tag" / "deformation" pass surfaces them in red.
   */
  val TAG_REGEX = Regex("""\[([A-Z]{1,2})]""")

  /** Capacity before deterministic recycling kicks in. */
  private const val POOL_CAPACITY = 26 + 26 * 26 // 702

  /**
   * Returns the i-th opaque tag word (no brackets).
   *   i in [0, 26)        -> single letter   "A".."Z"
   *   i in [26, 702)      -> two letters     "AA","AB",...,"ZZ"
   *   i >= 702            -> recycled (mod 702), logged
   */
  private fun tagWord(i: Int): String {
    if (i >= POOL_CAPACITY) {
      Log.w(
        TAG,
        "NumberTagger pool exhausted (i=$i >= $POOL_CAPACITY); recycling. " +
          "This will produce a duplicate map entry; verifier will surface any conflict.",
      )
    }
    val idx = i % POOL_CAPACITY
    if (idx < 26) {
      return ('A' + idx).toString()
    }
    val twoIdx = idx - 26
    val hi = ('A' + (twoIdx / 26))
    val lo = ('A' + (twoIdx % 26))
    return "$hi$lo"
  }

  /**
   * Replaces every numeric span in [text] with `[A]`, `[B]`, ... in encounter
   * order. Returned map is a [LinkedHashMap] so callers (snapshot formatter,
   * debug UI) iterate in the same order the model encounters the tags.
   */
  fun tag(text: String): TaggedContext {
    val map = LinkedHashMap<String, String>()
    var counter = 0
    val result =
      NUMBER_REGEX.replace(text) { m ->
        val key = tagWord(counter)
        counter += 1
        // Recycling collision: keep the FIRST mapping for a given key so the
        // earliest-seen value wins; subsequent collisions are dropped silently
        // here and surfaced by the verifier downstream.
        if (key !in map) map[key] = m.value
        "[$key]"
      }
    return TaggedContext(text = result, numberMap = map)
  }

  /**
   * Replaces every recognized tag in [answer] with its original value from
   * [numberMap]. Tags not present in the map are left as-is so the downstream
   * verifier can surface them as red-underlined errors.
   *
   * Safe to call on partial streaming content: only fully-closed `[A]` /
   * `[AB]` forms match, so a half-emitted `[A` at the end of a chunk stays
   * literal until the next chunk provides the closing bracket.
   */
  fun untag(answer: String, numberMap: Map<String, String>?): String {
    if (numberMap.isNullOrEmpty()) return answer
    return TAG_REGEX.replace(answer) { m ->
      val key = m.groupValues[1]
      numberMap[key] ?: m.value
    }
  }

  /**
   * Returns the character ranges in [answer] referencing tag keys NOT present
   * in [numberMap]. Used by the verifier to surface invented references after
   * untag-ing has left them as literal "[XY]" text.
   */
  fun unknownTagRangesIn(answer: String, numberMap: Map<String, String>?): List<IntRange> {
    if (numberMap == null) return emptyList()
    return TAG_REGEX.findAll(answer)
      .filter { it.groupValues[1] !in numberMap }
      .map { it.range }
      .toList()
  }
}
