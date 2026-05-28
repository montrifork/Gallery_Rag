/*
 * Copyright 2025 Google LLC
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

// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
// import androidx.compose.ui.tooling.preview.Preview

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.MarkdownText

/**
 * Canonical sentence-citation shape: `[X` + two uppercase letters + `]`. Matches the
 * three-letter tag scheme allocated by `ContextFormatter` (chunk letter + sentence letter).
 * The `X` prefix is reserved in `NumberTagger` so these never collide with number tags.
 */
private val CITATION_REGEX = Regex("""\[X[A-Z][A-Z]]""")

/**
 * Builds an [AnnotatedString] from [content] that:
 *
 *  - Red-underlines every range in [unverifiedRanges]. These are verifier-flagged spans —
 *    invalid citations, unmapped digits, deformed tags — that the user should treat with
 *    suspicion.
 *  - For every well-formed `[X?]` excerpt-citation in [content] whose tag is present in
 *    [validCitationTags] AND whose range is NOT itself a verifier flag, wraps the tag in
 *    a clickable [LinkAnnotation] so the consumer can show the underlying excerpt.
 *
 * Tags absent from [validCitationTags] are left unstyled here; they are typically already
 * red-underlined via [unverifiedRanges] from the verifier's invalid-citation pass. This
 * way we never make an invalid citation tappable.
 */
private fun buildAnnotatedAnswer(
  content: String,
  unverifiedRanges: List<IntRange>,
  validCitationTags: Set<String>,
  citationLinkColor: Color,
  onCitationClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
  append(content)
  // 1. Red-underline verifier-flagged ranges first so we can use them to suppress link
  // styling on overlapping citations below.
  val redStyle = SpanStyle(color = Color.Red, textDecoration = TextDecoration.Underline)
  for (range in unverifiedRanges) {
    val start = range.first
    val end = range.last + 1
    if (start in 0..content.length && end in 0..content.length && start < end) {
      addStyle(redStyle, start, end)
    }
  }
  // 2. Add clickable links for valid citations. We skip any citation whose range is
  // fully covered by a verifier-flagged range — those stay red and inert.
  if (validCitationTags.isNotEmpty()) {
    val linkStyle =
      SpanStyle(color = citationLinkColor, textDecoration = TextDecoration.Underline)
    val linkStyles = TextLinkStyles(style = linkStyle)
    for (m in CITATION_REGEX.findAll(content)) {
      val tag = m.value
      if (tag !in validCitationTags) continue
      val start = m.range.first
      val end = m.range.last + 1
      val overlapsFlag =
        unverifiedRanges.any { r ->
          val rStart = r.first
          val rEnd = r.last + 1
          rStart <= start && rEnd >= end
        }
      if (overlapsFlag) continue
      val link =
        LinkAnnotation.Clickable(
          tag = tag,
          styles = linkStyles,
          linkInteractionListener =
            LinkInteractionListener { onCitationClick(tag) },
        )
      addLink(link, start, end)
    }
  }
}

/**
 * Composable function to display the text content of a ChatMessageText.
 *
 * [onCitationClick] is invoked when the user taps an excerpt-citation `[X?]` in the
 * rendered answer that maps to a known excerpt on the message's `excerptDetails`. The
 * caller is expected to show the corresponding excerpt body (e.g. via a bottom sheet).
 * Default no-op preserves existing call sites that do not yet wire up the sheet.
 */
@Composable
fun MessageBodyText(
  message: ChatMessageText,
  inProgress: Boolean,
  onCitationClick: (String) -> Unit = {},
) {
  SelectionContainer {
    if (message.side == ChatSide.USER) {
      MarkdownText(
        text = message.content,
        modifier = Modifier.padding(12.dp),
        textColor = Color.White,
        linkColor = Color.White,
      )
    } else if (message.side == ChatSide.AGENT) {
      val cdResponse = stringResource(R.string.cd_model_response_text)
      val unverified = message.unverifiedNumberRanges
      val validCitationTags: Set<String> = message.excerptDetails?.keys.orEmpty()
      // When the post-generation number verifier has flagged any numeric spans, OR when
      // there are excerpt citations to make clickable, render as plain Text with custom
      // span styling so we can apply per-character color, underline, and link
      // annotations. This bypasses the markdown renderer (which cannot apply per-range
      // span styles) and is safe because the system prompt restricts answers to ASCII
      // prose with no markdown.
      val needsAnnotated = unverified.isNotEmpty() || validCitationTags.isNotEmpty()
      if (message.isMarkdown && !needsAnnotated) {
        MarkdownText(
          text = message.content,
          modifier =
            Modifier.padding(12.dp).semantics(mergeDescendants = true) {
              contentDescription = cdResponse
              // Only announce when message is complete.
              if (!inProgress) {
                liveRegion = LiveRegionMode.Polite
              }
            },
        )
      } else {
        val linkColor = MaterialTheme.colorScheme.primary
        val annotated =
          remember(message.content, unverified, validCitationTags, linkColor) {
            buildAnnotatedAnswer(
              content = message.content,
              unverifiedRanges = unverified,
              validCitationTags = validCitationTags,
              citationLinkColor = linkColor,
              onCitationClick = onCitationClick,
            )
          }
        Text(
          annotated,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier =
            Modifier.padding(12.dp).semantics {
              contentDescription = cdResponse
              // Only announce when message is complete.
              if (!inProgress) {
                liveRegion = LiveRegionMode.Polite
              }
            },
        )
      }
    }
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageBodyTextPreview() {
//   GalleryTheme {
//     Column {
//       Row(modifier = Modifier.padding(16.dp).background(MaterialTheme.colorScheme.primary)) {
//         MessageBodyText(ChatMessageText(content = "Hello world", side = ChatSide.USER))
//       }
//       Row(
//         modifier = Modifier.padding(16.dp).background(MaterialTheme.colorScheme.surfaceContainer)
//       ) {
//         MessageBodyText(ChatMessageText(content = "yes hello world", side = ChatSide.AGENT))
//       }
//     }
//   }
// }
