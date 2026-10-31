package com.example.glpred.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrainedModelDao {

    @Query("SELECT * FROM model_table WHERE id = 1")
    suspend fun get(): TrainedModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(model: TrainedModelEntity)

    @Query("DELETE FROM model_table")
    suspend fun clear()
}
