package com.fersaiyan.cyanbridge.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Opens the app database exactly as the app always has: the framework SQLite through
 * Room's SupportSQLiteOpenHelper (no driver), file `cyanbridge-db` in the app's
 * database directory.
 */
fun buildAppDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        .applyCyanBridgeSchema()
        .build()

internal actual fun sqlMigration(startVersion: Int, endVersion: Int, statements: List<String>): Migration =
    object : Migration(startVersion, endVersion) {
        override fun migrate(db: SupportSQLiteDatabase) {
            statements.forEach { sql -> db.execSQL(sql) }
        }
    }

internal actual fun freshInstallCallback(): RoomDatabase.Callback =
    object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Room doesn't (yet) support declaring FTS5 virtual tables via annotations.
            // Ensure the FTS5 index exists for fresh installs.
            runCatching {
                AppDatabase.FRESH_INSTALL_MIGRATIONS.forEach { it.migrate(db) }
            }
        }
    }

internal actual suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R = withTransaction(block)
