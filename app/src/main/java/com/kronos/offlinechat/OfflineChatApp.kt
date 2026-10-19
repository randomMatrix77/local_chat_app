package com.kronos.offlinechat

import android.Manifest
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kronos.offlinechat.data.ChatConversation
import com.kronos.offlinechat.data.ChatMessage
import com.kronos.offlinechat.data.MessageRole
import com.kronos.offlinechat.inference.DownloadState
import com.kronos.offlinechat.inference.InferenceStatus
import com.kronos.offlinechat.inference.OfficialGemmaModel
import com.kronos.offlinechat.inference.OfficialGemmaModels
import kotlinx.coroutines.launch
import java.io.File

private enum class Destination { CHAT, MODELS, WEB_SETTINGS }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OfflineChatApp(viewModel: OfflineChatViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var destination by rememberSaveable { mutableStateOf(Destination.CHAT) }
  var overflowExpanded by remember { mutableStateOf(false) }
  var renameDialog by rememberSaveable { mutableStateOf(false) }
  var conversationName by rememberSaveable { mutableStateOf("") }
  val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val activeTitle = state.conversations.firstOrNull { it.id == state.activeConversationId }?.title ?: "New conversation"
  val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::importImage) }
  var pendingCamera by remember { mutableStateOf<android.net.Uri?>(null) }
  val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> if (!saved) viewModel.clearAttachment() }
  val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) pendingCamera?.let(camera::launch) }
  val takePhoto: () -> Unit = {
    pendingCamera = viewModel.createCameraImageUri()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) pendingCamera?.let(camera::launch)
    else cameraPermission.launch(Manifest.permission.CAMERA)
    Unit
  }

  ModalNavigationDrawer(
    drawerState = drawer,
    gesturesEnabled = destination == Destination.CHAT,
    drawerContent = {
      ModalDrawerSheet(modifier = Modifier.widthIn(max = 360.dp)) {
        DrawerContent(
          conversations = state.conversations,
          active = state.activeConversationId,
          onNew = { viewModel.newConversation(); scope.launch { drawer.close() } },
          onSelect = { id -> viewModel.selectConversation(id); scope.launch { drawer.close() } },
          onModels = { destination = Destination.MODELS; scope.launch { drawer.close() } },
          onWebSettings = { destination = Destination.WEB_SETTINGS; scope.launch { drawer.close() } },
        )
      }
    },
  ) {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        CenterAlignedTopAppBar(
          colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
          navigationIcon = {
            if (destination == Destination.CHAT) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Open conversations") }
            else IconButton(onClick = { destination = Destination.CHAT }) { Icon(Icons.Default.ArrowBack, "Back to chat") }
          },
          title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(if (destination == Destination.CHAT) activeTitle else if (destination == Destination.MODELS) "Models" else "Web search", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
              if (destination == Destination.CHAT) Text(state.selectedModel?.label ?: "No model selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          },
          actions = {
            if (destination == Destination.CHAT) {
              AssistChip(onClick = { destination = Destination.MODELS }, label = { Text(state.selectedModel?.id?.uppercase() ?: "MODEL") }, leadingIcon = { Icon(Icons.Outlined.Bolt, null, Modifier.size(16.dp)) })
              IconButton(onClick = { overflowExpanded = true }) { Icon(Icons.Default.MoreVert, "Conversation options") }
              DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                DropdownMenuItem(text = { Text("New chat") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { overflowExpanded = false; viewModel.newConversation() })
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.ChatBubbleOutline, null) }, onClick = { overflowExpanded = false; conversationName = activeTitle; renameDialog = true })
                DropdownMenuItem(text = { Text("Delete chat") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { overflowExpanded = false; viewModel.deleteConversation() })
              }
            }
          },
        )
      },
    ) { padding ->
      when (destination) {
        Destination.CHAT -> ChatScreen(state, viewModel, takePhoto, { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)); Unit }, Modifier.padding(padding))
        Destination.MODELS -> ModelScreen(state, viewModel, Modifier.padding(padding))
        Destination.WEB_SETTINGS -> WebSettingsScreen(state, viewModel, Modifier.padding(padding))
      }
    }
  }
  if (renameDialog) AlertDialog(
    onDismissRequest = { renameDialog = false },
    title = { Text("Rename conversation") },
    text = { OutlinedTextField(value = conversationName, onValueChange = { conversationName = it }, singleLine = true, label = { Text("Title") }) },
    confirmButton = { TextButton(onClick = { viewModel.renameConversation(conversationName); renameDialog = false }) { Text("Save") } },
    dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } },
  )
}

