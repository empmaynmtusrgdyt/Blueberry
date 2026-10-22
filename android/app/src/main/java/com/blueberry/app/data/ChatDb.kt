package com.blueberry.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    val temperature: Float,
    @ColumnInfo(name = "max_tokens") val maxTokens: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ChatEntity::class, ["id"], ["chat_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chat_id")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    val role: String, // "user" | "assistant"
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updated_at DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: Long): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChat(id: Long): ChatEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY id")
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY id")
    suspend fun getMessages(chatId: Long): List<MessageEntity>

    @Insert suspend fun insertChat(chat: ChatEntity): Long
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @androidx.room.Update suspend fun updateChat(chat: ChatEntity)
    @Delete suspend fun deleteChat(chat: ChatEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE chats SET updated_at = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao
}
