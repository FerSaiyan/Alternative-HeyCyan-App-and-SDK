package com.fersaiyan.cyanbridge.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest

/**
 * Guards the on-device database: users upgrade with data in `cyanbridge-db`, so the
 * schema Room creates and the schema the migrations produce must never drift.
 *
 * Robolectric's SQLite has no FTS5, so the fresh-install dump has no `memory_chunks_fts`
 * and the upgrade starts at version 7 (the FTS5 index is created by migration 4->5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseSchemaTest {

    private lateinit var context: Context
    private val opened = mutableListOf<AppDatabase>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(FRESH_DB, MIGRATED_DB).forEach { context.deleteDatabase(it) }
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        listOf(FRESH_DB, MIGRATED_DB).forEach { context.deleteDatabase(it) }
    }

    @Test
    fun freshInstallSchemaMatchesShippedSchema() {
        val db = open(FRESH_DB)
        val sqlite = db.openHelper.writableDatabase
        assertEquals(SHIPPED_VERSION, sqlite.version)
        assertEquals(SHIPPED_IDENTITY_HASH, identityHash(sqlite))
        assertEquals(SHIPPED_SCHEMA, schemaDump(sqlite))
    }

    @Test
    fun upgradeFromVersionSevenKeepsDataAndMatchesFreshSchema() = runBlocking {
        createVersionSevenDatabase(context.getDatabasePath(MIGRATED_DB))

        val db = open(MIGRATED_DB)
        assertEquals("Kept chat", db.chatDao().getChatById("chat-1")?.title)
        assertEquals(listOf("kept message"), db.messageDao().getMessagesForChatOnce("chat-1").map { it.content })
        val note = db.noteDao().getNoteById(7)
        assertNotNull(note)
        assertEquals("kept note body", note!!.summary)
        assertNull(note.generatedTags)
        assertEquals("kept memory", db.memoryChunkDao().listBySource("screen_capture").single().text)

        val migrated = db.openHelper.writableDatabase
        assertEquals(SHIPPED_VERSION, migrated.version)
        assertEquals(SHIPPED_IDENTITY_HASH, identityHash(migrated))
        assertEquals(columnDump(open(FRESH_DB).openHelper.writableDatabase), columnDump(migrated))
    }

    /** The exact SQL each migration runs on a user's database (SHA-256 of its statements). */
    @Test
    fun migrationsRunTheShippedStatements() {
        val digests = listOf(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
        ).map { migration ->
            val statements = recordStatements(migration)
            "${migration.startVersion}->${migration.endVersion}:${statements.size}:${sha256(statements.joinToString("\n;\n"))}"
        }
        assertEquals(SHIPPED_MIGRATIONS, digests)
    }

    private fun open(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .applyCyanBridgeSchema()
            .allowMainThreadQueries()
            .build()
            .also { opened += it }

    /** Version 7 as Room left it on devices: today's tables except the four tagging columns of `notes`. */
    private fun createVersionSevenDatabase(file: File) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            SHIPPED_SCHEMA.lines()
                .filter { it.isNotBlank() && !it.contains("|android_metadata|") && !it.contains("|room_master_table|") }
                .sortedBy { !it.startsWith("table|") }
                .map { it.split('|', limit = 4)[3] }
                .forEach { sql ->
                    db.execSQL(
                        sql.replace(
                            ", `generatedTags` TEXT, `taggedContentHash` TEXT, `taggingModelVersion` TEXT, `taggedAt` INTEGER",
                            "",
                        ),
                    )
                }
            db.execSQL("INSERT INTO chats VALUES ('chat-1', 'Kept chat', 1, 2)")
            db.execSQL("INSERT INTO messages VALUES ('msg-1', 'chat-1', 'user', 'kept message', 3)")
            db.execSQL("INSERT INTO notes (id, title, summary, createdAt, updatedAt) VALUES (7, 'Kept note', 'kept note body', 4, 5)")
            db.execSQL("INSERT INTO memory_chunks (source, tsMs, text, createdAt, updatedAt) VALUES ('screen_capture', 1, 'kept memory', 1, 1)")
            db.version = 7
        }
    }

    private fun recordStatements(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val recorder = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            check(method.name == "execSQL" && args?.size == 1) { "Unexpected migration call: ${method.name}" }
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase
        migration.migrate(recorder)
        return statements
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun identityHash(db: SupportSQLiteDatabase): String =
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    /** Every table, index, trigger and virtual table with the exact SQL SQLite stores. */
    private fun schemaDump(db: SupportSQLiteDatabase): String =
        db.query("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
            .use { cursor ->
                buildString {
                    while (cursor.moveToNext()) {
                        append(cursor.getString(0)).append('|').append(cursor.getString(1)).append('|')
                            .append(cursor.getString(2)).append('|').append(cursor.getString(3)).append('\n')
                    }
                }
            }

    /** Column shape per Room table, independent of how the CREATE statement was written. */
    private fun columnDump(db: SupportSQLiteDatabase): String {
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'memory_chunks_fts%' AND name != 'android_metadata' ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.joinToString("\n") { table ->
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add("${cursor.getString(1)}:${cursor.getString(2)}:${cursor.getInt(3)}:${cursor.getInt(5)}")
                    }
                }.sorted().joinToString(",", prefix = "$table=")
            }
        }
    }

    private companion object {
        const val FRESH_DB = "schema-test-fresh-db"
        const val MIGRATED_DB = "schema-test-migrated-db"
        const val SHIPPED_VERSION = 8
        const val SHIPPED_IDENTITY_HASH = "1f2b5479c1d0fe0282817bfd8e009967"
        val SHIPPED_SCHEMA = """
            index|index_memory_chunks_source|memory_chunks|CREATE INDEX `index_memory_chunks_source` ON `memory_chunks` (`source`)
            index|index_memory_chunks_tsMs|memory_chunks|CREATE INDEX `index_memory_chunks_tsMs` ON `memory_chunks` (`tsMs`)
            index|index_memory_policy_metadata_sourceTimestampMs|memory_policy_metadata|CREATE INDEX `index_memory_policy_metadata_sourceTimestampMs` ON `memory_policy_metadata` (`sourceTimestampMs`)
            index|index_memory_policy_metadata_sourceType|memory_policy_metadata|CREATE INDEX `index_memory_policy_metadata_sourceType` ON `memory_policy_metadata` (`sourceType`)
            index|index_memory_policy_metadata_syncEligibility|memory_policy_metadata|CREATE INDEX `index_memory_policy_metadata_syncEligibility` ON `memory_policy_metadata` (`syncEligibility`)
            index|index_messages_chatId|messages|CREATE INDEX `index_messages_chatId` ON `messages` (`chatId`)
            index|index_sync_payload_manifest_createdAt|sync_payload_manifest|CREATE INDEX `index_sync_payload_manifest_createdAt` ON `sync_payload_manifest` (`createdAt`)
            index|index_sync_payload_manifest_memoryRef|sync_payload_manifest|CREATE INDEX `index_sync_payload_manifest_memoryRef` ON `sync_payload_manifest` (`memoryRef`)
            index|index_sync_preparation_queue_createdAt|sync_preparation_queue|CREATE INDEX `index_sync_preparation_queue_createdAt` ON `sync_preparation_queue` (`createdAt`)
            index|index_sync_preparation_queue_memoryRef|sync_preparation_queue|CREATE INDEX `index_sync_preparation_queue_memoryRef` ON `sync_preparation_queue` (`memoryRef`)
            index|index_sync_preparation_queue_status|sync_preparation_queue|CREATE INDEX `index_sync_preparation_queue_status` ON `sync_preparation_queue` (`status`)
            index|index_vault_item_keys_keyRef|vault_item_keys|CREATE UNIQUE INDEX `index_vault_item_keys_keyRef` ON `vault_item_keys` (`keyRef`)
            index|index_vault_items_memoryRef|vault_items|CREATE UNIQUE INDEX `index_vault_items_memoryRef` ON `vault_items` (`memoryRef`)
            index|index_vault_items_updatedAt|vault_items|CREATE INDEX `index_vault_items_updatedAt` ON `vault_items` (`updatedAt`)
            table|android_metadata|android_metadata|CREATE TABLE android_metadata (locale TEXT)
            table|capture_sessions|capture_sessions|CREATE TABLE `capture_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, `deviceClass` TEXT NOT NULL, `captureSource` TEXT NOT NULL, `audioPath` TEXT NOT NULL, `timerDurationSec` INTEGER, `stopReason` TEXT, `error` TEXT)
            table|capture_transcripts|capture_transcripts|CREATE TABLE `capture_transcripts` (`captureSessionId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `provider` TEXT NOT NULL, `language` TEXT, `transcript` TEXT NOT NULL, PRIMARY KEY(`captureSessionId`))
            table|chats|chats|CREATE TABLE `chats` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            table|local_embedding_store|local_embedding_store|CREATE TABLE `local_embedding_store` (`memoryRef` TEXT NOT NULL, `embeddingJson` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, `modelVersion` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryRef`))
            table|local_search_index_state|local_search_index_state|CREATE TABLE `local_search_index_state` (`stateKey` TEXT NOT NULL, `stateValue` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`stateKey`))
            table|memory_chunks|memory_chunks|CREATE TABLE `memory_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `sourceId` TEXT, `packageName` TEXT, `tsMs` INTEGER NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
            table|memory_mode_preferences|memory_mode_preferences|CREATE TABLE `memory_mode_preferences` (`id` INTEGER NOT NULL, `selectedMode` TEXT NOT NULL, `screenOcrRetentionDays` INTEGER NOT NULL, `screenOcrCaptureEnabled` INTEGER NOT NULL, `explicitFactsSyncEnabled` INTEGER NOT NULL, `dailyFactsSyncEnabled` INTEGER NOT NULL, `screenOcrSyncEnabled` INTEGER NOT NULL, `derivedSummariesSyncEnabled` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            table|memory_policy_metadata|memory_policy_metadata|CREATE TABLE `memory_policy_metadata` (`memoryRef` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sensitivityLevel` TEXT NOT NULL, `syncEligibility` TEXT NOT NULL, `retentionPolicy` TEXT, `derivedFromIdsCsv` TEXT, `provenance` TEXT, `containsPotentialSecrets` INTEGER NOT NULL, `requiresExplicitConsentForCloud` INTEGER NOT NULL, `sourceTimestampMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryRef`))
            table|messages|messages|CREATE TABLE `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            table|migration_state|migration_state|CREATE TABLE `migration_state` (`migrationKey` TEXT NOT NULL, `status` TEXT NOT NULL, `lastProcessedRef` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`migrationKey`))
            table|notes|notes|CREATE TABLE `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `transcript` TEXT, `redactedTranscript` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `durationSec` INTEGER, `deviceClass` TEXT, `tags` TEXT, `generatedTags` TEXT, `taggedContentHash` TEXT, `taggingModelVersion` TEXT, `taggedAt` INTEGER)
            table|pending_actions|pending_actions|CREATE TABLE `pending_actions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `source` TEXT NOT NULL, `actionJson` TEXT NOT NULL, `status` TEXT NOT NULL, `result` TEXT)
            table|room_master_table|room_master_table|CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)
            table|sync_payload_manifest|sync_payload_manifest|CREATE TABLE `sync_payload_manifest` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memoryRef` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `checksumSha256` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)
            table|sync_preparation_queue|sync_preparation_queue|CREATE TABLE `sync_preparation_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memoryRef` TEXT NOT NULL, `actionType` TEXT NOT NULL, `status` TEXT NOT NULL, `payloadManifestId` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
            table|transcriptions|transcriptions|CREATE TABLE `transcriptions` (`captureSessionId` INTEGER NOT NULL, `status` TEXT NOT NULL, `provider` TEXT NOT NULL, `language` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `progressPercent` INTEGER NOT NULL, `error` TEXT, `transcriptText` TEXT, PRIMARY KEY(`captureSessionId`))
            table|vault_item_keys|vault_item_keys|CREATE TABLE `vault_item_keys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `keyRef` TEXT NOT NULL, `wrappingVersion` INTEGER NOT NULL, `wrappedKeyNonce` BLOB NOT NULL, `wrappedKeyCiphertext` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, `rotatedAt` INTEGER NOT NULL)
            table|vault_items|vault_items|CREATE TABLE `vault_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `memoryRef` TEXT NOT NULL, `keyRef` TEXT NOT NULL, `cryptoVersion` INTEGER NOT NULL, `nonce` BLOB NOT NULL, `ciphertext` BLOB NOT NULL, `aad` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
            table|vault_lock_state|vault_lock_state|CREATE TABLE `vault_lock_state` (`id` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, `requiresPassphrase` INTEGER NOT NULL, `lockedAt` INTEGER NOT NULL, `lastUnlockedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent() + "\n"
        val SHIPPED_MIGRATIONS = listOf(
            "1->2:1:bb7cd9760a9f175bb01bec8f78c642fa1b8c70cb9a00d7398d716a5cd518aec1",
            "2->3:2:9829e426a630fc1897b0cb802f8b9c14f4d509254844630ca35c44563cda3396",
            "3->4:4:b7d5538c5782b1f6e09745652197ee6e5181fac000fa52f49417663d8e1a4a0b",
            "4->5:7:52b8372ba77171d68a68450da9e80c547912c017efa938c469faafa9fbffee59",
            "5->6:1:b2dfd966f8567f9d7d7b4656476c7a176edd2f95f4cf7264492434be5f16cfb1",
            "6->7:21:e2a3a64d50c6f4609978abe3ab809e8e9484b2010ad663c6fede02334daf0eea",
            "7->8:4:13a926a0777bc54ff47e66a53faf66e080ebeb0062e017a11d7abbe684c36d8f",
        )
    }
}
