package com.example.morphnote.database;

import android.database.Cursor;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface BlockDao {

    // ── Existing methods ──────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(BlockEntity block);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<BlockEntity> blocks);

    @Update
    void update(BlockEntity block);

    @Delete
    void delete(BlockEntity block);

    @Query("DELETE FROM blocks WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM blocks WHERE pageId = :pageId")
    void deleteByPageId(String pageId);

    @Query("SELECT * FROM blocks WHERE pageId = :pageId ORDER BY orderIndex ASC")
    List<BlockEntity> getBlocksForPage(String pageId);

    @Query("SELECT * FROM blocks WHERE blockType = 'TODO' AND isCompleted = 0 ORDER BY orderIndex ASC")
    List<BlockEntity> getPendingTodoBlocks();

    @Query("SELECT * FROM blocks ORDER BY orderIndex ASC")
    List<BlockEntity> getAllBlocks();

    @Query("SELECT * FROM blocks WHERE id = :id LIMIT 1")
    BlockEntity getBlockById(String id);

    @Query("SELECT * FROM blocks")
    Cursor queryAllBlocksCursor();

    @Query("SELECT * FROM blocks WHERE pageId = :pageId ORDER BY orderIndex ASC")
    Cursor queryBlocksForPageCursor(String pageId);

    // ── New methods (version 2) ───────────────────────────────────────────────

    /**
     * Returns all TODO blocks whose {@code dueDate} falls within the given timestamp range,
     * ordered by due date ascending.
     *
     * @param startTimestamp Inclusive start of the range (ms since epoch).
     * @param endTimestamp   Inclusive end of the range (ms since epoch).
     * @return List of matching {@link BlockEntity} objects.
     */
    @Query("SELECT * FROM blocks " +
           "WHERE blockType = 'TODO' " +
           "AND dueDate BETWEEN :startTimestamp AND :endTimestamp " +
           "ORDER BY dueDate ASC")
    List<BlockEntity> getBlocksByDateRange(long startTimestamp, long endTimestamp);

    /**
     * Returns all blocks that have an active reminder alarm scheduled in the future
     * and have not yet been completed.
     *
     * @param currentTime The current time in ms since epoch.
     * @return List of blocks whose reminder is pending, ordered by reminder time ascending.
     */
    @Query("SELECT * FROM blocks " +
           "WHERE hasReminder = 1 " +
           "AND reminderTime > :currentTime " +
           "AND isCompleted = 0 " +
           "ORDER BY reminderTime ASC")
    List<BlockEntity> getUpcomingReminders(long currentTime);

    /**
     * Returns all blocks (of any type) whose {@code dueDate} falls within the given
     * day boundaries, ordered by due date ascending.
     *
     * @param dayStart Inclusive start-of-day timestamp (ms since epoch).
     * @param dayEnd   Inclusive end-of-day timestamp (ms since epoch).
     * @return List of matching {@link BlockEntity} objects.
     */
    @Query("SELECT * FROM blocks " +
           "WHERE dueDate BETWEEN :dayStart AND :dayEnd " +
           "ORDER BY dueDate ASC")
    List<BlockEntity> getBlocksByDueDate(long dayStart, long dayEnd);

    /**
     * Returns all incomplete TODO blocks with a priority of High (1) or Urgent (2),
     * ordered by priority descending so the most urgent items appear first.
     *
     * @return List of high-priority pending TODO blocks.
     */
    @Query("SELECT * FROM blocks " +
           "WHERE blockType = 'TODO' " +
           "AND isCompleted = 0 " +
           "AND priority >= 1 " +
           "ORDER BY priority DESC")
    List<BlockEntity> getHighPriorityPending();
}
