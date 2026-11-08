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
 *   <li>v3 – added {@code status} column to {@code blocks} table and created {@code events}
 *            table for standalone calendar events via {@link #MIGRATION_2_3}</li>
 *   <li>v4 – added {@code routines} table for weekly recurring schedule via {@link #MIGRATION_3_4}</li>
 * </ul>
 */
@Database(entities = {PageEntity.class, BlockEntity.class, EventEntity.class, RoutineEntity.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    // ── Abstract DAO accessors ────────────────────────────────────────────────

    public abstract PageDao pageDao();

    public abstract BlockDao blockDao();

    public abstract EventDao eventDao();

    public abstract RoutineDao routineDao();

    // ── Executor for off-main-thread DB writes ────────────────────────────────

    private static final int NUMBER_OF_THREADS = 4;
    public static final ExecutorService databaseWriteExecutor =
            Executors.newFixedThreadPool(NUMBER_OF_THREADS);

    // ── Migrations ────────────────────────────────────────────────────────────

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE blocks ADD COLUMN dueDate INTEGER");
            database.execSQL("ALTER TABLE blocks ADD COLUMN reminderTime INTEGER");
            database.execSQL("ALTER TABLE blocks ADD COLUMN hasReminder INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE blocks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE blocks ADD COLUMN status TEXT DEFAULT 'TODO'");
            database.execSQL("CREATE TABLE IF NOT EXISTS events (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "userId TEXT, " +
                    "title TEXT, " +
                    "eventTime INTEGER NOT NULL, " +
                    "priority INTEGER NOT NULL DEFAULT 0, " +
                    "hasReminder INTEGER NOT NULL DEFAULT 0, " +
                    "isCompleted INTEGER NOT NULL DEFAULT 0)");
        }
    };

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS routines (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "userId TEXT, " +
                    "title TEXT, " +
                    "dayOfWeek INTEGER NOT NULL, " +
                    "hour INTEGER NOT NULL, " +
                    "minute INTEGER NOT NULL, " +
                    "tag TEXT, " +
                    "isEnabled INTEGER NOT NULL DEFAULT 1, " +
                    "createdAt INTEGER NOT NULL DEFAULT 0)");
        }
    };

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getInstance(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "morphnote_database")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    public static ExecutorService getDatabaseExecutor() {
        return databaseWriteExecutor;
    }
}
