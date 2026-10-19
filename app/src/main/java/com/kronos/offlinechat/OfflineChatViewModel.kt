package com.kronos.offlinechat

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kronos.offlinechat.data.ChatConversation
import com.kronos.offlinechat.data.ChatMessage
import com.kronos.offlinechat.data.ConversationStore
import com.kronos.offlinechat.data.MessageRole
import com.kronos.offlinechat.inference.DownloadState
import com.kronos.offlinechat.inference.InferenceStatus
import com.kronos.offlinechat.inference.LiteRtLmGateway
import com.kronos.offlinechat.inference.ModelRepository
import com.kronos.offlinechat.inference.OfficialGemmaModel
import com.kronos.offlinechat.inference.OfficialGemmaModels
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OfflineChatUiState(
  val conversations: List<ChatConversation> = emptyList(),
  val activeConversationId: String? = null,
  val messages: List<ChatMessage> = emptyList(),
  val selectedModel: OfficialGemmaModel? = null,
  val modelStates: Map<OfficialGemmaModel, DownloadState> = emptyMap(),
  val inferenceStatus: InferenceStatus = InferenceStatus.NoModelSelected,
  val draft: String = "",
  val pendingImagePath: String? = null,
  val isGenerating: Boolean = false,
  val webSearchTemplate: String = "",
  val webSearchEnabled: Boolean = true,
  val isSearching: Boolean = false,
  val webSearchStatus: String = "Configure a provider URL template containing {query}.",
)

class OfflineChatViewModel(application: Application) : AndroidViewModel(application) {
  private val defaultSearchTemplate = "https://html.duckduckgo.com/html/?q={query}"
  private val store = ConversationStore(application, viewModelScope)
  private val models = ModelRepository(application)
  private val gateway = LiteRtLmGateway(application)
  private val preferences = application.getSharedPreferences("offline_chat", Application.MODE_PRIVATE)
  private val _uiState = MutableStateFlow(OfflineChatUiState(inferenceStatus = gateway.status.value))
  val uiState: StateFlow<OfflineChatUiState> = _uiState
  private var generation: Job? = null

  init {
    val savedModel = preferences.getString("selected_model", null)
    _uiState.update { it.copy(selectedModel = OfficialGemmaModels.all.firstOrNull { model -> model.id == savedModel }) }
    val searchTemplate = preferences.getString("web_search_template", null)?.takeIf { it.isNotBlank() } ?: defaultSearchTemplate
    _uiState.update { it.copy(webSearchTemplate = searchTemplate, webSearchEnabled = preferences.getBoolean("web_search_enabled", true)) }
    viewModelScope.launch { store.conversation.collectLatest { snapshot -> _uiState.update { it.copy(conversations = snapshot.conversations, activeConversationId = snapshot.activeConversationId, messages = snapshot.messages) } } }
    viewModelScope.launch { gateway.status.collectLatest { status -> _uiState.update { it.copy(inferenceStatus = status) } } }
    OfficialGemmaModels.all.forEach { model -> viewModelScope.launch { models.state(model).collectLatest { state ->
      _uiState.update { current -> current.copy(modelStates = current.modelStates + (model to state)) }
      if (state is DownloadState.Ready && _uiState.value.selectedModel == model) gateway.modelAvailable(state.path)
    } } }
    _uiState.value.selectedModel?.let { gateway.modelAvailable(models.path(it)) }
  }

  fun updateDraft(value: String) = _uiState.update { it.copy(draft = value) }
  fun newConversation() = viewModelScope.launch(Dispatchers.IO) { store.createConversation() }
  fun selectConversation(id: String) = viewModelScope.launch(Dispatchers.IO) { store.setActiveConversation(id) }
  fun renameConversation(title: String) = viewModelScope.launch(Dispatchers.IO) { store.renameActiveConversation(title) }
  fun deleteConversation() = viewModelScope.launch(Dispatchers.IO) { store.deleteActiveConversation() }

