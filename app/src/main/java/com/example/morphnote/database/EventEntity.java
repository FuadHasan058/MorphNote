package com.example.morphnote.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * EventEntity represents standalone calendar events that are managed
 * strictly within the Calendar section and are NOT attached to canvases.
 */
@Entity(tableName = "events")
public class EventEntity implements Serializable {

    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_HIGH   = 1;
    public static final int PRIORITY_URGENT = 2;

    @PrimaryKey
    @NonNull
    private String id;
    private String userId;
    private String title;
    private long eventTime;
    private int priority;
    private boolean hasReminder;
    private boolean isCompleted;

    public EventEntity() {
        this.id = "";
        this.userId = "";
        this.title = "";
        this.eventTime = System.currentTimeMillis();
        this.priority = PRIORITY_NORMAL;
        this.hasReminder = false;
        this.isCompleted = false;
    }

    @Ignore
    public EventEntity(@NonNull String id, String userId, String title, long eventTime,
                       int priority, boolean hasReminder, boolean isCompleted) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.eventTime = eventTime;
        this.priority = priority;
        this.hasReminder = hasReminder;
        this.isCompleted = isCompleted;
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

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
        this.eventTime = eventTime;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isHasReminder() {
        return hasReminder;
    }

    public void setHasReminder(boolean hasReminder) {
        this.hasReminder = hasReminder;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public void setCompleted(boolean completed) {
        isCompleted = completed;
    }
}

