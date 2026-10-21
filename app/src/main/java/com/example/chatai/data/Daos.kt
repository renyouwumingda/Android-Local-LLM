package com.example.chatai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun findById(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE sessions SET modelId = :modelId WHERE id = :id")
    suspend fun setModelId(id: Long, modelId: String)

    @Query("UPDATE sessions SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeForSession(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun listForSession(sessionId: Long): List<MessageEntity>

    @Query("SELECT content FROM messages WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun lastContent(sessionId: Long): String?

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, interrupted = :interrupted WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, interrupted: Boolean)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Transaction
    suspend fun deleteSessionMessagesAndTouch(sessionId: Long) {
        deleteForSession(sessionId)
    }
}
