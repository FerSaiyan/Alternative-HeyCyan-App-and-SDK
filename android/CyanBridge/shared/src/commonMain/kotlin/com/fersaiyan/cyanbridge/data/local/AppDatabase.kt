package com.fersaiyan.cyanbridge.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import com.fersaiyan.cyanbridge.data.local.dao.CaptureSessionDao
import com.fersaiyan.cyanbridge.data.local.dao.CaptureTranscriptDao
import com.fersaiyan.cyanbridge.data.local.dao.ChatDao
import com.fersaiyan.cyanbridge.data.local.dao.MemoryChunkDao
import com.fersaiyan.cyanbridge.data.local.dao.MemoryVaultDao
import com.fersaiyan.cyanbridge.data.local.dao.MessageDao
import com.fersaiyan.cyanbridge.data.local.dao.NoteDao
import com.fersaiyan.cyanbridge.data.local.dao.PendingActionDao
import com.fersaiyan.cyanbridge.data.local.dao.TranscriptionDao
import com.fersaiyan.cyanbridge.data.local.entity.CaptureSession
import com.fersaiyan.cyanbridge.data.local.entity.CaptureTranscript
import com.fersaiyan.cyanbridge.data.local.entity.Chat
import com.fersaiyan.cyanbridge.data.local.entity.LocalEmbeddingStoreEntity
import com.fersaiyan.cyanbridge.data.local.entity.LocalSearchIndexStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk // (FTS5 table created via SQL; no Room entity)
import com.fersaiyan.cyanbridge.data.local.entity.MemoryModePreferenceEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryPolicyMetadataEntity
import com.fersaiyan.cyanbridge.data.local.entity.Message
import com.fersaiyan.cyanbridge.data.local.entity.MigrationStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.Note
import com.fersaiyan.cyanbridge.data.local.entity.PendingAction
import com.fersaiyan.cyanbridge.data.local.entity.SyncPayloadManifestEntity
import com.fersaiyan.cyanbridge.data.local.entity.SyncPreparationQueueEntity
import com.fersaiyan.cyanbridge.data.local.entity.TranscriptionRecord
import com.fersaiyan.cyanbridge.data.local.entity.VaultItemEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultItemKeyEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultLockStateEntity

