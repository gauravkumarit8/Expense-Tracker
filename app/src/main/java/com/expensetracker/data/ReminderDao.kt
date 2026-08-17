package com.expensetracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Query("SELECT * FROM reminders ORDER BY dueDayOfMonth ASC")
    fun getAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders")
    suspend fun getAllOnce(): List<Reminder>

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}