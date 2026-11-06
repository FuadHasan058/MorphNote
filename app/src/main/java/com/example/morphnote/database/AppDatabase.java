package com.example.morphnote.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Room database singleton for the MorphNote application.
 *
 * <p>Version history:
 * <ul>
 *   <li>v1 – initial schema (pages + blocks)</li>
 *   <li>v2 – added {@code dueDate}, {@code reminderTime}, {@code hasReminder}, {@code priority}
 *            columns to the {@code blocks} table via {@link #MIGRATION_1_2}</li>
 * </ul>
 */
@Database(entities = {PageEntity.class, BlockEntity.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    // ── Abstract DAO accessors ────────────────────────────────────────────────

    public abstract PageDao pageDao();

    public abstract BlockDao blockDao();

    // ── Executor for off-main-thread DB writes ────────────────────────────────

    private static final int NUMBER_OF_THREADS = 4;
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    // ── Migration: v1 → v2 ───────────────────────────────────────────────────

    /**
     * Incremental schema migration from database version 1 to version 2.
     *
     * <p>Adds four new nullable/default columns to the {@code blocks} table so that
     * existing rows remain valid without data loss:
     * <ul>
     *   <li>{@code dueDate}     – nullable INTEGER (Long timestamp, ms)</li>
     *   <li>{@code reminderTime} – nullable INTEGER (Long timestamp, ms)</li>
     *   <li>{@code hasReminder}  – non-null INTEGER, default 0 (false)</li>
     *   <li>{@code priority}     – non-null INTEGER, default 0 (Normal)</li>
     * </ul>
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE blocks ADD COLUMN dueDate INTEGER");
            database.execSQL(
                    "ALTER TABLE blocks ADD COLUMN reminderTime INTEGER");
            database.execSQL(
                    "ALTER TABLE blocks ADD COLUMN hasReminder INTEGER NOT NULL DEFAULT 0");
            database.execSQL(
                    "ALTER TABLE blocks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0");
        }
    };

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile AppDatabase INSTANCE;

    /**
     * Returns the application-wide {@link AppDatabase} instance, creating it on first call.
     *
     * @param context Any {@link Context}; {@code getApplicationContext()} is used internally.
     * @return The singleton {@link AppDatabase}.
     */
    public static AppDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "morphnote_database")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Convenience accessor for the shared write executor.
     *
     * @return The fixed thread-pool {@link ExecutorService} used for database writes.
     */
    public static ExecutorService getDatabaseExecutor() {
        return databaseWriteExecutor;
    }
}
