package com.example.morphnote.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(EventEntity event);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<EventEntity> events);

    @Update
    void update(EventEntity event);

    @Delete
    void delete(EventEntity event);

    @Query("DELETE FROM events WHERE id = :id")
    void deleteById(String id);

    @Query("SELECT * FROM events WHERE userId = :userId AND eventTime BETWEEN :dayStart AND :dayEnd ORDER BY eventTime ASC")
    List<EventEntity> getEventsForDay(String userId, long dayStart, long dayEnd);

    @Query("SELECT * FROM events WHERE userId = :userId AND eventTime BETWEEN :start AND :end ORDER BY eventTime ASC")
    List<EventEntity> getEventsForRange(String userId, long start, long end);

    @Query("SELECT * FROM events WHERE userId = :userId ORDER BY eventTime ASC")
    List<EventEntity> getAllEventsForUser(String userId);

    @Query("SELECT * FROM events WHERE id = :id LIMIT 1")
    EventEntity getEventById(String id);

    @Query("SELECT * FROM events WHERE hasReminder = 1 AND isCompleted = 0 AND eventTime > :currentTime ORDER BY eventTime ASC")
    List<EventEntity> getUpcomingEventReminders(long currentTime);
}
