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

import android.util.Log

/** Builds the prompt prefix that wraps retrieved chunks for the LLM. */
object ContextFormatter {

  private const val TAG = "ContextFormatter"

  /**
   * Hard cap on the number of whitespace-split words emitted across all retrieved chunks.
   * With a rough words-to-tokens ratio of ~1.3, this caps the RAG prefix at ~3250 tokens —
   * leaving room for a large shadow history and the user's input under the 10k context cap.
   * The token-budget trimmer in `LlmChatViewModel` may shrink the prefix further per-turn.
   */
  private const val MAX_PREFIX_WORDS = 1500

  /** Maximum sentence tags per chunk. Sentences 27+ are merged into the 26th tag. */
  private const val MAX_SENTENCES_PER_CHUNK = 26

  /**
   * Result of formatting a set of retrieved chunks.
   *
   * [text] is the `<knowledge_base>...</knowledge_base>` prefix string to splice into the
   * model prompt. [validExcerptTags] is the set of opaque sentence-level citation tags that
   * the model is permitted to cite, e.g. `{"[XAA]", "[XAB]", "[XBA]"}`. Citations the
   * model emits that fall outside this set are flagged by the verifier as invented refs.
   *
   * [detailsByTag] maps each sentence tag to a structured description carrying the full
   * parent excerpt body plus the highlighted sentence range within it. Used by the UI's
   * "tap a citation" sheet. Values are stored pre-tagging (real digits intact) so the
   * sheet renders the human-readable form directly with no number-tag substitution.
   */
  data class FormattedContext(
    val text: String,
    val validExcerptTags: Set<String>,
    val detailsByTag: Map<String, ExcerptDetails>,
  )

  /**
   * Structured description of a single cited sentence within a retrieved excerpt, keyed by
   * its opaque three-letter tag (e.g. `[XAC]`).
   *
   * [parentBody] is the full post-truncation excerpt text in its ORIGINAL digit form (before
   * `NumberTagger` substitutes `[A]`/`[B]` for numbers). All sentence entries from the same
   * chunk share the same `parentBody` String instance — intentional aliasing, safe because
   * the string is immutable.
   *
   * [sentenceRange] is a half-open `[start, end)` offset into [parentBody] identifying the
   * cited sentence. The sheet UI applies bold/highlight styling over this range so the user
   * sees the exact sentence that the model claims supports its answer.
   *
   * [pageStart]/[pageEnd] convey the source page range; for markdown sources both are 1
   * (treat as "page unknown" — caller may suppress the page line entirely).
   */
  data class ExcerptDetails(
    val parentBody: String,
    val sentenceRange: IntRange,
    val source: String,
    val pageStart: Int,
    val pageEnd: Int,
  )

