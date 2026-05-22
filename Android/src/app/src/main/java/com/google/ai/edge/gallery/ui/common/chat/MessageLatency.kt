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

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import com.google.ai.edge.gallery.ui.common.humanReadableDuration

/** Composable function to display the latency of a chat message, if available. */
@Composable
fun LatencyText(message: ChatMessage) {
  if (message.latencyMs >= 0) {
    Text(
      message.latencyMs.humanReadableDuration(),
      modifier = Modifier.alpha(0.5f).testTag("latency_label"),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/**
 * Composable that displays the running context-token estimate for a chat message, if known
 * ([ChatMessage.tokenCount] >= 0). The number shown is the estimated total number of tokens
 * occupied in the LiteRT-LM conversation context at the moment this message becomes part of
 * the prefill (i.e. system prompt + all prior retained turns + this message itself, plus the
 * current RAG prefix for user turns when RAG is active).
 */
@Composable
fun ContextTokenText(message: ChatMessage) {
  if (message.tokenCount >= 0) {
    Text(
      "${message.tokenCount} ctx tok",
      modifier = Modifier.alpha(0.5f).testTag("context_token_label"),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

/**
 * Composable that displays the process RAM usage (total PSS) sampled at the moment this
 * message was stamped. Hidden when [ChatMessage.memoryBytes] is negative (unknown).
 */
@Composable
fun MemoryText(message: ChatMessage) {
  val bytes = message.memoryBytes
  if (bytes >= 0L) {
    val mb = bytes / (1024L * 1024L)
    Text(
      "$mb MB",
      modifier = Modifier.alpha(0.5f).testTag("memory_label"),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun LatencyTextPreview() {
//   GalleryTheme {
//     Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp))
// {
//       for (latencyMs in listOf(123f, 1234f, 123456f, 7234567f)) {
//         LatencyText(
//           message =
//             ChatMessage(latencyMs = latencyMs, type = ChatMessageType.TEXT, side =
// ChatSide.AGENT)
//         )
//       }
//     }
//   }
// }
