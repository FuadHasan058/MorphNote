package com.example.morphnote.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for recurring weekly routine entities.
 */
@Dao
public interface RoutineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(RoutineEntity routine);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<RoutineEntity> routines);

    @Update
    void update(RoutineEntity routine);

    @Delete
    void delete(RoutineEntity routine);

    @Query("DELETE FROM routines WHERE id = :id")
    void deleteById(String id);

    @Query("SELECT * FROM routines WHERE userId = :userId AND dayOfWeek = :dayOfWeek ORDER BY hour ASC, minute ASC")
    List<RoutineEntity> getRoutinesByDay(String userId, int dayOfWeek);

    @Query("SELECT * FROM routines WHERE userId = :userId ORDER BY dayOfWeek ASC, hour ASC, minute ASC")
    List<RoutineEntity> getAllRoutines(String userId);

    @Query("SELECT * FROM routines WHERE id = :id LIMIT 1")
    RoutineEntity getRoutineById(String id);

    @Query("SELECT * FROM routines WHERE isEnabled = 1")
    List<RoutineEntity> getAllEnabledRoutines();

    @Query("DELETE FROM routines WHERE userId = :userId")
    void deleteAllForUser(String userId);
}

