package com.example.glpred.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of imported CGM telemetry stored in [CgmDatabase].
 *
 * [timestampMs] encodes the export's local wall-clock timestamp (`dd-MM-yyyy HH:mm`, written by
 * LibreView without any UTC offset) as milliseconds since the epoch, **treating that wall clock as
 * if it were UTC** (`LocalDateTime.toEpochMilli(ZoneOffset.UTC)`). Three consequences matter:
 *
 *  - `ORDER BY timestampMs` sorts history chronologically, which the raw `dd-MM-yyyy` text does
 *    not (text sorts by day-of-month first);
 *  - the encoding is faithful to the file and decodes back to the exact wall clock, so the
 *    daylight-saving hour that occurs twice on the fall-back night stays recognisable and can be
 *    excluded during preprocessing;
 *  - resolving the time against the device zone instead would map both copies of that ambiguous
 *    hour onto one instant and destroy that evidence.
 *
 * [timestampMs] is deliberately **not** unique: the export contains minutes carrying both a
 * historic and a scan reading (and the fall-back hour repeats wall-clock minutes), so rows must not
 * be collapsed at import time. Deduplication, averaging and gap policy belong to preprocessing.
 */
@Entity(
    tableName = "cgm_table",
    indices = [Index(value = ["timestampMs"])]
)
data class CgmReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,   // Export wall clock (dd-MM-yyyy HH:mm) as if it were UTC, in millis
    val glucose: Double      // Blood glucose level (mg/dL)
)
