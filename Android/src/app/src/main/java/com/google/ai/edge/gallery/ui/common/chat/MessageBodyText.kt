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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.MarkdownText

/**
 * Builds an [AnnotatedString] from [content] with each range in [unverifiedRanges] styled
 * with a red underline. Used to visually flag numeric spans that the model produced but
 * which do NOT appear verbatim in the retrieved context snapshot (likely hallucinated
 * digits). Ranges outside [content] bounds are silently skipped.
 */
private fun buildAnnotatedWithUnverifiedNumbers(
  content: String,
  unverifiedRanges: List<IntRange>,
): AnnotatedString = buildAnnotatedString {
  append(content)
  val style = SpanStyle(color = Color.Red, textDecoration = TextDecoration.Underline)
  for (range in unverifiedRanges) {
    val start = range.first
    val end = range.last + 1
    if (start in 0..content.length && end in 0..content.length && start < end) {
      addStyle(style, start, end)
    }
  }
}

/** Composable function to display the text content of a ChatMessageText. */
@Composable
fun MessageBodyText(message: ChatMessageText, inProgress: Boolean) {
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
      // When the post-generation number verifier has flagged any numeric spans as not
      // present in the retrieved context, render as plain Text with red-underlined spans
      // so the user can see exactly which digits were not grounded. This deliberately
      // bypasses the markdown renderer (which cannot apply per-character span styles)
      // and is safe because the system prompt restricts answers to ASCII-only output.
      if (message.isMarkdown && unverified.isEmpty()) {
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
        val annotated =
          remember(message.content, unverified) {
            buildAnnotatedWithUnverifiedNumbers(message.content, unverified)
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
