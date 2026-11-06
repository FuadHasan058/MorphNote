package com.example.morphnote.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PageEntity page);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<PageEntity> pages);

    @Update
    void update(PageEntity page);

    @Delete
    void delete(PageEntity page);

    @Query("DELETE FROM pages WHERE id = :pageId")
    void deleteById(String pageId);

    @Query("SELECT * FROM pages WHERE userId = :userId ORDER BY updatedAt DESC")
    List<PageEntity> getAllPages(String userId);

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    PageEntity getPageById(String pageId);

    @Query("SELECT COUNT(*) FROM pages WHERE userId = :userId")
    int getPageCount(String userId);
}

