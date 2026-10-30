package com.example.glpred.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteStatement
import com.example.glpred.data.CgmCsvLoader
import com.example.glpred.data.CgmDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Room's index name for the `timestampMs` column, dropped and rebuilt around a bulk import. */
private const val TIMESTAMP_INDEX = "index_cgm_table_timestampMs"

/** Rows per INSERT statement: 2 bind parameters each, just under SQLite's 999 variable limit. */
private const val INSERT_ROWS_PER_STATEMENT = 499

/** One multi-row INSERT with [rows] value tuples, so a whole batch parses as a single statement. */
private fun cgmInsertSql(rows: Int): String = buildString(rows * 14) {
    append("INSERT INTO cgm_table (timestampMs, glucose) VALUES ")
    for (i in 0 until rows) {
        if (i > 0) append(',')
        append("(?,?)")
    }
}

/**
 * Imports the selected CGM CSV into [CgmDatabase] with fine-grained performance logging.
 */
class FetchDataViewModel(application: Application) : AndroidViewModel(application) {

    private val database = CgmDatabase.getDatabase(application)
    private val cgmDao = database.cgmDao()

    var isImporting by mutableStateOf(false)
        private set

    var importSuccess by mutableStateOf(false)
        private set

    var importedCount by mutableStateOf(0)
        private set

    var importDurationMs by mutableStateOf(0L)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    /**
     * Reads raw CSV file off the main thread and replaces stored CGM telemetry in SQLite in sub-second time.
     */
    fun importCsv(uri: Uri) {
        if (isImporting) return
        isImporting = true
        importSuccess = false
        errorMessage = null

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val t0 = System.currentTimeMillis()
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("The selected file could not be opened.")

                    val loaded = CgmCsvLoader.load(input)
                    val t1 = System.currentTimeMillis()

                    require(loaded.readings.isNotEmpty()) {
                        "The file does not contain any glucose reading."
                    }

                    database.withTransaction {
                        val rawDb = database.openHelper.writableDatabase

                        // The table is replaced wholesale, so the timestamp index is maintained
                        // once at the end instead of being updated for every single inserted row.
                        rawDb.execSQL("DROP INDEX IF EXISTS $TIMESTAMP_INDEX")
                        cgmDao.clearAll()

                        // Wide multi-row INSERTs: each statement carries up to
                        // INSERT_ROWS_PER_STATEMENT rows, so SQLite parses the statement once and
                        // writes the whole batch in one step instead of one round trip per row.
                        // Statements are cached by row count, so only the final, shorter batch
                        // needs a second compiled statement.
                        val statements = HashMap<Int, SupportSQLiteStatement>()
                        try {
                            var index = 0
                            while (index < loaded.readings.size) {
                                val rows = minOf(INSERT_ROWS_PER_STATEMENT, loaded.readings.size - index)
                                val statement = statements.getOrPut(rows) {
                                    rawDb.compileStatement(cgmInsertSql(rows))
                                }
                                var bind = 1
                                for (offset in 0 until rows) {
                                    val reading = loaded.readings[index + offset]
                                    statement.bindLong(bind++, reading.timestampMs)
                                    statement.bindDouble(bind++, reading.glucose)
                                }
                                statement.executeInsert()
                                index += rows
                            }
                        } finally {
                            statements.values.forEach { it.close() }
                        }

                        rawDb.execSQL("CREATE INDEX IF NOT EXISTS $TIMESTAMP_INDEX ON cgm_table (timestampMs)")
                    }
                    val t2 = System.currentTimeMillis()

                    Log.d("PERF", "CgmCsvLoader.load: ${t1 - t0}ms | SQLite Batch Insert (${loaded.readings.size} rows): ${t2 - t1}ms | TOTAL: ${t2 - t0}ms")

                    loaded.readings.size
                }
            }

            val endTime = System.currentTimeMillis()
            importDurationMs = endTime - startTime
            isImporting = false

            result.fold(
                onSuccess = { count ->
                    importedCount = count
                    importSuccess = true
                },
                onFailure = {
                    errorMessage = "Import failed: ${it.message}"
                }
            )
        }
    }
}
