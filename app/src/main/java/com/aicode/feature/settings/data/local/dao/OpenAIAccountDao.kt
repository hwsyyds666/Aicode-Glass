package com.aicode.feature.settings.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.settings.data.local.entity.OpenAIAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OpenAIAccountDao {
    @Query("SELECT * FROM openai_accounts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<OpenAIAccountEntity>>

    @Query("SELECT * FROM openai_accounts ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<OpenAIAccountEntity>

    @Query("SELECT * FROM openai_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OpenAIAccountEntity?

    @Query("SELECT * FROM openai_accounts WHERE subject = :subject AND clientId = :clientId LIMIT 1")
    suspend fun getByIdentity(subject: String, clientId: String): OpenAIAccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: OpenAIAccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: OpenAIAccountEntity)

    @Query("UPDATE openai_accounts SET status = :status, lastError = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String?, updatedAt: Long)

    @Query("DELETE FROM openai_accounts WHERE id = :id")
    suspend fun delete(id: String)
}
