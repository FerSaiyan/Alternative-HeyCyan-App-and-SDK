package com.fersaiyan.cyanbridge.data.local

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Targets without Android's SupportSQLite layer talk to SQLite through a driver connection.
// Same actuals as iosMain, so JVM tests exercise the iOS database path.

internal actual fun sqlMigration(startVersion: Int, endVersion: Int, statements: List<String>): Migration =
    object : Migration(startVersion, endVersion) {
        override fun migrate(connection: SQLiteConnection) {
            statements.forEach { sql -> connection.execSQL(sql) }
        }
    }

internal actual fun freshInstallCallback(): RoomDatabase.Callback =
    object : RoomDatabase.Callback() {
        override fun onCreate(connection: SQLiteConnection) {
            super.onCreate(connection)
            runCatching {
                AppDatabase.FRESH_INSTALL_MIGRATIONS.forEach { it.migrate(connection) }
            }
        }
    }

internal actual suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
