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

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts plain text from a Markdown (`.md`) [Uri]. */
interface MarkdownTextExtractor {
  /**
   * @throws RagError.FileOpenFailed if the [Uri] cannot be opened.
   * @throws RagError.NotAMarkdownFile if the content cannot be decoded as UTF-8 text.
   */
  suspend fun extract(uri: Uri): List<PageText>
}

/**
 * Reads a markdown file from a [Uri] as UTF-8, applies a light syntactic strip pass (headers,
 * emphasis, links, code-fence markers, table pipes, blockquote / bullet markers), and returns
 * the result as a single synthetic "page". The downstream [Chunker] is content-agnostic so the
 * one-page representation is sufficient.
 */
class DefaultMarkdownTextExtractor(private val context: Context) : MarkdownTextExtractor {

  override suspend fun extract(uri: Uri): List<PageText> = withContext(Dispatchers.IO) {
    val raw = try {
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw RagError.FileOpenFailed(IllegalStateException("openInputStream returned null"))
    } catch (e: CancellationException) {
      throw e
    } catch (e: SecurityException) {
      throw RagError.FileOpenFailed(e)
    } catch (e: java.io.IOException) {
      throw RagError.FileOpenFailed(e)
    }

    val text = try {
      String(raw, Charsets.UTF_8)
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to decode bytes as UTF-8", t)
      throw RagError.NotAMarkdownFile(t)
    }

    val stripped = stripMarkdown(text)
    listOf(PageText(page = 1, text = stripped))
  }

  companion object {
    private const val TAG = "AGMarkdownTextExtractor"

    /**
     * Light regex-based markdown stripper. Goal: produce plain text suitable for BM25 indexing
     * and as LLM-visible context. We deliberately keep the *content* of code fences (the code
     * itself is often the most informative part of a doc) but drop the fence markers.
     *
     * Not a full CommonMark parser; handles the 90% case for hand-written knowledge-base
     * markdown. Operates line-by-line to keep things predictable.
     */
    internal fun stripMarkdown(input: String): String {
      val out = StringBuilder(input.length)
      var inFence = false
      for (rawLine in input.lineSequence()) {
        var line = rawLine

        // Fenced code blocks: drop the fence markers, keep their content lines verbatim.
        val fenceMatch = FENCE.matchEntire(line.trimStart())
        if (fenceMatch != null) {
          inFence = !inFence
          continue
        }
        if (inFence) {
          out.append(line).append('\n')
          continue
        }

        // ATX headings: strip leading hashes and trailing hashes.
        line = HEADING.replace(line) { it.groupValues[1] }

        // Blockquote markers.
        line = BLOCKQUOTE.replace(line, "")

        // List bullets (unordered and ordered).
        line = UNORDERED_BULLET.replace(line, "")
        line = ORDERED_BULLET.replace(line, "")

        // Images: keep alt text only. Must run before links so the leading `!` is consumed.
        line = IMAGE.replace(line) { it.groupValues[1] }

        // Links: keep the link text.
        line = LINK.replace(line) { it.groupValues[1] }

        // Bold / italic / strikethrough / inline code: keep inner text.
        line = BOLD_STAR.replace(line) { it.groupValues[1] }
        line = BOLD_UNDER.replace(line) { it.groupValues[1] }
        line = ITALIC_STAR.replace(line) { it.groupValues[1] }
        line = ITALIC_UNDER.replace(line) { it.groupValues[1] }
        line = STRIKE.replace(line) { it.groupValues[1] }
        line = INLINE_CODE.replace(line) { it.groupValues[1] }

        // Table pipes -> spaces; drop pure separator rows (e.g. `|---|---|`).
        if (TABLE_SEPARATOR.matches(line.trim())) {
          continue
        }
        line = line.replace('|', ' ')

        // Horizontal rules: drop entirely.
        if (HRULE.matches(line.trim())) continue

        // Collapse runs of whitespace introduced by the substitutions above.
        line = MULTI_SPACE.replace(line, " ").trimEnd()

        out.append(line).append('\n')
      }
      // Collapse 3+ blank lines into 2.
      return out.toString().replace(BLANK_RUN, "\n\n").trim()
    }

    private val FENCE = Regex("^(```|~~~).*")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val BLOCKQUOTE = Regex("^\\s{0,3}>+\\s?")
    private val UNORDERED_BULLET = Regex("^\\s*[-*+]\\s+")
    private val ORDERED_BULLET = Regex("^\\s*\\d+\\.\\s+")
    private val IMAGE = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val BOLD_STAR = Regex("\\*\\*([^*]+)\\*\\*")
    private val BOLD_UNDER = Regex("__([^_]+)__")
    private val ITALIC_STAR = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
    private val ITALIC_UNDER = Regex("(?<!_)_([^_\\n]+)_(?!_)")
    private val STRIKE = Regex("~~([^~]+)~~")
    private val INLINE_CODE = Regex("`([^`\\n]+)`")
    private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")
    private val HRULE = Regex("^(\\*\\s*){3,}$|^(-\\s*){3,}$|^(_\\s*){3,}$")
    private val MULTI_SPACE = Regex(" {2,}")
    private val BLANK_RUN = Regex("\\n{3,}")
  }
}
