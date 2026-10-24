package com.fersaiyan.cyanbridge.data.local

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk
import com.fersaiyan.cyanbridge.data.local.entity.TranscriptionRecord
import com.fersaiyan.cyanbridge.data.repository.CyanBridgeRepository
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The driver path iOS uses (bundled SQLite, connection-based migrations and fresh-install
 * callback) must produce the database Android ships, byte for byte.
 */
class SharedAppDatabaseTest {
    private val dir: File = Files.createTempDirectory("cyanbridge-db-test").toFile()
    private val opened = mutableListOf<AppDatabase>()

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun freshInstallMatchesShippedSchema() = runBlocking {
        val file = File(dir, "fresh-db")
        open(file).chatDao().getAllChatsOnce()
        closeAll()

        withConnection(file) { connection ->
            assertEquals(SHIPPED_VERSION, connection.longQuery("PRAGMA user_version").toInt())
            assertEquals(SHIPPED_IDENTITY_HASH, connection.textQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"))
            assertEquals(SHIPPED_SCHEMA, schemaDump(connection))
        }
    }

    @Test
    fun upgradeFromFirstVersionKeepsDataAndMatchesShippedSchema() = runBlocking {
        val file = File(dir, "migrated-db")
        withConnection(file) { connection ->
            connection.execSQL("CREATE TABLE chats (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
            connection.execSQL("CREATE TABLE messages (id TEXT NOT NULL, chatId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            connection.execSQL("CREATE INDEX index_messages_chatId ON messages (chatId)")
            connection.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            connection.execSQL("INSERT INTO chats VALUES ('chat-1', 'Kept chat', 1, 2)")
            connection.execSQL("INSERT INTO messages VALUES ('msg-1', 'chat-1', 'user', 'kept message', 3)")
            connection.execSQL("INSERT INTO notes VALUES (7, 'Kept note', 'kept note body', 4, 5)")
            connection.execSQL("PRAGMA user_version = 1")
        }

        val db = open(file)
        assertEquals("Kept chat", db.chatDao().getChatById("chat-1")?.title)
        assertEquals(listOf("kept message"), db.messageDao().getMessagesForChatOnce("chat-1").map { it.content })
        assertEquals("kept note body", db.noteDao().getNoteById(7)?.summary)
        db.memoryChunkDao().insert(
            MemoryChunk(source = "screen_capture", tsMs = 1, text = "upgrade search works", createdAt = 1, updatedAt = 1),
        )
        assertEquals(1, db.memoryChunkDao().search("upgrade").size)
        assertEquals("[upgrade] search works", db.memoryChunkDao().searchWithSnippet("upgrade").single().snippet)
        closeAll()

        withConnection(file) { connection ->
            assertEquals(SHIPPED_VERSION, connection.longQuery("PRAGMA user_version").toInt())
            assertEquals(SHIPPED_IDENTITY_HASH, connection.textQuery("SELECT identity_hash FROM room_master_table WHERE id = 42"))
            assertEquals(SHIPPED_MIGRATED_SCHEMA, schemaDump(connection))
        }
    }

    @Test
    fun freshInstallHasWorkingFullTextSearch() = runBlocking {
        val dao = open(File(dir, "fts-db")).memoryChunkDao()
        val id = dao.insert(MemoryChunk(source = "screen_capture", tsMs = 1, text = "blue umbrella", createdAt = 1, updatedAt = 1))
        dao.insert(MemoryChunk(source = "screen_capture", tsMs = 2, text = "red car", createdAt = 2, updatedAt = 2))

        assertEquals(listOf(id), dao.search("umbrella").map { it.id })
        dao.deleteBySource("screen_capture")
        assertTrue(dao.search("umbrella").isEmpty())
    }

    @Test
    fun deleteCaptureSessionRemovesItsTranscriptionInOneTransaction() = runBlocking {
        val repository = CyanBridgeRepository(open(File(dir, "repo-db")))
        val id = repository.insertCaptureSession(
            CaptureSession(
                startedAt = 1, endedAt = 2, durationSec = 1, deviceClass = "GENERIC_AUDIO",
                captureSource = "phone", audioPath = "/a.m4a", timerDurationSec = null, stopReason = null, error = null,
            ),
        )
        repository.upsertTranscription(TranscriptionRecord(id, "done", "fake", null, 1, 1, 100, null, "hi"))

        assertTrue(repository.deleteCaptureSession(id))
        assertNull(repository.getTranscriptionByCaptureSessionId(id))
        assertFalse(repository.deleteCaptureSession(id))
    }

    private fun open(file: File): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = file.path)
            .setDriver(BundledSQLiteDriver())
            .applyCyanBridgeSchema()
            .build()
            .also { opened += it }

    private fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun <R> withConnection(file: File, block: (SQLiteConnection) -> R): R {
        val connection = BundledSQLiteDriver().open(file.path)
        return try {
            block(connection)
        } finally {
            connection.close()
        }
    }

    private fun SQLiteConnection.longQuery(sql: String): Long =
        prepare(sql).use { check(it.step()); it.getLong(0) }

    private fun SQLiteConnection.textQuery(sql: String): String =
        prepare(sql).use { check(it.step()); it.getText(0) }

    /**
     * Same dump as the Android schema test. The fresh-install dump equals Android's minus its
     * `android_metadata` table, plus the FTS5 index Robolectric's SQLite can't build; the
     * migrated dump keeps the CREATE text the migrations wrote on users' devices.
     */
    private fun schemaDump(connection: SQLiteConnection): String =
        connection.prepare(
            "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
        ).use { statement ->
            buildString {
                while (statement.step()) {
                    append(statement.getText(0)).append('|').append(statement.getText(1)).append('|')
                        .append(statement.getText(2)).append('|').append(statement.getText(3)).append('\n')
                }
            }
        }

    private companion object {
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
            table|capture_sessions|capture_sessions|CREATE TABLE `capture_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, `deviceClass` TEXT NOT NULL, `captureSource` TEXT NOT NULL, `audioPath` TEXT NOT NULL, `timerDurationSec` INTEGER, `stopReason` TEXT, `error` TEXT)
            table|capture_transcripts|capture_transcripts|CREATE TABLE `capture_transcripts` (`captureSessionId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `provider` TEXT NOT NULL, `language` TEXT, `transcript` TEXT NOT NULL, PRIMARY KEY(`captureSessionId`))
            table|chats|chats|CREATE TABLE `chats` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
            table|local_embedding_store|local_embedding_store|CREATE TABLE `local_embedding_store` (`memoryRef` TEXT NOT NULL, `embeddingJson` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, `modelVersion` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryRef`))
            table|local_search_index_state|local_search_index_state|CREATE TABLE `local_search_index_state` (`stateKey` TEXT NOT NULL, `stateValue` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`stateKey`))
            table|memory_chunks|memory_chunks|CREATE TABLE `memory_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `sourceId` TEXT, `packageName` TEXT, `tsMs` INTEGER NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
            table|memory_chunks_fts|memory_chunks_fts|CREATE VIRTUAL TABLE memory_chunks_fts
            USING fts5(text, content='memory_chunks', content_rowid='id')
            table|memory_chunks_fts_config|memory_chunks_fts_config|CREATE TABLE 'memory_chunks_fts_config'(k PRIMARY KEY, v) WITHOUT ROWID
            table|memory_chunks_fts_data|memory_chunks_fts_data|CREATE TABLE 'memory_chunks_fts_data'(id INTEGER PRIMARY KEY, block BLOB)
            table|memory_chunks_fts_docsize|memory_chunks_fts_docsize|CREATE TABLE 'memory_chunks_fts_docsize'(id INTEGER PRIMARY KEY, sz BLOB)
            table|memory_chunks_fts_idx|memory_chunks_fts_idx|CREATE TABLE 'memory_chunks_fts_idx'(segid, term, pgno, PRIMARY KEY(segid, term)) WITHOUT ROWID
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
            trigger|memory_chunks_ad|memory_chunks|CREATE TRIGGER memory_chunks_ad
            AFTER DELETE ON memory_chunks
            BEGIN
                INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, text)
                VALUES('delete', old.id, old.text);
            END
            trigger|memory_chunks_ai|memory_chunks|CREATE TRIGGER memory_chunks_ai
            AFTER INSERT ON memory_chunks
            BEGIN
                INSERT INTO memory_chunks_fts(rowid, text)
                VALUES (new.id, new.text);
            END
            trigger|memory_chunks_au|memory_chunks|CREATE TRIGGER memory_chunks_au
            AFTER UPDATE ON memory_chunks
            BEGIN
                INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, text)
                VALUES('delete', old.id, old.text);
                INSERT INTO memory_chunks_fts(rowid, text)
                VALUES (new.id, new.text);
            END
        """.trimIndent() + "\n"
        val SHIPPED_MIGRATED_SCHEMA = """
            index|index_memory_chunks_source|memory_chunks|CREATE INDEX index_memory_chunks_source ON memory_chunks(source)
            index|index_memory_chunks_tsMs|memory_chunks|CREATE INDEX index_memory_chunks_tsMs ON memory_chunks(tsMs)
            index|index_memory_policy_metadata_sourceTimestampMs|memory_policy_metadata|CREATE INDEX index_memory_policy_metadata_sourceTimestampMs ON memory_policy_metadata(sourceTimestampMs)
            index|index_memory_policy_metadata_sourceType|memory_policy_metadata|CREATE INDEX index_memory_policy_metadata_sourceType ON memory_policy_metadata(sourceType)
            index|index_memory_policy_metadata_syncEligibility|memory_policy_metadata|CREATE INDEX index_memory_policy_metadata_syncEligibility ON memory_policy_metadata(syncEligibility)
            index|index_messages_chatId|messages|CREATE INDEX index_messages_chatId ON messages (chatId)
            index|index_sync_payload_manifest_createdAt|sync_payload_manifest|CREATE INDEX index_sync_payload_manifest_createdAt ON sync_payload_manifest(createdAt)
            index|index_sync_payload_manifest_memoryRef|sync_payload_manifest|CREATE INDEX index_sync_payload_manifest_memoryRef ON sync_payload_manifest(memoryRef)
            index|index_sync_preparation_queue_createdAt|sync_preparation_queue|CREATE INDEX index_sync_preparation_queue_createdAt ON sync_preparation_queue(createdAt)
            index|index_sync_preparation_queue_memoryRef|sync_preparation_queue|CREATE INDEX index_sync_preparation_queue_memoryRef ON sync_preparation_queue(memoryRef)
            index|index_sync_preparation_queue_status|sync_preparation_queue|CREATE INDEX index_sync_preparation_queue_status ON sync_preparation_queue(status)
            index|index_vault_item_keys_keyRef|vault_item_keys|CREATE UNIQUE INDEX index_vault_item_keys_keyRef ON vault_item_keys(keyRef)
            index|index_vault_items_memoryRef|vault_items|CREATE UNIQUE INDEX index_vault_items_memoryRef ON vault_items(memoryRef)
            index|index_vault_items_updatedAt|vault_items|CREATE INDEX index_vault_items_updatedAt ON vault_items(updatedAt)
            table|capture_sessions|capture_sessions|CREATE TABLE capture_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER NOT NULL,
                durationSec INTEGER NOT NULL,
                deviceClass TEXT NOT NULL,
                captureSource TEXT NOT NULL,
                audioPath TEXT NOT NULL,
                timerDurationSec INTEGER,
                stopReason TEXT,
                error TEXT
            )
            table|capture_transcripts|capture_transcripts|CREATE TABLE capture_transcripts (
                captureSessionId INTEGER PRIMARY KEY NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                provider TEXT NOT NULL,
                language TEXT,
                transcript TEXT NOT NULL
            )
            table|chats|chats|CREATE TABLE chats (id TEXT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))
            table|local_embedding_store|local_embedding_store|CREATE TABLE local_embedding_store (
                memoryRef TEXT NOT NULL,
                embeddingJson TEXT NOT NULL,
                tagsJson TEXT NOT NULL,
                modelVersion TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(memoryRef)
            )
            table|local_search_index_state|local_search_index_state|CREATE TABLE local_search_index_state (
                stateKey TEXT NOT NULL,
                stateValue TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(stateKey)
            )
            table|memory_chunks|memory_chunks|CREATE TABLE memory_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                source TEXT NOT NULL,
                sourceId TEXT,
                packageName TEXT,
                tsMs INTEGER NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            table|memory_chunks_fts|memory_chunks_fts|CREATE VIRTUAL TABLE memory_chunks_fts
            USING fts5(text, content='memory_chunks', content_rowid='id')
            table|memory_chunks_fts_config|memory_chunks_fts_config|CREATE TABLE 'memory_chunks_fts_config'(k PRIMARY KEY, v) WITHOUT ROWID
            table|memory_chunks_fts_data|memory_chunks_fts_data|CREATE TABLE 'memory_chunks_fts_data'(id INTEGER PRIMARY KEY, block BLOB)
            table|memory_chunks_fts_docsize|memory_chunks_fts_docsize|CREATE TABLE 'memory_chunks_fts_docsize'(id INTEGER PRIMARY KEY, sz BLOB)
            table|memory_chunks_fts_idx|memory_chunks_fts_idx|CREATE TABLE 'memory_chunks_fts_idx'(segid, term, pgno, PRIMARY KEY(segid, term)) WITHOUT ROWID
            table|memory_mode_preferences|memory_mode_preferences|CREATE TABLE memory_mode_preferences (
                id INTEGER NOT NULL,
                selectedMode TEXT NOT NULL,
                screenOcrRetentionDays INTEGER NOT NULL,
                screenOcrCaptureEnabled INTEGER NOT NULL,
                explicitFactsSyncEnabled INTEGER NOT NULL,
                dailyFactsSyncEnabled INTEGER NOT NULL,
                screenOcrSyncEnabled INTEGER NOT NULL,
                derivedSummariesSyncEnabled INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            table|memory_policy_metadata|memory_policy_metadata|CREATE TABLE memory_policy_metadata (
                memoryRef TEXT NOT NULL,
                sourceType TEXT NOT NULL,
                sensitivityLevel TEXT NOT NULL,
                syncEligibility TEXT NOT NULL,
                retentionPolicy TEXT,
                derivedFromIdsCsv TEXT,
                provenance TEXT,
                containsPotentialSecrets INTEGER NOT NULL,
                requiresExplicitConsentForCloud INTEGER NOT NULL,
                sourceTimestampMs INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(memoryRef)
            )
            table|messages|messages|CREATE TABLE messages (id TEXT NOT NULL, chatId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))
            table|migration_state|migration_state|CREATE TABLE migration_state (
                migrationKey TEXT NOT NULL,
                status TEXT NOT NULL,
                lastProcessedRef TEXT,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY(migrationKey)
            )
            table|notes|notes|CREATE TABLE "notes" (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                transcript TEXT,
                redactedTranscript TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                durationSec INTEGER,
                deviceClass TEXT,
                tags TEXT
            , generatedTags TEXT, taggedContentHash TEXT, taggingModelVersion TEXT, taggedAt INTEGER)
            table|pending_actions|pending_actions|CREATE TABLE pending_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                ts INTEGER NOT NULL,
                source TEXT NOT NULL,
                actionJson TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT
            )
            table|room_master_table|room_master_table|CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)
            table|sync_payload_manifest|sync_payload_manifest|CREATE TABLE sync_payload_manifest (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                memoryRef TEXT NOT NULL,
                schemaVersion INTEGER NOT NULL,
                payloadJson TEXT NOT NULL,
                checksumSha256 TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            table|sync_preparation_queue|sync_preparation_queue|CREATE TABLE sync_preparation_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                memoryRef TEXT NOT NULL,
                actionType TEXT NOT NULL,
                status TEXT NOT NULL,
                payloadManifestId INTEGER NOT NULL,
                lastError TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            table|transcriptions|transcriptions|CREATE TABLE transcriptions (
                captureSessionId INTEGER PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                provider TEXT NOT NULL,
                language TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                progressPercent INTEGER NOT NULL,
                error TEXT,
                transcriptText TEXT
            )
            table|vault_item_keys|vault_item_keys|CREATE TABLE vault_item_keys (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                keyRef TEXT NOT NULL,
                wrappingVersion INTEGER NOT NULL,
                wrappedKeyNonce BLOB NOT NULL,
                wrappedKeyCiphertext BLOB NOT NULL,
                createdAt INTEGER NOT NULL,
                rotatedAt INTEGER NOT NULL
            )
            table|vault_items|vault_items|CREATE TABLE vault_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                memoryRef TEXT NOT NULL,
                keyRef TEXT NOT NULL,
                cryptoVersion INTEGER NOT NULL,
                nonce BLOB NOT NULL,
                ciphertext BLOB NOT NULL,
                aad TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            table|vault_lock_state|vault_lock_state|CREATE TABLE vault_lock_state (
                id INTEGER NOT NULL,
                isLocked INTEGER NOT NULL,
                requiresPassphrase INTEGER NOT NULL,
                lockedAt INTEGER NOT NULL,
                lastUnlockedAt INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            trigger|memory_chunks_ad|memory_chunks|CREATE TRIGGER memory_chunks_ad
            AFTER DELETE ON memory_chunks
            BEGIN
                INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, text)
                VALUES('delete', old.id, old.text);
            END
            trigger|memory_chunks_ai|memory_chunks|CREATE TRIGGER memory_chunks_ai
            AFTER INSERT ON memory_chunks
            BEGIN
                INSERT INTO memory_chunks_fts(rowid, text)
                VALUES (new.id, new.text);
            END
            trigger|memory_chunks_au|memory_chunks|CREATE TRIGGER memory_chunks_au
            AFTER UPDATE ON memory_chunks
            BEGIN
                INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, text)
                VALUES('delete', old.id, old.text);
                INSERT INTO memory_chunks_fts(rowid, text)
                VALUES (new.id, new.text);
            END
        """.trimIndent() + "\n"
    }
}
