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

package com.google.ai.edge.gallery.ui.llmchat

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import android.os.Process as AndroidProcess
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.rag.RagRepository
import com.google.ai.edge.gallery.data.rag.RagState
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

/**
 * Fixed system instruction for the on-device insurance assistant. This string is passed to the
 * LiteRT-LM engine via [com.google.ai.edge.litertlm.ConversationConfig.systemInstruction]. The
 * engine prefills it ONCE per [com.google.ai.edge.litertlm.Conversation] construction; it is NOT
 * re-sent or appended for every user turn within the same conversation, and it is NEVER stored
 * in the shadow history that seeds rebuilt conversations as `initialMessages`. Net cost is a
 * single fixed prefill (~340 tokens) per conversation creation — no incremental growth.
 *
 * ASCII-only by design; the prompt itself instructs the model to emit ASCII-only output.
 *
 * The prompt is structured as numbered, named rules to maximize per-rule salience on small
 * instruction-tuned models like Gemma 3 / 3n / 4. Quote enforcement is paired with concrete
 * good / bad examples because few-shot anchoring is dramatically more reliable than abstract
 * rules for sub-4B parameter models. The quote rule is unconditional (not gated on "medical
 * question") so the model does not need to classify the question first.
 */
const val LLM_CHAT_DEFAULT_SYSTEM_PROMPT: String =
  "You are an on-device assistant. The user turn contains:\n" +
    "- <knowledge_base source=\"...\"> with one or more <excerpt index=\"N\"> blocks " +
    "(the only source of truth).\n" +
    "- <user_question> with the question to answer.\n" +
    "\n" +
    "Rules:\n" +
    "1. Answer ONLY from <excerpt> content. Never use prior turns or outside knowledge as facts.\n" +
    "2. ASCII only. No markdown, no emoji.\n" +
    "3. For any number, dose, age, percentage, timeframe, or amount: copy the digits " +
    "character-for-character from an <excerpt>, inside ASCII double quotes. Never restate, " +
    "round, or paraphrase a number.\n" +
    "4. 2-5 sentences. No disclaimers.\n" +
    "5. If no <excerpt> answers the question, reply EXACTLY:\n" +
    "   I do not have the knowledge to answer this question. Can you try to reformulate?\n" +
    "6. Never output the tag names themselves.\n" +
    "\n" +
    "Example:\n" +
    "<knowledge_base source=\"plan.md\"><excerpt index=\"1\">Dental benefits begin after a " +
    "waiting period of 6 months.</excerpt></knowledge_base>\n" +
    "<user_question>How long is the dental waiting period?</user_question>\n" +
    "The plan states \"a waiting period of 6 months\" before dental benefits begin."

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase() : ChatViewModel() {
  open fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
      }

      var firstRun = true
      val start = System.currentTimeMillis()

      try {
        val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            if (partialResult.startsWith("<ctrl")) {
              // Do nothing. Ignore control tokens.
            } else {
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = thinkingText != null && thinkingText.isNotEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
                if (partialResult.isNotEmpty() || wasLoading || done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = partialResult,
                    latencyMs = latencyMs.toFloat(),
                  )
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = finalLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                setInProgress(false)
                onDone()
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          setInProgress(false)
          setPreparing(false)
        }

        val errorListener: (String) -> Unit = { message ->
          Log.e(TAG, "Error occurred while running inference")
          setInProgress(false)
          setPreparing(false)
          onError(message)
        }

        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        model.runtimeHelper.runInference(
          model = model,
          input = input,
          images = images,
          audioClips = audioClips,
          resultListener = resultListener,
          cleanUpListener = cleanUpListener,
          onError = errorListener,
          coroutineScope = viewModelScope,
          extraContext = extraContext,
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError(e.message ?: "")
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
  }

  open fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      while (true) {
        try {
          model.runtimeHelper.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemInstruction = systemInstruction,
            tools = tools,
            enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          )
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  open fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(context = context, task = task, model = model)

          // Add a warning message for re-initializing the session.
          addMessage(
            model = model,
            message = ChatMessageWarning(content = "Session re-initialized"),
          )
        },
      )
    }
  }
}

