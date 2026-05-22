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
  "You are an on-device assistant for an insurance app. Answer the user's question using ONLY " +
    "the knowledge base provided in the context preceding the user's question.\n" +
    "\n" +
    "OUTPUT RULES (follow ALL of them, every time):\n" +
    "\n" +
    "1. ASCII ONLY. No markdown, no bullets, no bold, no headings, no emoji, no smart quotes. " +
    "Use only the ASCII double-quote character ( \" ) for quoting.\n" +
    "\n" +
    "2. QUOTE REQUIREMENT. Every answer, especially numbers or direct suggestions to the user, MUST include at least one verbatim excerpt copied " +
    "character-for-character from the knowledge base, wrapped in ASCII double quotes. The " +
    "excerpt must be 4 words or longer. Place the quote inline in your sentence, like this:\n" +
    "   The policy states \"covered up to 30 days per year\" for inpatient stays.\n" +
    "If you cannot find a suitable excerpt to quote, you do not have the answer (see rule 5).\n" +
    "\n" +
    "3. NUMBERS, DOSAGES, TIMEFRAMES. When the user asks about any number, dosage, percentage, " +
    "age limit, waiting period, deductible, or timeframe, you MUST quote the exact phrase from " +
    "the knowledge base that contains that number. Never restate numbers without a quote.\n" +
    "\n" +
    "4. CONCISENESS. 2 to 5 sentences. Do not add disclaimers, safety notes, or suggestions " +
    "beyond what the knowledge base itself says. Do not invent medical or insurance facts.\n" +
    "\n" +
    "5. REFUSAL. If the knowledge base does not contain the answer, reply with this EXACT " +
    "sentence and nothing else:\n" +
    "   I do not have the knowledge to answer this question. Can you try to reformulate?\n" +
    "\n" +
    "EXAMPLES:\n" +
    "\n" +
    "User: How long is the waiting period for dental?\n" +
    "Good: The plan specifies \"a waiting period of 6 months\" before dental benefits begin. " +
    "Routine cleanings are covered after that.\n" +
    "Bad (no quote, restated number): The waiting period is 6 months.\n" +
    "Bad (paraphrased quote): The plan says there is a six-month waiting period.\n" +
    "\n" +
    "User: What is the capital of France?\n" +
    "Good: I do not have the knowledge to answer this question. Can you try to reformulate?"

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
    // Bootstrap the bundled default PDF on first ever launch so users have a working
    // RAG demo without needing to attach anything. Idempotent across launches.
    if (!dataStoreRepository.getDefaultPdfIngested() && ragState.value is RagState.Empty) {
      viewModelScope.launch {
        val result = ragRepository.ingestAsset(DEFAULT_PDF_ASSET)
        if (result.isSuccess) {
          dataStoreRepository.setDefaultPdfIngested(true)
          // Auto-enable RAG so the default PDF is actually used until the user toggles it off.
          dataStoreRepository.setRagEnabled(true)
        } else {
          Log.w(TAG, "Failed to auto-ingest default PDF", result.exceptionOrNull())
        }
      }
    }
  }

  fun setRagEnabled(enabled: Boolean) {
    dataStoreRepository.setRagEnabled(enabled)
  }

  fun ingestPdf(uri: Uri, onError: (String) -> Unit = {}) {
    viewModelScope.launch {
      val result = ragRepository.ingestPdf(uri)
      result.exceptionOrNull()?.let { e ->
        onError(e.message ?: "Failed to ingest PDF")
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
        val prefix = ragRepository.formatContext(scored)
        val prefixed = if (prefix.isEmpty()) input else prefix + input

        // CRITICAL: rebuild the underlying Conversation with only the CLEAN prior turns so the
        // KV cache doesn't accumulate stale RAG prefixes from earlier turns. The model still
        // "remembers" prior dialogue because those clean Q/A pairs are seeded via
        // ConversationConfig.initialMessages. Only the *current* turn carries a RAG prefix.
        val history = snapshotShadowHistory(model)
        try {
          model.runtimeHelper.rebuildConversationWithHistory(
            model = model,
            initialMessages = history,
            supportImage = false,
            supportAudio = false,
            systemInstruction = Contents.of(LLM_CHAT_DEFAULT_SYSTEM_PROMPT),
            tools = emptyList(),
            enableConversationConstrainedDecoding = false,
          )
        } catch (t: Throwable) {
          Log.w(TAG, "rebuildConversationWithHistory failed; proceeding with existing session", t)
        }

        // Stamp the just-added USER message with the context-token count at send time:
        //   system prompt + replayed shadow history + RAG prefix + user input.
        // The RAG path rebuilds the Conversation, so this is the exact prefill size.
        val userContextTokens =
          estimateBaseContextTokens(history) +
            estimateTextTokens(prefix) +
            estimateTextTokens(input)
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
    // message after generation completes.
    val baselineBeforeTurn =
      synchronized(nonRagRunningTokens) {
        // First non-RAG turn ever for this model: seed with just the system prompt cost.
        nonRagRunningTokens[model.name] ?: estimateTextTokens(LLM_CHAT_DEFAULT_SYSTEM_PROMPT)
      }
    val userContextTokens = baselineBeforeTurn + estimateTextTokens(input)
    synchronized(nonRagRunningTokens) {
      nonRagRunningTokens[model.name] = userContextTokens
    }
    // Dispatch the stamp off the main thread because sampleProcessMemoryBytes() can take
    // tens to hundreds of milliseconds on some devices (getProcessMemoryInfo is an IPC).
    viewModelScope.launch(Dispatchers.Default) {
      stampTokenCount(
        model = model,
        side = ChatSide.USER,
        type = ChatMessageType.TEXT,
        count = userContextTokens,
      )
    }

    val wrappedOnDone: () -> Unit = {
      try {
        val last =
          getLastMessageWithTypeAndSide(
            model = model,
            type = ChatMessageType.TEXT,
            side = ChatSide.AGENT,
          ) as? ChatMessageText
        val answer = last?.content?.trim().orEmpty()
        val agentContextTokens = userContextTokens + estimateTextTokens(answer)
        synchronized(nonRagRunningTokens) {
          nonRagRunningTokens[model.name] = agentContextTokens
        }
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

    super.generateResponse(
      model, input, images, audioMessages, onFirstToken, wrappedOnDone, onError, allowThinking
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
    private const val DEFAULT_PDF_ASSET = "health_triage_kb.pdf"
    // Keep at most this many messages (user + model entries) in the per-model shadow history
    // used to seed `ConversationConfig.initialMessages`. With ~10 pairs at a few hundred tokens
    // each, prefill stays well within a 32k context window.
    private const val MAX_SHADOW_MESSAGES = 20
  }
}

@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()

@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
