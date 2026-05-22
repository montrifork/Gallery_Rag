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

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"

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
            systemInstruction = null,
            tools = emptyList(),
            enableConversationConstrainedDecoding = false,
          )
        } catch (t: Throwable) {
          Log.w(TAG, "rebuildConversationWithHistory failed; proceeding with existing session", t)
        }

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

    super.generateResponse(
      model, input, images, audioMessages, onFirstToken, onDone, onError, allowThinking
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
    super.resetSession(
      task = task,
      model = model,
      systemInstruction = systemInstruction,
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
