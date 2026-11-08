package com.example.morphnote.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Locale;

/**
 * Room entity representing a weekly recurring schedule / routine entry.
 *
 * <p>Supported recurring days are standard {@link Calendar#DAY_OF_WEEK} values:
 * {@link Calendar#SUNDAY} (1) through {@link Calendar#SATURDAY} (7).
 * Notification alarms are triggered exactly 30 minutes before {@code hour:minute}.</p>
 */
@Entity(tableName = "routines")
public class RoutineEntity implements Serializable {

    @PrimaryKey
    @NonNull
    private String id;

    private String userId;
    private String title;
    private int dayOfWeek; // Calendar.SUNDAY (1) to Calendar.SATURDAY (7)
    private int hour;      // 0 - 23
    private int minute;    // 0 - 59
    private String tag;    // "Class", "Fitness", "Work", "Personal", "Study"
    private boolean isEnabled;
    private long createdAt;

    /**
     * Required default zero-arg constructor for Firebase Realtime Database deserialization.
     */
    public RoutineEntity() {
        this.id = "";
        this.userId = "";
        this.title = "";
        this.dayOfWeek = Calendar.MONDAY;
        this.hour = 9;
        this.minute = 0;
        this.tag = "Class";
        this.isEnabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Full constructor for Room and application creation.
     */
    @Ignore
    public RoutineEntity(@NonNull String id,
                         String userId,
                         String title,
                         int dayOfWeek,
                         int hour,
                         int minute,
                         String tag,
                         boolean isEnabled,
                         long createdAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.dayOfWeek = dayOfWeek;
        this.hour = hour;
        this.minute = minute;
        this.tag = (tag != null && !tag.isEmpty()) ? tag : "Class";
        this.isEnabled = isEnabled;
        this.createdAt = (createdAt > 0) ? createdAt : System.currentTimeMillis();
    }

    // ── Getters and Setters ───────────────────────────────────────────────────

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

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public void setDayOfWeek(int dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    // ── Formatting Helpers ────────────────────────────────────────────────────

    /**
     * Formats the start time in 12-hour format with AM/PM (e.g. "09:30 AM", "02:15 PM").
     */
    public String getFormattedTime() {
        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;
        String amPm = (hour < 12) ? "AM" : "PM";
        return String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, minute, amPm);
    }

    /**
     * Returns full day name (e.g. "Monday", "Tuesday").
     */
    public static String getDayName(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:    return "Monday";
            case Calendar.TUESDAY:   return "Tuesday";
            case Calendar.WEDNESDAY: return "Wednesday";
            case Calendar.THURSDAY:  return "Thursday";
            case Calendar.FRIDAY:    return "Friday";
            case Calendar.SATURDAY:  return "Saturday";
            case Calendar.SUNDAY:    return "Sunday";
            default:                 return "Unknown";
        }
    }

    /**
     * Returns short day name (e.g. "Mon", "Tue").
     */
    public static String getShortDayName(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY:    return "Mon";
            case Calendar.TUESDAY:   return "Tue";
            case Calendar.WEDNESDAY: return "Wed";
            case Calendar.THURSDAY:  return "Thu";
            case Calendar.FRIDAY:    return "Fri";
            case Calendar.SATURDAY:  return "Sat";
            case Calendar.SUNDAY:    return "Sun";
            default:                 return "";
        }
    }
}