  fun selectModel(model: OfficialGemmaModel) {
    generation?.cancel(); gateway.close()
    preferences.edit().putString("selected_model", model.id).apply()
    _uiState.update { it.copy(selectedModel = model) }
    gateway.modelAvailable(models.path(model))
  }

  fun downloadModel(model: OfficialGemmaModel) = viewModelScope.launch { models.download(model); if (models.path(model) != null) selectModel(model) }
  fun retryDownload(model: OfficialGemmaModel) = downloadModel(model)
  fun deleteModel(model: OfficialGemmaModel) {
    if (_uiState.value.selectedModel == model) { generation?.cancel(); gateway.close() }
    models.delete(model)
    gateway.modelAvailable(_uiState.value.selectedModel?.let(models::path))
  }
  fun loadSelectedModel() = _uiState.value.selectedModel?.let { model -> models.path(model)?.let { path -> viewModelScope.launch(Dispatchers.Default) { gateway.load(path) } } }

  fun importImage(uri: Uri) = viewModelScope.launch {
    runCatching {
      val directory = File(getApplication<Application>().filesDir, "images").apply { mkdirs() }
      val target = File(directory, "gallery-${System.currentTimeMillis()}.jpg")
      withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) } ?: error("Unable to read image") }
      target.absolutePath
    }.onSuccess { path -> _uiState.update { it.copy(pendingImagePath = path) } }
      .onFailure { error -> _uiState.update { it.copy(inferenceStatus = InferenceStatus.Error("Image import failed: ${error.message}")) } }
  }

  fun createCameraImageUri(): Uri {
    val directory = File(getApplication<Application>().filesDir, "images").apply { mkdirs() }
    val image = File(directory, "camera-${System.currentTimeMillis()}.jpg")
    _uiState.update { state -> state.copy(pendingImagePath = image.absolutePath) }
    return FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.fileprovider", image)
  }
  fun clearAttachment() = _uiState.update { it.copy(pendingImagePath = null) }

  fun submitDraft() {
    val prompt = _uiState.value.draft.trim(); val image = _uiState.value.pendingImagePath
    if ((prompt.isEmpty() && image == null) || _uiState.value.isGenerating) return
    val history = _uiState.value.messages
    _uiState.update { it.copy(draft = "", pendingImagePath = null) }
    viewModelScope.launch {
      val user = store.append(MessageRole.USER, prompt.ifBlank { "Describe this image." }, image)
      if (_uiState.value.inferenceStatus !is InferenceStatus.Ready) return@launch
      _uiState.update { it.copy(isGenerating = true) }
      val reply = store.append(MessageRole.MODEL, "")
      generation = launch(Dispatchers.Default) {
        var text = ""
        try { gateway.generate(history, user.text, image, _uiState.value.webSearchEnabled, _uiState.value.webSearchTemplate) { searching -> _uiState.update { it.copy(isSearching = searching) } }.collect { chunk -> text += chunk; store.replaceMessage(reply.id, text) } }
        catch (cancelled: CancellationException) { store.replaceMessage(reply.id, text.ifBlank { "Generation stopped." }); throw cancelled }
        catch (failure: Throwable) { store.replaceMessage(reply.id, "Inference failed: ${failure.message ?: failure.javaClass.simpleName}"); gateway.reportGenerationFailure(failure) }
        finally {
          _uiState.update { it.copy(isGenerating = false, isSearching = false) }
          if (text.isNotBlank()) gateway.generateTitle(user.text)?.let { title -> store.renameConversation(user.conversationId, title) }
        }
      }
    }
  }

  fun stopGeneration() { gateway.stopGeneration(); generation?.cancel() }

  fun setWebSearchTemplate(template: String) {
    preferences.edit().putString("web_search_template", template).apply()
    _uiState.update { it.copy(webSearchTemplate = template) }
  }
  fun setWebSearchEnabled(enabled: Boolean) { preferences.edit().putBoolean("web_search_enabled", enabled).apply(); _uiState.update { it.copy(webSearchEnabled = enabled) } }

  override fun onCleared() { gateway.close(); super.onCleared() }
}
