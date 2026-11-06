package com.example.morphnote.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.io.Serializable;

@Entity(tableName = "blocks")
public class BlockEntity implements Serializable {

    public static final String TYPE_HEADING = "HEADING";
    public static final String TYPE_TODO    = "TODO";
    public static final String TYPE_BULLET  = "BULLET";
    public static final String TYPE_CALLOUT = "CALLOUT";
    public static final String TYPE_CODE    = "CODE";
    public static final String TYPE_LINK    = "LINK";
    public static final String TYPE_IMAGE   = "IMAGE";
    public static final String TYPE_FILE    = "FILE";

    // Priority constants
    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_HIGH   = 1;
    public static final int PRIORITY_URGENT = 2;

    // Status constants for tasks
    public static final String STATUS_TODO        = "TODO";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_DONE        = "DONE";

    // ── Existing fields ──────────────────────────────────────────────────────
    @PrimaryKey
    @NonNull
    private String id;
    private String pageId;
    private String blockType;
    private String content;
    private boolean isCompleted;
    private int orderIndex;

    // ── New fields (version 2) ───────────────────────────────────────────────
    /** Unix-epoch millisecond timestamp for the block's due date; null = no due date. */
    private Long dueDate;

    /** Unix-epoch millisecond timestamp for the reminder alarm; null = no reminder. */
    private Long reminderTime;

    /** Whether a reminder alarm has been set for this block. */
    private boolean hasReminder;

    /**
     * Priority level of this block.
     * 0 = Normal ({@link #PRIORITY_NORMAL}),
     * 1 = High   ({@link #PRIORITY_HIGH}),
     * 2 = Urgent ({@link #PRIORITY_URGENT}).
     */
    private int priority;

    /**
     * Execution status: "TODO", "IN_PROGRESS", or "DONE".
     */
    private String status = STATUS_TODO;

    // ── Constructors ─────────────────────────────────────────────────────────

    /** Default no-arg constructor required by Room. */
    public BlockEntity() {
        this.id          = "";
        this.pageId      = "";
        this.blockType   = TYPE_HEADING;
        this.content     = "";
        this.isCompleted = false;
        this.orderIndex  = 0;
        this.dueDate     = null;
        this.reminderTime = null;
        this.hasReminder = false;
        this.priority    = PRIORITY_NORMAL;
    }

    /**
     * Convenience 6-parameter constructor – initializes v2 fields with default values.
     *
     * @param id          Unique block identifier (UUID string).
     * @param pageId      Parent page identifier.
     * @param blockType   One of the TYPE_* constants.
     * @param content     Text/markdown content of the block.
     * @param isCompleted Completion state.
     * @param orderIndex  Display order within the parent page.
     */
    @Ignore
    public BlockEntity(@NonNull String id,
                       String pageId,
                       String blockType,
                       String content,
                       boolean isCompleted,
                       int orderIndex) {
        this(id, pageId, blockType, content, isCompleted, orderIndex, null, null, false, PRIORITY_NORMAL);
    }

    /**
     * Full constructor – covers all fields including the new v2 additions.
     *
     * @param id           Unique block identifier (UUID string).
     * @param pageId       Parent page identifier.
     * @param blockType    One of the TYPE_* constants.
     * @param content      Text/markdown content of the block.
     * @param isCompleted  Completion state (meaningful for TODO blocks).
     * @param orderIndex   Display order within the parent page.
     * @param dueDate      Optional due-date timestamp (ms since epoch); may be null.
     * @param reminderTime Optional reminder alarm timestamp (ms since epoch); may be null.
     * @param hasReminder  {@code true} if a reminder alarm is scheduled.
     * @param priority     One of {@link #PRIORITY_NORMAL}, {@link #PRIORITY_HIGH}, or {@link #PRIORITY_URGENT}.
     */
    @Ignore
    public BlockEntity(@NonNull String id,
                       String pageId,
                       String blockType,
                       String content,
                       boolean isCompleted,
                       int orderIndex,
                       Long dueDate,
                       Long reminderTime,
                       boolean hasReminder,
                       int priority) {
        this.id           = id;
        this.pageId       = pageId;
        this.blockType    = blockType;
        this.content      = content;
        this.isCompleted  = isCompleted;
        this.orderIndex   = orderIndex;
        this.dueDate      = dueDate;
        this.reminderTime = reminderTime;
        this.hasReminder  = hasReminder;
        this.priority     = priority;
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

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
        return STATUS_DONE.equalsIgnoreCase(status) || isCompleted;
    }

    public void setCompleted(boolean completed) {
        this.isCompleted = completed;
        if (completed) {
            this.status = STATUS_DONE;
        } else if (STATUS_DONE.equalsIgnoreCase(this.status)) {
            this.status = STATUS_TODO;
        }
    }

    public String getStatus() {
        return status != null ? status : (isCompleted ? STATUS_DONE : STATUS_TODO);
    }

    public void setStatus(String status) {
        this.status = status;
        this.isCompleted = STATUS_DONE.equalsIgnoreCase(status);
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public Long getDueDate() {
        return dueDate;
    }

    public void setDueDate(Long dueDate) {
        this.dueDate = dueDate;
    }

    public Long getReminderTime() {
        return reminderTime;
    }

    public void setReminderTime(Long reminderTime) {
        this.reminderTime = reminderTime;
    }

    public boolean isHasReminder() {
        return hasReminder;
    }

    public void setHasReminder(boolean hasReminder) {
        this.hasReminder = hasReminder;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }
}