  /**
   * Produces a prefix that wraps retrieved chunks in XML-style structural markers the model
   * is trained to recognize as authoritative context fences. Empty list returns
   * `FormattedContext("", emptySet(), emptyMap())`.
   *
   * The emitted shape is:
   *
   *     <knowledge_base source="document.md">
   *     <excerpt page="12">
   *     <s tag="[XAA]">First sentence.</s>
   *     <s tag="[XAB]">Second sentence.</s>
   *     </excerpt>
   *     <excerpt page="3">
   *     <s tag="[XBA]">Another sentence.</s>
   *     </excerpt>
   *     </knowledge_base>
   *
   * Each `<s>` element carries a three-letter `tag="[X??]"` attribute that the model is
   * instructed (via the system prompt) to copy verbatim after every factual sentence in its
   * answer. The first letter after `X` identifies the parent chunk (A–Z in score order);
   * the second letter identifies the sentence within that chunk (A–Z in document order).
   *
   * The `X` prefix is reserved across the system: `NumberTagger` never allocates a number
   * tag whose first letter is `X`, so sentence tags never collide with number tags.
   *
   * Chunks are appended in retrieval-score order; once [MAX_PREFIX_WORDS] would be exceeded
   * the remaining chunks are dropped (and the current chunk is truncated to fit).
   */
  fun format(docName: String, chunks: List<ScoredChunk>): FormattedContext {
    if (chunks.isEmpty()) return FormattedContext("", emptySet(), emptyMap())

    val emitPageAttr =
      chunks.any { it.chunk.pageStart != 1 || it.chunk.pageEnd != 1 }

    val sb = StringBuilder()
    sb.append("<knowledge_base source=\"")
    sb.append(escapeAttr(docName))
    sb.append("\">\n")

    val validTags = LinkedHashSet<String>()
    val detailsByTag = LinkedHashMap<String, ExcerptDetails>()
    var wordBudget = MAX_PREFIX_WORDS
    var chunkIndex = 0
    for (sc in chunks) {
      if (wordBudget <= 0) break
      val words = sc.chunk.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
      val truncated = words.size > wordBudget
      val keep = if (truncated) words.subList(0, wordBudget) else words
      val body = keep.joinToString(" ")
      val parentBody = if (truncated) "$body ..." else body

      val chunkLetter = 'A' + (chunkIndex % 26)

      // Open <excerpt> — no tag attr (tags are on individual <s> elements now).
      sb.append("<excerpt")
      if (emitPageAttr) {
        val pageAttr =
          if (sc.chunk.pageStart == sc.chunk.pageEnd) sc.chunk.pageStart.toString()
          else "${sc.chunk.pageStart}-${sc.chunk.pageEnd}"
        sb.append(" page=\"").append(pageAttr).append("\"")
      }
      sb.append(">\n")

      // Split the (possibly truncated) body into sentences.
      val sentenceRanges = SentenceSplitter.split(parentBody)

      if (sentenceRanges.isEmpty()) {
        // Degenerate: no sentence boundaries found (extremely short chunk or all
        // whitespace). Wrap the entire body as a single sentence tag.
        val tag = "[X${chunkLetter}A]"
        validTags.add(tag)
        detailsByTag[tag] = ExcerptDetails(
          parentBody = parentBody,
          sentenceRange = 0 until parentBody.length,
          source = docName,
          pageStart = sc.chunk.pageStart,
          pageEnd = sc.chunk.pageEnd,
        )
        sb.append("<s tag=\"").append(tag).append("\">")
        sb.append(parentBody)
        sb.append("</s>\n")
      } else {
        // Clamp to MAX_SENTENCES_PER_CHUNK. Sentences beyond the cap are merged into the
        // last tag's range.
        val effectiveRanges = if (sentenceRanges.size <= MAX_SENTENCES_PER_CHUNK) {
          sentenceRanges
        } else {
          Log.w(
            TAG,
            "Chunk $chunkIndex has ${sentenceRanges.size} sentences; " +
              "merging ${sentenceRanges.size - MAX_SENTENCES_PER_CHUNK + 1} into tag [X${chunkLetter}Z]",
          )
          val capped = sentenceRanges.subList(0, MAX_SENTENCES_PER_CHUNK - 1).toMutableList()
          // Merge the remaining sentences into a single range.
          val mergedStart = sentenceRanges[MAX_SENTENCES_PER_CHUNK - 1].first
          val mergedEnd = sentenceRanges.last().last
          capped.add(mergedStart..mergedEnd)
          capped
        }

        for ((sentIdx, range) in effectiveRanges.withIndex()) {
          val sentLetter = 'A' + sentIdx
          val tag = "[X${chunkLetter}${sentLetter}]"
          validTags.add(tag)
          detailsByTag[tag] = ExcerptDetails(
            parentBody = parentBody,
            sentenceRange = range,
            source = docName,
            pageStart = sc.chunk.pageStart,
            pageEnd = sc.chunk.pageEnd,
          )
          val sentText = parentBody.substring(range.first, range.last + 1)
          sb.append("<s tag=\"").append(tag).append("\">")
          sb.append(sentText)
          sb.append("</s>\n")
        }
      }

      sb.append("</excerpt>\n")
      wordBudget -= keep.size
      chunkIndex++
    }
    sb.append("</knowledge_base>\n")
    return FormattedContext(
      text = sb.toString(),
      validExcerptTags = validTags,
      detailsByTag = detailsByTag,
    )
  }

  /** Minimal XML attribute escaping for the doc-name attribute value. */
  private fun escapeAttr(s: String): String =
    s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
