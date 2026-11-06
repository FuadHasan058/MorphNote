package com.example.morphnote.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "pages")
public class PageEntity implements Serializable {

    @PrimaryKey
    @NonNull
    private String id;
    private String userId;
    private String title;
    private long updatedAt;

    public PageEntity() {
        this.id = "";
        this.title = "";
        this.userId = "";
        this.updatedAt = System.currentTimeMillis();
    }

    public PageEntity(@NonNull String id, String userId, String title, long updatedAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.updatedAt = updatedAt;
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}

