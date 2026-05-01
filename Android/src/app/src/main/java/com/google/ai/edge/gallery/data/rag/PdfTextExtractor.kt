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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts page-by-page text from a PDF Uri. */
interface PdfTextExtractor {
  /**
   * @throws RagError.FileOpenFailed if the Uri cannot be opened.
   * @throws RagError.NotAPdf if PdfBox cannot parse the file.
   */
  suspend fun extract(uri: Uri): List<PageText>
}

class PdfBoxTextExtractor(private val context: Context) : PdfTextExtractor {

  init {
    // Safe to call multiple times; PdfBox guards against re-init.
    PDFBoxResourceLoader.init(context.applicationContext)
  }

  override suspend fun extract(uri: Uri): List<PageText> = withContext(Dispatchers.IO) {
    val input = try {
      context.contentResolver.openInputStream(uri)
        ?: throw RagError.FileOpenFailed(IllegalStateException("openInputStream returned null"))
    } catch (e: SecurityException) {
      throw RagError.FileOpenFailed(e)
    } catch (e: java.io.IOException) {
      throw RagError.FileOpenFailed(e)
    }

    input.use { stream ->
      val doc = try {
        PDDocument.load(stream)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        Log.w(TAG, "PdfBox failed to load document", e)
        throw RagError.NotAPdf(e)
      }
      doc.use { d ->
        val stripper = PDFTextStripper()
        val pageCount = d.numberOfPages
        val out = ArrayList<PageText>(pageCount)
        for (pageNum in 1..pageCount) {
          stripper.startPage = pageNum
          stripper.endPage = pageNum
          val text = stripper.getText(d)
          out.add(PageText(page = pageNum, text = text))
        }
        return@withContext out
      }
    }
  }

  companion object {
    private const val TAG = "AGPdfBoxTextExtractor"
  }
}
