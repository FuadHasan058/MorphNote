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
}