@Composable
private fun DrawerContent(conversations: List<ChatConversation>, active: String?, onNew: () -> Unit, onSelect: (String) -> Unit, onModels: () -> Unit, onWebSettings: () -> Unit) {
  Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Chats", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
      FilledTonalIconButton(onClick = onNew) { Icon(Icons.Default.Add, "New chat") }
    }
    Text("Local Gemma Chat", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
    LazyColumn(Modifier.weight(1f)) {
      items(conversations, key = { it.id }) { chat ->
        val selected = chat.id == active
        Surface(
          modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(18.dp)).clickable { onSelect(chat.id) },
          color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
          shape = RoundedCornerShape(18.dp),
        ) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.ChatBubbleOutline, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
          Text(chat.title, modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        } }
      }
    }
    HorizontalDivider()
    DrawerAction("Models", Icons.Default.SmartToy, onModels)
    DrawerAction("Web search", Icons.Default.Public, onWebSettings)
    Spacer(Modifier.size(12.dp))
  }
}

@Composable private fun DrawerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) =
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Text(label, Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleSmall) }

@Composable
private fun ChatScreen(state: OfflineChatUiState, viewModel: OfflineChatViewModel, takePhoto: () -> Unit, choosePhoto: () -> Unit, modifier: Modifier) {
  Column(modifier.fillMaxSize().imePadding()) {
    ModelStatus(state.inferenceStatus, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    if (state.isSearching) SearchStatus(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    if (state.messages.isEmpty()) EmptyChat(Modifier.weight(1f))
    else LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("conversation_messages"), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(state.messages, key = { it.id }) { MessageBubble(it) } }
    state.pendingImagePath?.let { ImageAttachment(it, viewModel::clearAttachment) }
    Composer(state, viewModel, takePhoto, choosePhoto)
  }
}

@Composable private fun EmptyChat(modifier: Modifier) = Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.SmartToy, null, Modifier.padding(16.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary) }; Text("Private, on-device chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)); Text("Gemma runs on this phone. Web search is only used when the model asks for current information.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }

@Composable private fun ModelStatus(status: InferenceStatus, modifier: Modifier) {
  val (label, color) = when (status) {
    is InferenceStatus.Ready -> "On-device • ${status.backend} ready" to MaterialTheme.colorScheme.tertiaryContainer
    is InferenceStatus.Loading -> "Loading Gemma…" to MaterialTheme.colorScheme.secondaryContainer
    is InferenceStatus.ModelReadyToLoad -> "Model downloaded • load it in Models" to MaterialTheme.colorScheme.secondaryContainer
    InferenceStatus.NoModelSelected -> "Choose a Gemma model" to MaterialTheme.colorScheme.secondaryContainer
    InferenceStatus.EmulatorUnsupported -> "Physical device required for inference" to MaterialTheme.colorScheme.errorContainer
    is InferenceStatus.Error -> "Inference unavailable" to MaterialTheme.colorScheme.errorContainer
  }
  Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = color) { Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Bolt, null, Modifier.size(16.dp)); Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium) } }
}

