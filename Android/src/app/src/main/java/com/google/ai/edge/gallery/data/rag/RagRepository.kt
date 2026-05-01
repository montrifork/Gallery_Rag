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
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Hilt-singleton repository owning the active RAG index. */
interface RagRepository {
  val state: StateFlow<RagState>
  suspend fun ingestPdf(uri: Uri): Result<Unit>

  /**
   * Ingests a PDF bundled in the app's assets. The display name shown to the user is
   * [assetName] (e.g., "health_triage_kb.pdf"). Implementations copy the asset to a cache
   * file and run the same indexing pipeline as [ingestPdf].
   */
  suspend fun ingestAsset(assetName: String): Result<Unit>

  suspend fun clear()
  suspend fun retrieve(query: String, k: Int = 4): List<ScoredChunk>

  /** Convenience: returns the prompt prefix for [chunks], using the active doc name. Empty string if no index. */
  fun formatContext(chunks: List<ScoredChunk>): String
}

class DefaultRagRepository(
  private val context: Context,
  private val extractor: PdfTextExtractor,
) : RagRepository {

  private val _state = MutableStateFlow<RagState>(RagState.Empty)
  override val state: StateFlow<RagState> = _state.asStateFlow()

  private val mutex = Mutex()
  @Volatile private var current: RagIndexData? = null

  private val json = Json { ignoreUnknownKeys = true }

  init {
    // Probe for an existing index file. Don't load chunks into memory yet (lazy on first retrieve).
    val f = indexFile()
    if (f.exists()) {
      try {
        val loaded = json.decodeFromString(RagIndexData.serializer(), f.readText())
        current = loaded
        _state.value = RagState.Ready(loaded.docName, loaded.chunks.size)
      } catch (t: Throwable) {
        Log.w(TAG, "Discarding corrupt index file", t)
        f.delete()
        _state.value = RagState.Empty
      }
    }
  }

  override suspend fun ingestPdf(uri: Uri): Result<Unit> = ingestInternal(uri, displayNameOverride = null)

  override suspend fun ingestAsset(assetName: String): Result<Unit> {
    val cacheFile = try {
      withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "rag_assets/$assetName").apply {
          parentFile?.mkdirs()
        }
        context.assets.open(assetName).use { input ->
          out.outputStream().use { output -> input.copyTo(output) }
        }
        out
      }
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to stage bundled asset '$assetName'", t)
      return Result.failure(RagError.FileOpenFailed(t))
    }
    return ingestInternal(Uri.fromFile(cacheFile), displayNameOverride = assetName)
  }

  private suspend fun ingestInternal(
    uri: Uri,
    displayNameOverride: String?,
  ): Result<Unit> = mutex.withLock {
    val previous = current
    try {
      _state.value = RagState.Indexing(0f)

      val docName = displayNameOverride ?: queryDisplayName(uri) ?: "document.pdf"

      // Extract (0.0 -> 0.4)
      val pages = extractor.extract(uri)
      _state.value = RagState.Indexing(0.4f)

      if (pages.all { it.text.isBlank() }) throw RagError.NoTextExtracted()
      if (pages.size > MAX_PAGES) throw RagError.TooLarge(pages.size)

      // Chunk (0.4 -> 0.6)
      val chunks = try {
        withContext(Dispatchers.Default) { Chunker.chunk(pages) }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        throw RagError.IndexBuildFailed(t)
      }
      _state.value = RagState.Indexing(0.6f)

      // Index (0.6 -> 0.9)
      val index = try {
        withContext(Dispatchers.Default) {
          Bm25Index.build(chunks, docName = docName, createdAtMs = System.currentTimeMillis())
        }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        throw RagError.IndexBuildFailed(t)
      }
      _state.value = RagState.Indexing(0.9f)

      // Persist atomically (0.9 -> 1.0)
      withContext(Dispatchers.IO) {
        try {
          val dir = indexDir().also { it.mkdirs() }
          val tmp = File(dir, "active_index.json.tmp")
          tmp.writeText(json.encodeToString(RagIndexData.serializer(), index))
          val target = indexFile()
          if (!tmp.renameTo(target)) {
            // Fallback: copy then delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
          }
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          throw RagError.PersistenceFailed(t)
        }
      }

      current = index
      _state.value = RagState.Ready(docName, chunks.size)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: RagError) {
      Log.w(TAG, "Ingestion failed", e)
      // Restore previous good state.
      current = previous
      _state.value = previous?.let { RagState.Ready(it.docName, it.chunks.size) } ?: RagState.Empty
      Result.failure(e)
    } catch (t: Throwable) {
      Log.e(TAG, "Unexpected ingestion error", t)
      current = previous
      _state.value = previous?.let { RagState.Ready(it.docName, it.chunks.size) } ?: RagState.Empty
      Result.failure(RagError.IndexBuildFailed(t))
    }
  }

  override suspend fun clear() = mutex.withLock {
    withContext(Dispatchers.IO) { indexFile().delete() }
    current = null
    _state.value = RagState.Empty
  }

  override suspend fun retrieve(query: String, k: Int): List<ScoredChunk> {
    val idx = current ?: return emptyList()
    return try {
      withContext(Dispatchers.Default) { Bm25Index.search(idx, query, k) }
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "Retrieval failed; returning no results", t)
      emptyList()
    }
  }

  override fun formatContext(chunks: List<ScoredChunk>): String {
    val docName = current?.docName ?: return ""
    return ContextFormatter.format(docName, chunks)
  }

  // ---------- helpers ----------

  private fun indexDir(): File = File(context.filesDir, "rag")
  private fun indexFile(): File = File(indexDir(), "active_index.json")

  private fun queryDisplayName(uri: Uri): String? {
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
          if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
      null
    }
  }

  companion object {
    private const val TAG = "AGRagRepository"
    private const val MAX_PAGES = 500
  }
}