@Database(
    entities = [
        Chat::class,
        Message::class,
        Note::class,
        CaptureSession::class,
        TranscriptionRecord::class,
        CaptureTranscript::class,
        MemoryChunk::class,
        PendingAction::class,
        VaultItemEntity::class,
        VaultItemKeyEntity::class,
        MemoryPolicyMetadataEntity::class,
        SyncPreparationQueueEntity::class,
        SyncPayloadManifestEntity::class,
        LocalEmbeddingStoreEntity::class,
        LocalSearchIndexStateEntity::class,
        MemoryModePreferenceEntity::class,
        VaultLockStateEntity::class,
        MigrationStateEntity::class,
        // memory_chunks_fts is an FTS5 virtual table created via SQL (no Room entity).
    ],
    version = 8,
    exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun noteDao(): NoteDao
    abstract fun captureSessionDao(): CaptureSessionDao
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun captureTranscriptDao(): CaptureTranscriptDao
    abstract fun memoryChunkDao(): MemoryChunkDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun memoryVaultDao(): MemoryVaultDao

    companion object {
        /** File name of the on-device database. Changing it would orphan every user's data. */
        const val DATABASE_NAME = "cyanbridge-db"

        val MIGRATION_1_2: Migration = cyanBridgeMigration(1, 2) {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS capture_sessions (
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
                """.trimIndent()
            )
        }

        val MIGRATION_2_3: Migration = cyanBridgeMigration(2, 3) {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS transcriptions (
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
                """.trimIndent()
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS capture_transcripts (
                    captureSessionId INTEGER PRIMARY KEY NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    language TEXT,
                    transcript TEXT NOT NULL
                )
                """.trimIndent()
            )
        }

        val MIGRATION_3_4: Migration = cyanBridgeMigration(3, 4) {
            // Expand Note schema
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS notes_new (
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
                )
                """.trimIndent()
            )

            // Copy existing rows: map old `content` -> new `summary`.
            // Note: The previous schema had `content` instead of `summary`.
            execSQL(
                """
                INSERT INTO notes_new (id, title, summary, createdAt, updatedAt)
                SELECT id, title, content, createdAt, updatedAt FROM notes
                """.trimIndent()
            )

            execSQL("DROP TABLE notes")
            execSQL("ALTER TABLE notes_new RENAME TO notes")
        }

        val MIGRATION_4_5: Migration = cyanBridgeMigration(4, 5) {
            // Local Agent memory chunks + FTS5 index
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    source TEXT NOT NULL,
                    sourceId TEXT,
                    packageName TEXT,
                    tsMs INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Indices (match @Entity indices)
            execSQL("CREATE INDEX IF NOT EXISTS index_memory_chunks_source ON memory_chunks(source)")
            execSQL("CREATE INDEX IF NOT EXISTS index_memory_chunks_tsMs ON memory_chunks(tsMs)")

            // External-content FTS5 table. We keep canonical text in memory_chunks and
            // let triggers sync the FTS index.
            execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_chunks_fts
                USING fts5(text, content='memory_chunks', content_rowid='id')
                """.trimIndent()
            )

            // Triggers to keep the FTS index in sync.
            execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS memory_chunks_ai
                AFTER INSERT ON memory_chunks
                BEGIN
                    INSERT INTO memory_chunks_fts(rowid, text)
                    VALUES (new.id, new.text);
                END
                """.trimIndent()
            )

            execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS memory_chunks_ad
                AFTER DELETE ON memory_chunks
                BEGIN
                    INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, text)
                    VALUES('delete', old.id, old.text);
                END
                """.trimIndent()
            )

            execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS memory_chunks_au
                AFTER UPDATE ON memory_chunks
                BEGIN
                    INSERT INTO memory_chunks_fts(memory_chunks_fts, rowid, text)
                    VALUES('delete', old.id, old.text);
                    INSERT INTO memory_chunks_fts(rowid, text)
                    VALUES (new.id, new.text);
                END
                """.trimIndent()
            )
        }

        val MIGRATION_5_6: Migration = cyanBridgeMigration(5, 6) {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_actions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ts INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    actionJson TEXT NOT NULL,
                    status TEXT NOT NULL,
                    result TEXT
                )
                """.trimIndent()
            )
        }

        val MIGRATION_6_7: Migration = cyanBridgeMigration(6, 7) {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS vault_items (
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
                """.trimIndent()
            )
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_vault_items_memoryRef ON vault_items(memoryRef)")
            execSQL("CREATE INDEX IF NOT EXISTS index_vault_items_updatedAt ON vault_items(updatedAt)")

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS vault_item_keys (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    keyRef TEXT NOT NULL,
                    wrappingVersion INTEGER NOT NULL,
                    wrappedKeyNonce BLOB NOT NULL,
                    wrappedKeyCiphertext BLOB NOT NULL,
                    createdAt INTEGER NOT NULL,
                    rotatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_vault_item_keys_keyRef ON vault_item_keys(keyRef)")

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_policy_metadata (
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
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS index_memory_policy_metadata_sourceType ON memory_policy_metadata(sourceType)")
            execSQL("CREATE INDEX IF NOT EXISTS index_memory_policy_metadata_syncEligibility ON memory_policy_metadata(syncEligibility)")
            execSQL("CREATE INDEX IF NOT EXISTS index_memory_policy_metadata_sourceTimestampMs ON memory_policy_metadata(sourceTimestampMs)")

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_payload_manifest (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    memoryRef TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL,
                    payloadJson TEXT NOT NULL,
                    checksumSha256 TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_payload_manifest_memoryRef ON sync_payload_manifest(memoryRef)")
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_payload_manifest_createdAt ON sync_payload_manifest(createdAt)")

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_preparation_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    memoryRef TEXT NOT NULL,
                    actionType TEXT NOT NULL,
                    status TEXT NOT NULL,
                    payloadManifestId INTEGER NOT NULL,
                    lastError TEXT,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_preparation_queue_memoryRef ON sync_preparation_queue(memoryRef)")
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_preparation_queue_status ON sync_preparation_queue(status)")
            execSQL("CREATE INDEX IF NOT EXISTS index_sync_preparation_queue_createdAt ON sync_preparation_queue(createdAt)")

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_embedding_store (
                    memoryRef TEXT NOT NULL,
                    embeddingJson TEXT NOT NULL,
                    tagsJson TEXT NOT NULL,
                    modelVersion TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(memoryRef)
                )
                """.trimIndent()
            )

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS local_search_index_state (
                    stateKey TEXT NOT NULL,
                    stateValue TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(stateKey)
                )
                """.trimIndent()
            )

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_mode_preferences (
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
                """.trimIndent()
            )

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS vault_lock_state (
                    id INTEGER NOT NULL,
                    isLocked INTEGER NOT NULL,
                    requiresPassphrase INTEGER NOT NULL,
                    lockedAt INTEGER NOT NULL,
                    lastUnlockedAt INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent()
            )

            execSQL(
                """
                CREATE TABLE IF NOT EXISTS migration_state (
                    migrationKey TEXT NOT NULL,
                    status TEXT NOT NULL,
                    lastProcessedRef TEXT,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(migrationKey)
                )
                """.trimIndent()
            )
        }

        val MIGRATION_7_8: Migration = cyanBridgeMigration(7, 8) {
            execSQL("ALTER TABLE notes ADD COLUMN generatedTags TEXT")
            execSQL("ALTER TABLE notes ADD COLUMN taggedContentHash TEXT")
            execSQL("ALTER TABLE notes ADD COLUMN taggingModelVersion TEXT")
            execSQL("ALTER TABLE notes ADD COLUMN taggedAt INTEGER")
        }

        /** Every migration, oldest first; the database must open with all of them registered. */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )

        /**
         * Room doesn't (yet) support declaring FTS5 virtual tables via annotations, so a fresh
         * install replays these (all `IF NOT EXISTS`) to create the FTS5 index and its triggers.
         */
        val FRESH_INSTALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    }
}

/** Generated by Room for each target (Android, JVM tests, iOS). */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
