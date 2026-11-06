package com.example.morphnote.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "blocks")
public class BlockEntity implements Serializable {

    public static final String TYPE_HEADING = "HEADING";
    public static final String TYPE_TODO = "TODO";
    public static final String TYPE_BULLET = "BULLET";
    public static final String TYPE_CALLOUT = "CALLOUT";
    public static final String TYPE_CODE = "CODE";

    @PrimaryKey
    @NonNull
    private String id;
    private String pageId;
    private String blockType;
    private String content;
    private boolean isCompleted;
    private int orderIndex;

    public BlockEntity() {
        this.id = "";
        this.pageId = "";
        this.blockType = TYPE_HEADING;
        this.content = "";
        this.isCompleted = false;
        this.orderIndex = 0;
    }

    public BlockEntity(@NonNull String id, String pageId, String blockType, String content, boolean isCompleted, int orderIndex) {
        this.id = id;
        this.pageId = pageId;
        this.blockType = blockType;
        this.content = content;
        this.isCompleted = isCompleted;
        this.orderIndex = orderIndex;
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public String getPageId() {
        return pageId;
    }

    public void setPageId(String pageId) {
        this.pageId = pageId;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public void setCompleted(boolean completed) {
        isCompleted = completed;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }
}

