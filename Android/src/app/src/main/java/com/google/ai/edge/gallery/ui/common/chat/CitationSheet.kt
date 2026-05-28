/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.rag.ContextFormatter

/**
 * Contents shown inside the modal bottom sheet that appears when the user taps a
 * sentence citation like `[XAC]` in an agent message. Renders:
 *
 *   - The tag itself (so the user can correlate with what they tapped).
 *   - The source filename and page range (when meaningful).
 *   - The full parent excerpt body with the cited sentence **highlighted** via bold text
 *     and a primary-container background color. This gives the user pinpoint visibility
 *     into which sentence the model claims supports its answer, while still showing
 *     surrounding context for verification.
 *
 * A `null` [details] argument renders a graceful fallback.
 */
@Composable
fun CitationSheetContent(
  tag: String,
  details: ContextFormatter.ExcerptDetails?,
  modifier: Modifier = Modifier,
) {
  val highlightColor = MaterialTheme.colorScheme.primaryContainer
  val highlightTextColor = MaterialTheme.colorScheme.onPrimaryContainer

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Header: tag in monospace + source filename.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = tag,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
      if (details != null) {
        Text(
          text = details.source,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pageLine = formatPageLine(details.pageStart, details.pageEnd)
        if (pageLine != null) {
          Text(
            text = pageLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    HorizontalDivider()
    // Body: the full parent excerpt with the cited sentence highlighted.
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.verticalScroll(scrollState)) {
      if (details != null) {
        val annotated = remember(details.parentBody, details.sentenceRange, highlightColor) {
          buildAnnotatedString {
            append(details.parentBody)
            val start = details.sentenceRange.first
            val end = (details.sentenceRange.last + 1).coerceAtMost(details.parentBody.length)
            if (start in 0 until details.parentBody.length && start < end) {
              addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Bold,
                  background = highlightColor,
                  color = highlightTextColor,
                ),
                start,
                end,
              )
            }
          }
        }
        Text(
          text = annotated,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      } else {
        Text(
          text = "(This citation does not refer to a retrieved excerpt.)",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
  }
}

/**
 * Formats the page line shown beneath the source filename. Returns null when the page
 * information is uninformative (markdown sources synthesize page 1 for everything).
 */
private fun formatPageLine(pageStart: Int, pageEnd: Int): String? {
  if (pageStart == 1 && pageEnd == 1) return null
  return if (pageStart == pageEnd) "Page $pageStart" else "Pages $pageStart\u2013$pageEnd"
}