@Composable private fun SearchStatus(modifier: Modifier) = Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { LinearProgressIndicator(Modifier.size(width = 28.dp, height = 3.dp)); Text("Searching the web…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium) } }

@Composable
private fun Composer(state: OfflineChatUiState, viewModel: OfflineChatViewModel, takePhoto: () -> Unit, choosePhoto: () -> Unit) {
  Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(28.dp), tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      IconButton(onClick = takePhoto) { Icon(Icons.Default.CameraAlt, "Take photo") }
      IconButton(onClick = choosePhoto) { Icon(Icons.Default.Image, "Choose image") }
      TextField(value = state.draft, onValueChange = viewModel::updateDraft, modifier = Modifier.weight(1f).testTag("chat_draft").heightIn(min = 48.dp, max = 136.dp), placeholder = { Text("Message Local Gemma…") }, maxLines = 5, colors = androidx.compose.material3.TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
      if (state.isGenerating) FilledTonalIconButton(onClick = viewModel::stopGeneration, modifier = Modifier.testTag("send_or_save")) { Icon(Icons.Default.Close, "Stop generation") }
      else FilledIconButton(onClick = viewModel::submitDraft, enabled = state.draft.isNotBlank() || state.pendingImagePath != null, modifier = Modifier.testTag("send_or_save"), colors = IconButtonDefaults.filledIconButtonColors()) { Icon(Icons.Default.Send, "Send") }
    }
  }
}

@Composable private fun MessageBubble(message: ChatMessage) {
  val user = message.role == MessageRole.USER
  val container = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(if (user) 22.dp else 24.dp), modifier = Modifier.widthIn(max = 680.dp)) { Column(Modifier.padding(14.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!user) Icon(Icons.Default.SmartToy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (user) "You" else if (message.role == MessageRole.TOOL) "Web result" else "Gemma", Modifier.padding(start = if (user) 0.dp else 7.dp).weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Text(DateFormat.format("HH:mm", message.createdAtMillis).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      message.imagePath?.let { ImagePreview(it, Modifier.padding(top = 10.dp)) }
      if (message.text.isNotBlank()) Text(message.text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
      else if (!user) Text("Generating…", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
      SourceLinks(message)
    } }
  }
}

@Composable private fun SourceLinks(message: ChatMessage) {
  val urls = remember(message.text, message.sourceUrl) { (Regex("""https?://[^\s)\]]+""").findAll(message.text).map { it.value.trimEnd('.', ',', ';') }.toList() + listOfNotNull(message.sourceUrl)).distinct().take(5) }
  if (urls.isNotEmpty()) { val uriHandler = LocalUriHandler.current; HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp), color = DividerDefaults.color); Text("Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); urls.forEach { url -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { uriHandler.openUri(url) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(url, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Outlined.OpenInNew, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary) } } }
}

@Composable private fun ImageAttachment(path: String, onRemove: () -> Unit) { Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { ImagePreview(path); TextButton(onClick = onRemove) { Text("Remove") } } }
@Composable private fun ImagePreview(path: String, modifier: Modifier = Modifier) { val bitmap = remember(path) { BitmapFactory.decodeFile(path) }; bitmap?.let { ComposeImage(it.asImageBitmap(), "Attached image", modifier.size(104.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) } ?: Text("Image attachment unavailable") }

@Composable private fun ModelScreen(state: OfflineChatUiState, viewModel: OfflineChatViewModel, modifier: Modifier) { LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { Text("Manage downloaded models and runtime settings.", color = MaterialTheme.colorScheme.onSurfaceVariant) }; items(OfficialGemmaModels.all, key = { it.id }) { model -> ModelCard(model, state.selectedModel == model, state.modelStates[model] ?: DownloadState.NotDownloaded, viewModel) }; item { Text("Only one model is loaded at a time. GPU is preferred; CPU fallback is used only when GPU initialization fails.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun ModelCard(model: OfficialGemmaModel, selected: Boolean, download: DownloadState, viewModel: OfflineChatViewModel) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.SmartToy, null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary) }; Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(model.label, style = MaterialTheme.typography.titleLarge); Text(model.sizeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (selected) AssistChip(onClick = {}, label = { Text("Selected") }, leadingIcon = { Icon(Icons.Outlined.Bolt, null, Modifier.size(14.dp)) }) }
  when (download) { DownloadState.NotDownloaded -> Button(onClick = { viewModel.downloadModel(model) }) { Text("Download") }; is DownloadState.Downloading -> { val fraction = (download.downloadedBytes.toFloat() / download.totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f); LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth()); Text("${(fraction * 100).toInt()}% • ${download.downloadedBytes / 1_000_000} / ${download.totalBytes / 1_000_000} MB", style = MaterialTheme.typography.labelMedium) }; is DownloadState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { viewModel.selectModel(model) }) { Text(if (selected) "Active" else "Set active") }; OutlinedButton(onClick = { viewModel.deleteModel(model) }) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)); Text("Delete", Modifier.padding(start = 6.dp)) } }; is DownloadState.Failed -> { Text("Download failed: ${download.detail}", color = MaterialTheme.colorScheme.error); Button(onClick = { viewModel.retryDownload(model) }) { Text("Retry") } } }
  if (selected && stateReadyToLoad(download)) OutlinedButton(onClick = viewModel::loadSelectedModel) { Text("Load selected model") }
} } }

private fun stateReadyToLoad(download: DownloadState) = download is DownloadState.Ready

@Composable private fun WebSettingsScreen(state: OfflineChatUiState, viewModel: OfflineChatViewModel, modifier: Modifier) { Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Card(shape = RoundedCornerShape(24.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) { Icon(Icons.Default.Public, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary) }; Column(Modifier.padding(start = 14.dp).weight(1f)) { Text("Allow web search", style = MaterialTheme.typography.titleMedium); Text("Gemma may fetch current information when needed.", color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = state.webSearchEnabled, onCheckedChange = viewModel::setWebSearchEnabled) } }
  OutlinedTextField(value = state.webSearchTemplate, onValueChange = viewModel::setWebSearchTemplate, modifier = Modifier.fillMaxWidth(), label = { Text("Search-provider URL") }, supportingText = { Text("DuckDuckGo is the current default. Only a query is sent; chat history and inference stay on-device.") }, singleLine = false)
  Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Row(Modifier.padding(16.dp)) { Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.padding(start = 12.dp)) { Text("Model stays on-device", fontWeight = FontWeight.SemiBold); Text("Search results are untrusted data and are passed to the local model only after destination URLs are validated.", color = MaterialTheme.colorScheme.onSecondaryContainer) } } }
  Text("The model can make at most two searches per turn. Search failures appear in the response; the app never invents sources.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
} }
