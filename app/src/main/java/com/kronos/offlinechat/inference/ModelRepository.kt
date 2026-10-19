package com.kronos.offlinechat.inference

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class OfficialGemmaModel(val id: String, val label: String, val fileName: String, val downloadUrl: String, val expectedBytes: Long) {
  val sizeLabel get() = "%.1f GB".format(expectedBytes / 1_000_000_000.0)
}

object OfficialGemmaModels {
  // Official LiteRT Community repositories. Model files stay out of the APK.
  val E4B = OfficialGemmaModel("gemma-4-e4b", "Gemma 4 E4B", "gemma-4-E4B-it.litertlm", "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true", 3_654_467_584L)
  val E2B = OfficialGemmaModel("gemma-4-e2b", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true", 2_583_085_056L)
  val all = listOf(E4B, E2B)
}

sealed interface DownloadState {
  data object NotDownloaded : DownloadState
  data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
  data class Ready(val path: String) : DownloadState
  data class Failed(val detail: String) : DownloadState
}

/** Direct, resumable-by-retry model downloader. It stores only under the app's private files directory. */
class ModelRepository(private val context: Context) {
  private val directory = File(context.filesDir, "models").apply { mkdirs() }
  private val states = OfficialGemmaModels.all.associateWith { MutableStateFlow(initial(it)) }
  fun state(model: OfficialGemmaModel): StateFlow<DownloadState> = states.getValue(model)
  fun path(model: OfficialGemmaModel): String? = file(model).takeIf(File::isFile)?.absolutePath

  suspend fun download(model: OfficialGemmaModel) = withContext(Dispatchers.IO) {
    // Service owns the transfer so screen-off/background execution continues.
    ModelDownloadService.start(context, model)
    states.getValue(model).value = DownloadState.Downloading(File(context.filesDir, "models/${model.fileName}.part").length(), model.expectedBytes)
  }

  /* Legacy in-process path retained below for reference during migration; no longer invoked. */
  private suspend fun legacyDownload(model: OfficialGemmaModel) = withContext(Dispatchers.IO) {
    val output = file(model); val part = File(directory, "${model.fileName}.part"); val state = states.getValue(model)
    runCatching {
      part.delete()
      val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000; requestMethod = "GET"
      }
      connection.connect()
      check(connection.responseCode in 200..299) { "Download server returned HTTP ${connection.responseCode}" }
      val total = connection.contentLengthLong.takeIf { it > 0 } ?: model.expectedBytes
      BufferedInputStream(connection.inputStream, 256 * 1024).use { input -> FileOutputStream(part).use { destination ->
        val buffer = ByteArray(256 * 1024); var copied = 0L; var lastPublishedAt = 0L; var lastPublishedBytes = 0L
        while (true) {
          val read = input.read(buffer); if (read < 0) break
          destination.write(buffer, 0, read); copied += read
          val now = android.os.SystemClock.elapsedRealtime()
          // Compose receives at most four updates a second; the final byte count is still exact.
          if (now - lastPublishedAt >= 250) { state.value = DownloadState.Downloading(copied, total); lastPublishedAt = now; lastPublishedBytes = copied }
        }
        if (copied != lastPublishedBytes) state.value = DownloadState.Downloading(copied, total)
      } }
      check(part.length() > 1_000_000L) { "Downloaded file is unexpectedly small; retry when network access is available." }
      if (output.exists()) output.delete()
      check(part.renameTo(output)) { "Could not finalize downloaded model." }
      state.value = DownloadState.Ready(output.absolutePath)
    }.onFailure { failure -> part.delete(); state.value = DownloadState.Failed(failure.message ?: failure.javaClass.simpleName) }
  }

  fun delete(model: OfficialGemmaModel) { file(model).delete(); File(directory, "${model.fileName}.part").delete(); states.getValue(model).value = DownloadState.NotDownloaded }
  private fun file(model: OfficialGemmaModel) = File(directory, model.fileName)
  private fun initial(model: OfficialGemmaModel): DownloadState = path(model)?.let(DownloadState::Ready) ?: run {
    val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val bytes = prefs.getLong("${model.id}.b", 0)
    val total = prefs.getLong("${model.id}.t", 0)
    if (prefs.getString("${model.id}.s", null) == "downloading") DownloadState.Downloading(bytes, total.takeIf { it > 0 } ?: model.expectedBytes) else DownloadState.NotDownloaded
  }
}
