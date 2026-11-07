/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rtbishop.look4sat.core.data.database.entity.SatEntry
import com.rtbishop.look4sat.core.data.database.entity.SatRadio

@Database(entities = [SatEntry::class, SatRadio::class], version = 4, exportSchema = false)
abstract class Look4SatDb : RoomDatabase() {
    abstract fun look4SatDao(): Look4SatDao
}

/** Adds the mean motion derivative, needed to tell decayed satellites apart, and marks the
 * transceivers that were imported from a file, so that remote updates leave them alone. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN ndot REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE radios ADD COLUMN isCustom INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds the transceiver service class ("Amateur" etc.), used by the SSTV virtual
 * filter to tell amateur satellites apart from debris/weather/launcher stages. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE radios ADD COLUMN service TEXT")
    }
}

/** Repairs names saved by older SatNOGS TLE imports. Parsing now strips the optional
 *  "0 " line-zero marker, but existing rows survive app upgrades without a refresh.
 *  Update only the name column so ephemerides, selections and transceivers are preserved. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE entries SET name = substr(name, 3) WHERE name LIKE '0 %'")
    }
}
