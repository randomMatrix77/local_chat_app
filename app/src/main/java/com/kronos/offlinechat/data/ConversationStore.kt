package com.kronos.offlinechat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageRole { USER, MODEL, TOOL }

@Entity(tableName = "conversations")
data class ChatConversation(
  @PrimaryKey val id: String = UUID.randomUUID().toString(),
  val title: String = "New conversation",
  val createdAtMillis: Long = System.currentTimeMillis(),
  val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "messages")
data class ChatMessage(
  @PrimaryKey val id: String = UUID.randomUUID().toString(),
  val conversationId: String,
  val role: MessageRole,
  val text: String,
  val imagePath: String? = null,
  val sourceUrl: String? = null,
  val createdAtMillis: Long = System.currentTimeMillis(),
)

@Dao
interface ChatDao {
  @Query("SELECT * FROM conversations ORDER BY updatedAtMillis DESC") fun observeConversations(): kotlinx.coroutines.flow.Flow<List<ChatConversation>>
  @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC") fun observeMessages(conversationId: String): kotlinx.coroutines.flow.Flow<List<ChatMessage>>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConversation(conversation: ChatConversation)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessage(message: ChatMessage)
  @Query("UPDATE messages SET text = :text WHERE id = :id") suspend fun replaceMessage(id: String, text: String)
  @Query("UPDATE conversations SET updatedAtMillis = :timestamp WHERE id = :id") suspend fun touchConversation(id: String, timestamp: Long = System.currentTimeMillis())
  @Query("UPDATE conversations SET title = :title, updatedAtMillis = :timestamp WHERE id = :id") suspend fun renameConversation(id: String, title: String, timestamp: Long = System.currentTimeMillis())
  @Query("DELETE FROM messages WHERE conversationId = :conversationId") suspend fun deleteMessages(conversationId: String)
  @Query("DELETE FROM conversations WHERE id = :conversationId") suspend fun deleteConversation(conversationId: String)
}

@Database(entities = [ChatConversation::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class OfflineChatDatabase : RoomDatabase() {
  abstract fun chatDao(): ChatDao
  companion object {
    fun create(context: Context): OfflineChatDatabase = Room.databaseBuilder(context, OfflineChatDatabase::class.java, "offline-chat.db").build()
  }
}

data class ConversationSnapshot(
  val conversations: List<ChatConversation> = emptyList(),
  val activeConversationId: String? = null,
  val messages: List<ChatMessage> = emptyList(),
)

/** Room-backed, device-only storage. Message data is never uploaded by this class. */
class ConversationStore(context: Context, scope: CoroutineScope) {
  private val preferences = context.getSharedPreferences("offline_chat", Context.MODE_PRIVATE)
  private val dao = OfflineChatDatabase.create(context).chatDao()
  private val activeId = MutableStateFlow(preferences.getString("active_conversation", null))

  val conversation: StateFlow<ConversationSnapshot> = activeId.flatMapLatest { id ->
    if (id == null) flowOf(ConversationSnapshot())
    else combine(dao.observeConversations(), dao.observeMessages(id)) { conversations, messages -> ConversationSnapshot(conversations, id, messages) }
  }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConversationSnapshot())

  init { scope.launch(Dispatchers.IO) { ensureActiveConversation() } }

  suspend fun createConversation(): String {
    val conversation = ChatConversation()
    dao.insertConversation(conversation)
    setActiveConversation(conversation.id)
    return conversation.id
  }

  suspend fun setActiveConversation(id: String) {
    activeId.value = id
    preferences.edit().putString("active_conversation", id).apply()
  }

  suspend fun append(role: MessageRole, text: String, imagePath: String? = null, sourceUrl: String? = null): ChatMessage {
    val id = ensureActiveConversation()
    return ChatMessage(conversationId = id, role = role, text = text, imagePath = imagePath, sourceUrl = sourceUrl).also {
      dao.insertMessage(it)
      dao.touchConversation(id)
    }
  }

  suspend fun replaceMessage(id: String, text: String) = dao.replaceMessage(id, text)
  suspend fun renameActiveConversation(title: String) { activeId.value?.let { dao.renameConversation(it, title.trim().ifBlank { "Untitled conversation" }) } }
  suspend fun renameConversation(id: String, title: String) { dao.renameConversation(id, title.trim().ifBlank { "Untitled conversation" }) }
  suspend fun deleteActiveConversation() {
    val id = activeId.value ?: return
    dao.deleteMessages(id); dao.deleteConversation(id)
    activeId.value = null
    preferences.edit().remove("active_conversation").apply()
    ensureActiveConversation()
  }

  private suspend fun ensureActiveConversation(): String = activeId.value ?: createConversation()
}