@HiltViewModel
class LlmChatViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val ragRepository: RagRepository,
  private val dataStoreRepository: DataStoreRepository,
) : LlmChatViewModelBase() {

  val ragState: StateFlow<RagState> = ragRepository.state

  val ragEnabled: StateFlow<Boolean> =
    dataStoreRepository.ragEnabledFlow().stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = dataStoreRepository.getRagEnabled(),
    )

  init {
    // Bootstrap the bundled default knowledge base on first ever launch so users have a
    // working RAG demo without needing to attach anything. Idempotent across launches.
    // NOTE: the DataStore key is historically named `defaultPdfIngested`; we keep that name
    // to avoid a proto migration. Semantically it means "default KB ingested".
    if (!dataStoreRepository.getDefaultPdfIngested() && ragState.value is RagState.Empty) {
      viewModelScope.launch {
        val result = ragRepository.ingestAsset(DEFAULT_KB_ASSET)
        if (result.isSuccess) {
          dataStoreRepository.setDefaultPdfIngested(true)
          // Auto-enable RAG so the default KB is actually used until the user toggles it off.
          dataStoreRepository.setRagEnabled(true)
        } else {
          Log.w(TAG, "Failed to auto-ingest default knowledge base", result.exceptionOrNull())
        }
      }
    }
  }

  fun setRagEnabled(enabled: Boolean) {
    dataStoreRepository.setRagEnabled(enabled)
  }

  fun ingestMarkdown(uri: Uri, onError: (String) -> Unit = {}) {
    viewModelScope.launch {
      val result = ragRepository.ingestMarkdown(uri)
      result.exceptionOrNull()?.let { e ->
        onError(e.message ?: "Failed to ingest Markdown file")
      }
    }
  }

  fun clearRag() {
    viewModelScope.launch {
      ragRepository.clear()
    }
  }

  /**
   * Per-model shadow history of CLEAN turns (user question + model answer) used to seed the
   * underlying LiteRT-LM `Conversation` via `ConversationConfig.initialMessages` on every RAG
   * turn. By "clean" we mean: the user message stored here is the original question WITHOUT the
   * retrieved RAG context prefix. This lets the model retain conversational memory across turns
   * while ensuring stale RAG prefixes never accumulate in the native KV cache.
   *
   * Cap on retained turns prevents unbounded prefill latency on very long sessions.
   */
  private val ragShadowHistory: MutableMap<String, MutableList<Message>> = mutableMapOf()

  /**
   * Drops the shadow history for [model]. Called when the engine has been (re)initialized or the
   * user explicitly resets the session, so the next RAG turn starts from a clean slate.
   */
  fun clearRagShadowHistory(model: Model) {
    synchronized(ragShadowHistory) { ragShadowHistory.remove(model.name) }
  }

  private fun shadowHistoryFor(model: Model): MutableList<Message> =
    synchronized(ragShadowHistory) {
      ragShadowHistory.getOrPut(model.name) { mutableListOf() }
    }

  private fun snapshotShadowHistory(model: Model): List<Message> =
    synchronized(ragShadowHistory) {
      ragShadowHistory[model.name]?.toList() ?: emptyList()
    }

  private fun appendShadowTurn(model: Model, userText: String, modelText: String) {
    if (modelText.isEmpty()) return
    val history = shadowHistoryFor(model)
    synchronized(ragShadowHistory) {
      history.add(Message.user(userText))
      history.add(Message.model(modelText))
      // Bound the history so prefill cost stays predictable.
      while (history.size > MAX_SHADOW_MESSAGES) {
        history.removeAt(0)
      }
    }
  }

  /**
   * Pure formatter: given the exact pieces of a turn (already computed), produces the
   * human-readable debug snapshot string. Used both at generation time (to stamp each AGENT
   * message with what was actually sent) and by the live "Copy model context (debug)" menu
   * action (to preview what would be sent on the next turn).
   *
   * Passing `rawHistorySize == trimmedHistory.size` and `rawPrefixChars == trimmedPrefix.length`
   * is fine if the caller didn't track the pre-trim sizes — those lines will just say
   * "N stored, N replayed".
   */
  private fun formatContextSnapshot(
    model: Model,
    ragOn: Boolean,
    rawPrefix: String,
    trimmedPrefix: String,
    rawHistorySize: Int,
    trimmedHistory: List<Message>,
    finalContextTokens: Int,
    didTrim: Boolean,
    wrappedInput: String,
  ): String {
    val sb = StringBuilder()
    sb.append("=== MODEL CONTEXT SNAPSHOT (debug) ===\n")
    sb.append("Mode: ").append(if (ragOn) "RAG" else "non-RAG").append("\n")
    sb.append("Hard cap (maxNumTokens): ").append(hardCapFor(model)).append("\n")
    sb.append("Effective cap (cap - reserve): ")
      .append(hardCapFor(model) - RESERVE_FOR_ANSWER).append("\n")
    sb.append("Estimated prefill tokens (post-trim, +10% safety): ")
      .append(finalContextTokens).append("\n")
    sb.append("History: ").append(rawHistorySize)
      .append(" stored, ").append(trimmedHistory.size).append(" replayed\n")
    if (ragOn) {
      sb.append("RAG prefix: ").append(rawPrefix.length)
        .append(" raw chars, ").append(trimmedPrefix.length).append(" post-trim chars\n")
    }
    sb.append("Trimmer engaged: ").append(didTrim).append("\n")
    sb.append("\n--- [1] SYSTEM INSTRUCTION (prefilled once per Conversation) ---\n")
    sb.append(LLM_CHAT_DEFAULT_SYSTEM_PROMPT).append("\n")
    sb.append("\n--- [2] REPLAYED HISTORY (")
      .append(trimmedHistory.size).append(" messages, oldest first) ---\n")
    if (trimmedHistory.isEmpty()) {
      sb.append("(none)\n")
    } else {
      for ((i, msg) in trimmedHistory.withIndex()) {
        sb.append("[").append(i).append("] ").append(msg.toString()).append("\n")
      }
    }
    sb.append("\n--- [3] CURRENT USER TURN (sent as a single user message) ---\n")
    if (ragOn) {
      sb.append(trimmedPrefix)
    }
    sb.append(wrappedInput).append("\n")
    sb.append("\n=== END SNAPSHOT ===\n")
    return sb.toString()
  }

  /**
   * Debug helper: returns a human-readable reconstruction of EXACTLY what the engine would
   * receive as prefill on the next turn, given the current draft [currentInput]. This mirrors
   * the real generation pipeline:
   *
   *   1. System instruction (always prefilled by the engine).
   *   2. Replayed shadow history (post-trimmer; oldest-first, may be reduced by the budget).
   *   3. If RAG is enabled and indexed: the retrieved <knowledge_base> block for the current
   *      input.
   *   4. The wrapped <user_question> carrying the current input.
   *
   * Suspending because RAG retrieval is suspending. Safe to call off the main thread.
   * Pure-read — does not mutate any state, does not touch the engine, does not consume any
   * shadow-history budget.
   */
  suspend fun snapshotContextForDebug(model: Model, currentInput: String): String {
    val ragOn = ragEnabled.value && ragState.value is RagState.Ready
    val rawPrefix: String =
      if (ragOn) {
        try {
          val scored = ragRepository.retrieve(currentInput, k = 4)
          ragRepository.formatContext(scored)
        } catch (t: Throwable) {
          Log.w(TAG, "snapshotContextForDebug: retrieval failed", t)
          "(RAG retrieval failed: ${t.message})\n"
        }
      } else {
        ""
      }
    val rawHistory = snapshotShadowHistory(model)
    val budget =
      computeTokenBudget(
        model = model,
        ragPrefix = rawPrefix,
        userInput = currentInput,
        history = rawHistory,
      )
    val wrappedInput = "<user_question>$currentInput</user_question>"
    return formatContextSnapshot(
      model = model,
      ragOn = ragOn,
      rawPrefix = rawPrefix,
      trimmedPrefix = budget.trimmedPrefix,
      rawHistorySize = rawHistory.size,
      trimmedHistory = budget.trimmedHistory,
      finalContextTokens = budget.finalContextTokens,
      didTrim = budget.didTrim,
      wrappedInput = wrappedInput,
    )
  }

  // --- Context-token bookkeeping for the in-UI counter ----------------------------------
  //
  // For the RAG path the underlying Conversation is rebuilt per turn, so the prefill is
  // fully determined by what we pass as systemInstruction + initialMessages + the current
  // turn's content. For the non-RAG path the Conversation persists and grows monotonically;
  // we maintain a parallel running estimate per model so the UI can still show a number.
  //
  // All values are cheap heuristic estimates (1 token ≈ 4 chars, plus a few tokens of
  // overhead per message for chat-template markers). This is intentionally approximate —
  // the goal is to give the user a sense of how full the context is, not to be exact.

  private val nonRagRunningTokens: MutableMap<String, Int> = mutableMapOf()

  private fun resetNonRagRunningTokensFor(model: Model) {
    synchronized(nonRagRunningTokens) { nonRagRunningTokens.remove(model.name) }
  }

  private fun estimateTextTokens(text: String): Int {
    // ~4 chars/token for typical English; +4 for role/turn markers a chat template injects.
    if (text.isEmpty()) return 0
    return text.length / 4 + 4
  }

  private fun estimateMessageTokens(msg: Message): Int = estimateTextTokens(msg.toString())

  /**
   * Sums the prefill-time token cost of the persistent prompt + the supplied shadow history.
   * Excludes the current turn's user input and RAG prefix — those are added separately by
   * the caller depending on whether the turn went through the RAG branch.
   */
  private fun estimateBaseContextTokens(history: List<Message>): Int {
    val systemTokens = estimateTextTokens(LLM_CHAT_DEFAULT_SYSTEM_PROMPT)
    val historyTokens = history.sumOf { estimateMessageTokens(it) }
    return systemTokens + historyTokens
  }

  /** Multiplies an estimate by the safety factor, rounding up. */
  private fun withSafetyFactor(tokens: Int): Int =
    kotlin.math.ceil(tokens * TOKEN_ESTIMATE_SAFETY_FACTOR).toInt()

  /**
   * Returns the live hard token cap for [model], read from the per-model `maxNumTokens` config
   * slider value. Keeps the trimmer in lockstep with whatever the user has selected.
   */
  private fun hardCapFor(model: Model): Int =
    model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = 8000)

  /**
   * Result of a token-budget trim: what the caller should actually send to the engine.
   * [finalContextTokens] is the post-safety-factor estimate suitable for UI display.
   */
  private data class BudgetResult(
    val trimmedPrefix: String,
    val trimmedHistory: List<Message>,
    val finalContextTokens: Int,
    val didTrim: Boolean,
  )

  /**
   * Enforces the per-turn token budget: `hardCap - RESERVE_FOR_ANSWER` is the effective prefill
   * ceiling. Trim priority (high to low): drop oldest shadow history → truncate RAG prefix from
   * the tail → never touch user input or system prompt. Estimates use a 10% safety factor.
   */
  private fun computeTokenBudget(
    model: Model,
    ragPrefix: String,
    userInput: String,
    history: List<Message>,
  ): BudgetResult {
    val hardCap = hardCapFor(model)
    val effectiveCap = hardCap - RESERVE_FOR_ANSWER

    val systemTokens = withSafetyFactor(estimateTextTokens(LLM_CHAT_DEFAULT_SYSTEM_PROMPT))
    val inputTokens = withSafetyFactor(estimateTextTokens(userInput))
    var prefixTokens = withSafetyFactor(estimateTextTokens(ragPrefix))

    var trimmedPrefix = ragPrefix
    var didTrim = false

    // Degenerate case: system + input alone overflow. Drop prefix and history entirely.
    if (systemTokens + inputTokens >= effectiveCap) {
      Log.w(
        TAG,
        "Token budget exhausted by system+input ($systemTokens + $inputTokens) under cap " +
          "$effectiveCap; dropping RAG prefix and history",
      )
      return BudgetResult(
        trimmedPrefix = "",
        trimmedHistory = emptyList(),
        finalContextTokens = systemTokens + inputTokens,
        didTrim = true,
      )
    }

    // If prefix alone doesn't fit, truncate it word-by-word from the tail.
    if (systemTokens + inputTokens + prefixTokens > effectiveCap) {
      val available = effectiveCap - systemTokens - inputTokens
      // Convert token budget back to a character budget using the same heuristic (4 chars/tok,
      // minus the +4 overhead and the safety factor) — be conservative.
      val charBudget = ((available / TOKEN_ESTIMATE_SAFETY_FACTOR).toInt() - 4) * 4
      trimmedPrefix = if (charBudget <= 0) "" else ragPrefix.take(charBudget)
      prefixTokens = withSafetyFactor(estimateTextTokens(trimmedPrefix))
      didTrim = true
      Log.d(TAG, "Trimmed RAG prefix to fit budget: $charBudget chars, ~$prefixTokens tok")
    }

    // Fill remaining budget with shadow history, newest-first, preserving turn boundaries.
    val remaining = effectiveCap - systemTokens - inputTokens - prefixTokens
    val keptReversed = mutableListOf<Message>()
    var runningHistoryTokens = 0
    // Walk newest-first.
    for (msg in history.asReversed()) {
      val cost = withSafetyFactor(estimateMessageTokens(msg))
      if (runningHistoryTokens + cost > remaining) break
      keptReversed.add(msg)
      runningHistoryTokens += cost
    }
    var kept = keptReversed.asReversed().toMutableList()

    // Preserve turn boundaries: if the oldest kept message is a model reply, drop it so we
    // don't start the replay with a stranded assistant turn.
    if (kept.isNotEmpty() && kept.first().toString().contains("\"role\":\"model\"", ignoreCase = true)) {
      kept.removeAt(0)
    }

    if (kept.size != history.size) didTrim = true

    val finalTokens = systemTokens + inputTokens + prefixTokens + runningHistoryTokens
    return BudgetResult(
      trimmedPrefix = trimmedPrefix,
      trimmedHistory = kept,
      finalContextTokens = finalTokens,
      didTrim = didTrim,
    )
  }

  /**
   * Samples the current process's total PSS (proportional set size) in bytes. This is the
   * most representative single number for "how much RAM is this app using right now" — it
   * includes native mmap'd model weights (which `Runtime.totalMemory` would miss) and
   * proportionally attributes shared pages.
   *
   * `getProcessMemoryInfo` can take 50–200 ms on some devices; callers must invoke this off
   * the main thread. Returns `-1L` on failure.
   */
  private fun sampleProcessMemoryBytes(): Long {
    return try {
      val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return -1L
      val infos = am.getProcessMemoryInfo(intArrayOf(AndroidProcess.myPid()))
      if (infos.isNotEmpty()) {
        infos[0].totalPss * 1024L
      } else {
        // Fallback: native heap size (cheap, no IPC).
        Debug.getNativeHeapAllocatedSize()
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to sample process memory", t)
      -1L
    }
  }

  /**
   * Sets [ChatMessage.tokenCount] on the most-recent message of [side] and [type] for [model],
   * replacing the message in the UI list so Compose recomposes. Falls back to a direct mutation
   * + a no-op state replacement if no matching message is found.
   */
  private fun stampTokenCount(model: Model, side: ChatSide, type: ChatMessageType, count: Int) {
    val target = getLastMessageWithTypeAndSide(model = model, type = type, side = side) ?: return
    target.tokenCount = count
    target.memoryBytes = sampleProcessMemoryBytes()
    // Force a state-flow emission so Compose observes the mutation. The simplest way is to
    // replace the message with itself in the list (the list reference changes via
    // `replaceLastMessage` which already updates _uiState).
    val last = getLastMessage(model = model)
    if (last === target) {
      replaceLastMessage(model = model, message = target, type = type)
    }
  }

  /**
   * Attaches [snapshot] to the most-recent AGENT TEXT message so the UI's per-message
   * "Copy context" button can retrieve the EXACT prompt that produced this answer. Captures
   * the model's "what did you see?" provenance for debugging. Also computes
   * [ChatMessageText.unverifiedNumberRanges] — numeric spans in the answer that do NOT
   * appear verbatim in [snapshot] — for the red-underline render in MessageBodyText.
   */
  private fun stampDebugContextSnapshot(model: Model, snapshot: String) {
    val target =
      getLastMessageWithTypeAndSide(
        model = model,
        type = ChatMessageType.TEXT,
        side = ChatSide.AGENT,
      ) ?: return
    target.debugContextSnapshot = snapshot
    if (target is ChatMessageText) {
      target.unverifiedNumberRanges = computeUnverifiedNumberRanges(target.content, snapshot)
    }
    val last = getLastMessage(model = model)
    if (last === target) {
      replaceLastMessage(model = model, message = target, type = ChatMessageType.TEXT)
    }
  }

  /**
   * Returns the character ranges in [answer] containing numbers (integers or decimals like
   * "250", "12.5", "1,000") that do NOT appear verbatim as a token in [snapshot]. Strict
   * string match — "250" does not match "250.0". Used to visually flag likely hallucinated
   * numbers in the rendered agent reply.
   */
  private fun computeUnverifiedNumberRanges(answer: String, snapshot: String): List<IntRange> {
    val numberRegex = Regex("""\b\d+(?:[.,]\d+)?\b""")
    val ctxNumbers = numberRegex.findAll(snapshot).map { it.value }.toHashSet()
    return numberRegex.findAll(answer)
      .filter { it.value !in ctxNumbers }
      .map { it.range }
      .toList()
  }


  override fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap>,
    audioMessages: List<ChatMessageAudioClip>,
    onFirstToken: (Model) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
    allowThinking: Boolean,
  ) {
    if (ragEnabled.value && ragState.value is RagState.Ready) {
      // Set busy flags synchronously so a fast double-tap on send is gated immediately,
      // even though retrieval and the actual super-call happen asynchronously below.
      // super.generateResponse will call setInProgress(true)/setPreparing(true) again — these are idempotent.
      setInProgress(true)
      setPreparing(true)
      // Retrieval is suspending; do it in a coroutine then call super.
      viewModelScope.launch(Dispatchers.Default) {
        val scored = ragRepository.retrieve(input, k = 4)
        val rawPrefix = ragRepository.formatContext(scored)
        val rawHistory = snapshotShadowHistory(model)

        // Enforce the per-turn token budget BEFORE building the conversation. Trim order:
        // shadow history (oldest first) → RAG prefix (tail) → never input or system prompt.
        val budget =
          computeTokenBudget(
            model = model,
            ragPrefix = rawPrefix,
            userInput = input,
            history = rawHistory,
          )
        if (budget.didTrim) {
          Log.d(
            TAG,
            "Token-budget trimmer engaged for RAG turn: history ${rawHistory.size}→" +
              "${budget.trimmedHistory.size}, prefix ${rawPrefix.length}→" +
              "${budget.trimmedPrefix.length} chars",
          )
        }
        val prefix = budget.trimmedPrefix
        val history = budget.trimmedHistory
        // Wrap the user's input in a <user_question> fence so the model can unambiguously
        // distinguish the documentation region (inside <knowledge_base>) from the actual
        // question. The system prompt documents both fences and instructs the model never to
        // emit the tag names. This also makes the prompt tail (`</user_question>`) lexical
        // rather than numeric, breaking the digit-bias degeneracy that can collapse Gemma
        // sampling on number-dense KB content.
        val wrappedQuestion = "<user_question>$input</user_question>"
        val prefixed = if (prefix.isEmpty()) wrappedQuestion else prefix + wrappedQuestion

        // CRITICAL: rebuild the underlying Conversation with only the CLEAN prior turns so the
        // KV cache doesn't accumulate stale RAG prefixes from earlier turns. The model still
        // "remembers" prior dialogue because those clean Q/A pairs are seeded via
        // ConversationConfig.initialMessages. Only the *current* turn carries a RAG prefix.
        try {
          model.runtimeHelper.rebuildConversationWithHistory(
            model = model,
            initialMessages = history,
            supportImage = false,
            supportAudio = false,
            systemInstruction = Contents.of(LLM_CHAT_DEFAULT_SYSTEM_PROMPT),
            tools = emptyList(),
            // Constrained decoding enabled on the RAG path to reduce digit-loop
            // degeneracy and tighten numeric copying fidelity from <excerpt> blocks.
            enableConversationConstrainedDecoding = true,
            // Force deterministic decoding (temp=0) for factual lookup; user-set
            // temperature applies only to free-form (non-RAG) chat.
            temperatureOverride = 0.0f,
          )
        } catch (t: Throwable) {
          Log.w(TAG, "rebuildConversationWithHistory failed; proceeding with existing session", t)
        }

        // Stamp the just-added USER message with the post-trim total prefill cost.
        val userContextTokens = budget.finalContextTokens
        // Non-RAG running estimate should track the RAG rebuild as the new baseline, since
        // a subsequent non-RAG turn would inherit the just-rebuilt conversation.
        synchronized(nonRagRunningTokens) {
          nonRagRunningTokens[model.name] = userContextTokens
        }
        stampTokenCount(
          model = model,
          side = ChatSide.USER,
          type = ChatMessageType.TEXT,
          count = userContextTokens,
        )

        // Wrap onDone so we can capture the model's final answer text and append the CLEAN
        // (un-prefixed) user turn + answer to the shadow history.
        val ragRawPrefix = rawPrefix
        val ragTrimmedPrefix = budget.trimmedPrefix
        val ragRawHistorySize = rawHistory.size
        val ragTrimmedHistory = budget.trimmedHistory
        val ragFinalCtxTokens = budget.finalContextTokens
        val ragDidTrim = budget.didTrim
        val ragWrappedQuestion = wrappedQuestion
        val wrappedOnDone: () -> Unit = {
          try {
            val last =
              getLastMessageWithTypeAndSide(
                model = model,
                type = ChatMessageType.TEXT,
                side = ChatSide.AGENT,
              ) as? ChatMessageText
            val answer = last?.content?.trim().orEmpty()
            if (answer.isNotEmpty()) {
              appendShadowTurn(model = model, userText = input, modelText = answer)
            }
            // Agent-side context total = user-turn prefill + generated answer tokens.
            val agentContextTokens = userContextTokens + estimateTextTokens(answer)
            synchronized(nonRagRunningTokens) {
              nonRagRunningTokens[model.name] = agentContextTokens
            }
            // Capture the EXACT context that produced this answer so the user can copy it
            // post-hoc for debugging. Built from the locals captured above — these reflect
            // what was actually sent to the engine on THIS turn (retrieved excerpts, trimmed
            // history, etc.), not a re-retrieval at copy time.
            val snapshot =
              formatContextSnapshot(
                model = model,
                ragOn = true,
                rawPrefix = ragRawPrefix,
                trimmedPrefix = ragTrimmedPrefix,
                rawHistorySize = ragRawHistorySize,
                trimmedHistory = ragTrimmedHistory,
                finalContextTokens = ragFinalCtxTokens,
                didTrim = ragDidTrim,
                wrappedInput = ragWrappedQuestion,
              )
            stampDebugContextSnapshot(model = model, snapshot = snapshot)
            stampTokenCount(
              model = model,
              side = ChatSide.AGENT,
              type = ChatMessageType.TEXT,
              count = agentContextTokens,
            )
          } catch (t: Throwable) {
            Log.w(TAG, "Failed to capture answer for shadow history", t)
          }
          onDone()
        }

        super@LlmChatViewModel.generateResponse(
          model,
          prefixed,
          images,
          audioMessages,
          onFirstToken,
          wrappedOnDone,
          onError,
          allowThinking,
        )
      }
      return
    }

    // Non-RAG path: the Conversation persists across turns and grows monotonically. Update
    // the running estimate to reflect this turn's user input now, and stamp the AGENT
    // message after generation completes. If the running estimate is approaching the live
    // hard cap, transparently rebuild the conversation with a trimmed shadow history so
    // the engine never sees more than the budget allows.
    val baselineBeforeTurn =
      synchronized(nonRagRunningTokens) {
        // First non-RAG turn ever for this model: seed with just the system prompt cost.
        nonRagRunningTokens[model.name] ?: estimateTextTokens(LLM_CHAT_DEFAULT_SYSTEM_PROMPT)
      }
    val projected = baselineBeforeTurn + estimateTextTokens(input)
    val hardCap = hardCapFor(model)
    val effectiveCap = hardCap - RESERVE_FOR_ANSWER

    var userContextTokens = projected
    if (withSafetyFactor(projected) > effectiveCap) {
      // Synchronous rebuild on the calling thread is safe — rebuild closes the old
      // conversation and creates a new one; the subsequent super.generateResponse will
      // then send into the rebuilt conversation.
      val rawHistory = snapshotShadowHistory(model)
      val budget =
        computeTokenBudget(
          model = model,
          ragPrefix = "",
          userInput = input,
          history = rawHistory,
        )
      Log.d(
        TAG,
        "Non-RAG token budget exceeded ($projected > $effectiveCap); rebuilding " +
          "conversation with trimmed history (${rawHistory.size}→${budget.trimmedHistory.size})",
      )
      try {
        model.runtimeHelper.rebuildConversationWithHistory(
          model = model,
          initialMessages = budget.trimmedHistory,
          supportImage = false,
          supportAudio = false,
          systemInstruction = Contents.of(LLM_CHAT_DEFAULT_SYSTEM_PROMPT),
          tools = emptyList(),
          enableConversationConstrainedDecoding = false,
        )
        userContextTokens = budget.finalContextTokens
      } catch (t: Throwable) {
        Log.w(TAG, "Non-RAG rebuild failed; proceeding with existing session", t)
      }
    }
    synchronized(nonRagRunningTokens) {
      nonRagRunningTokens[model.name] = userContextTokens
    }
    val finalUserContextTokens = userContextTokens
    // Dispatch the stamp off the main thread because sampleProcessMemoryBytes() can take
    // tens to hundreds of milliseconds on some devices (getProcessMemoryInfo is an IPC).
    viewModelScope.launch(Dispatchers.Default) {
      stampTokenCount(
        model = model,
        side = ChatSide.USER,
        type = ChatMessageType.TEXT,
        count = finalUserContextTokens,
      )
    }

    // Capture the EXACT context that will be sent on this non-RAG turn so we can stamp it
    // onto the AGENT message when generation finishes. The engine's Conversation already
    // holds (system + prior history); the current turn appends the wrapped input. If we
    // rebuilt above, history reflects the trimmed set; otherwise it reflects everything in
    // shadow history (which mirrors what the persistent Conversation has accumulated).
    val nonRagHistoryForSnapshot = snapshotShadowHistory(model)
    val nonRagWrappedQuestion = "<user_question>$input</user_question>"
    val nonRagSnapshot =
      formatContextSnapshot(
        model = model,
        ragOn = false,
        rawPrefix = "",
        trimmedPrefix = "",
        rawHistorySize = nonRagHistoryForSnapshot.size,
        trimmedHistory = nonRagHistoryForSnapshot,
        finalContextTokens = finalUserContextTokens,
        didTrim = withSafetyFactor(projected) > effectiveCap,
        wrappedInput = nonRagWrappedQuestion,
      )

    val wrappedOnDone: () -> Unit = {
      try {
        val last =
          getLastMessageWithTypeAndSide(
            model = model,
            type = ChatMessageType.TEXT,
            side = ChatSide.AGENT,
          ) as? ChatMessageText
        val answer = last?.content?.trim().orEmpty()
        val agentContextTokens = finalUserContextTokens + estimateTextTokens(answer)
        synchronized(nonRagRunningTokens) {
          nonRagRunningTokens[model.name] = agentContextTokens
        }
        // Also append to shadow history so the next turn's budget check sees this exchange.
        if (answer.isNotEmpty()) {
          appendShadowTurn(model = model, userText = input, modelText = answer)
        }
        stampDebugContextSnapshot(model = model, snapshot = nonRagSnapshot)
        stampTokenCount(
          model = model,
          side = ChatSide.AGENT,
          type = ChatMessageType.TEXT,
          count = agentContextTokens,
        )
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to stamp non-RAG agent token count", t)
      }
      onDone()
    }

    // Symmetric with the RAG path: wrap the user input in <user_question> so the model sees
    // a consistent structural envelope on every turn regardless of whether RAG fired. Shadow
    // history is appended with the CLEAN (un-wrapped) input below so replayed turns don't
    // accumulate nested fences.
    val wrappedInput = "<user_question>$input</user_question>"
    super.generateResponse(
      model, wrappedInput, images, audioMessages, onFirstToken, wrappedOnDone, onError, allowThinking
    )
  }

  override fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: () -> Unit,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    // Manual reset → also drop the shadow RAG history so the next RAG turn starts clean.
    clearRagShadowHistory(model)
    resetNonRagRunningTokensFor(model)
    // Ensure the default insurance-assistant system prompt is re-applied on reset. Callers
    // (e.g. LlmChatScreen) don't supply one, so without this the post-reset conversation
    // would have NO system instruction at all.
    val effectiveSystemInstruction =
      systemInstruction ?: Contents.of(LLM_CHAT_DEFAULT_SYSTEM_PROMPT)
    super.resetSession(
      task = task,
      model = model,
      systemInstruction = effectiveSystemInstruction,
      tools = tools,
      supportImage = supportImage,
      supportAudio = supportAudio,
      onDone = onDone,
      enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
    )
  }

  override fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Engine is about to be torn down + reinitialized; shadow history becomes stale.
    clearRagShadowHistory(model)
    resetNonRagRunningTokensFor(model)
    super.handleError(
      context = context,
      task = task,
      model = model,
      modelManagerViewModel = modelManagerViewModel,
      errorMessage = errorMessage,
    )
  }

  companion object {
    private const val DEFAULT_KB_ASSET = "health_triage_kb.md"
    // Upper bound on the per-model shadow history (user + model entries). The token-budget
    // trimmer enforces an additional cap based on the live `maxNumTokens` slider value, so
    // in practice we keep up to this many messages but only the newest that fit are replayed
    // on any given turn.
    private const val MAX_SHADOW_MESSAGES = 50
    // Tokens reserved for the model's generated answer (kept out of the prefill budget so
    // the engine always has somewhere to write).
    private const val RESERVE_FOR_ANSWER = 1024
    // Multiplier applied to all token estimates to leave headroom against the heuristic's
    // ~10% underestimate. Trimming happens 10% earlier than the strict cap suggests.
    private const val TOKEN_ESTIMATE_SAFETY_FACTOR = 1.1
  }
}

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
