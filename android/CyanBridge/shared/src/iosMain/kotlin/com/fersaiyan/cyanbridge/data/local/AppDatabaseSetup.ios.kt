package com.fersaiyan.cyanbridge.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.fersaiyan.cyanbridge.shared.platform.PlatformFilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The iOS copy of the app database: the same schema and migrations as Android, in
 * `Documents/cyanbridge-db`, on the bundled SQLite (it has FTS5 on every iOS version).
 */
fun buildAppDatabase(): AppDatabase =
    Room.databaseBuilder<AppDatabase>(
        name = PlatformFilePaths.dataDirectory() + "/" + AppDatabase.DATABASE_NAME,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .applyCyanBridgeSchema()
        .build()

/** One instance per process: Room's invalidation tracking assumes a single open database. */
internal val iosAppDatabase: AppDatabase by lazy { buildAppDatabase() }

// Same driver-connection actuals as portabilityMain (the JVM test target).

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
