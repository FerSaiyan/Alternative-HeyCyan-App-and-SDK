package com.fersaiyan.cyanbridge.data.local

import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Records a migration's SQL once so every platform runs exactly the same statements:
 * Android through `SupportSQLiteDatabase.execSQL` (as it always has), other targets
 * through the `SQLiteConnection` of their driver.
 */
class MigrationStatements internal constructor() {
    internal val statements = mutableListOf<String>()

    fun execSQL(sql: String) {
        statements += sql
    }
}

internal fun cyanBridgeMigration(
    startVersion: Int,
    endVersion: Int,
    body: MigrationStatements.() -> Unit,
): Migration = sqlMigration(startVersion, endVersion, MigrationStatements().apply(body).statements.toList())

/** A [Migration] that executes [statements] in order. */
internal expect fun sqlMigration(startVersion: Int, endVersion: Int, statements: List<String>): Migration

/** Replays [AppDatabase.FRESH_INSTALL_MIGRATIONS] when Room creates the database file. */
internal expect fun freshInstallCallback(): RoomDatabase.Callback

/** Runs [block] in one write transaction; DAO calls inside it join that transaction. */
internal expect suspend fun <R> AppDatabase.inTransaction(block: suspend () -> R): R

/** Migrations and fresh-install setup shared by every platform's database builder. */
fun RoomDatabase.Builder<AppDatabase>.applyCyanBridgeSchema(): RoomDatabase.Builder<AppDatabase> =
    addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .addCallback(freshInstallCallback())
