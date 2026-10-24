package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.shared.settings.MemoryPrivacyMode
import com.fersaiyan.cyanbridge.shared.settings.MemorySourceType
import android.util.Base64
import com.fersaiyan.cyanbridge.data.local.entity.LocalEmbeddingStoreEntity
import com.fersaiyan.cyanbridge.data.local.entity.LocalSearchIndexStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryModePreferenceEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryPolicyMetadataEntity
import com.fersaiyan.cyanbridge.data.local.entity.MigrationStateEntity
import com.fersaiyan.cyanbridge.data.local.entity.SyncPayloadManifestEntity
import com.fersaiyan.cyanbridge.data.local.entity.SyncPreparationQueueEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultItemEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultItemKeyEntity
import com.fersaiyan.cyanbridge.data.local.entity.VaultLockStateEntity
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.CipherEnvelope
import com.fersaiyan.cyanbridge.shared.memoryvault.crypto.VaultCrypto
import com.fersaiyan.cyanbridge.memoryvault.crypto.VaultKeyManager
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

object MemoryVaultService {
    suspend fun putText(
        context: Context,
        memoryRef: String,
        plaintext: String,
        policy: MemoryPolicyMetadata,
    ): Boolean = withContext(Dispatchers.IO) {
        if (VaultLockStateManager.isLocked(context)) return@withContext false
        VaultKeyManager.ensureMasterKeyExists(context)
        val master = VaultKeyManager.getUnlockedMasterKey(context) ?: return@withContext false

        val keyRef = keyRefFor(memoryRef)
        val now = System.currentTimeMillis()
        val itemKey = VaultCrypto.newAesKeyBytes()

        val wrappedItemKey = VaultCrypto.encryptAesGcm(
            keyBytes = master,
            plaintext = itemKey,
            aad = keyRef.toByteArray(Charsets.UTF_8),
        )
        val payload = VaultCrypto.encryptAesGcm(
            keyBytes = itemKey,
            plaintext = plaintext.toByteArray(Charsets.UTF_8),
            aad = memoryRef.toByteArray(Charsets.UTF_8),
        )

        val dao = MyApplication.database.memoryVaultDao()
        dao.upsertVaultItemKey(
            VaultItemKeyEntity(
                keyRef = keyRef,
                wrappingVersion = wrappedItemKey.version,
                wrappedKeyNonce = wrappedItemKey.nonce,
                wrappedKeyCiphertext = wrappedItemKey.ciphertext,
                createdAt = now,
                rotatedAt = now,
            )
        )
        dao.upsertVaultItem(
            VaultItemEntity(
                memoryRef = memoryRef,
                keyRef = keyRef,
                cryptoVersion = payload.version,
                nonce = payload.nonce,
                ciphertext = payload.ciphertext,
                aad = memoryRef,
                createdAt = now,
                updatedAt = now,
            )
        )
        MemoryPolicyService.upsertPolicy(policy)
        runCatching { LocalEmbeddingService.upsertEmbedding(memoryRef, plaintext) }

        if (MemoryPolicyService.shouldQueueForEncryptedSync(context, policy)) {
            val prepared = MemorySyncPreparationService.buildPreparedSyncPayload(
                memoryRef = memoryRef,
                cryptoVersion = payload.version,
                nonce = payload.nonce,
                ciphertext = payload.ciphertext,
                aad = memoryRef,
                keyRef = keyRef,
                wrappingVersion = wrappedItemKey.version,
                wrappedKeyNonce = wrappedItemKey.nonce,
                wrappedKeyCiphertext = wrappedItemKey.ciphertext,
            )
            MemorySyncPreparationService.enqueueUpsert(memoryRef, prepared)
        } else {
            MemorySyncPreparationService.cancelQueuedForMemoryRef(
                memoryRef,
                reason = "Policy/mode does not allow sync preparation",
            )
        }

        VaultCrypto.destroy(itemKey)
        VaultCrypto.destroy(master)
        true
    }

    fun putTextBlocking(
        context: Context,
        memoryRef: String,
        plaintext: String,
        policy: MemoryPolicyMetadata,
    ): Boolean {
        return runCatching {
            runBlocking(Dispatchers.IO) { putText(context, memoryRef, plaintext, policy) }
        }.getOrDefault(false)
    }

    suspend fun getText(context: Context, memoryRef: String): String? = withContext(Dispatchers.IO) {
        if (VaultLockStateManager.isLocked(context)) return@withContext null
        val master = VaultKeyManager.getUnlockedMasterKey(context) ?: return@withContext null

        val dao = MyApplication.database.memoryVaultDao()
        val item = dao.getVaultItem(memoryRef) ?: run {
            VaultCrypto.destroy(master)
            return@withContext null
        }
        val key = dao.getVaultKey(item.keyRef) ?: run {
            VaultCrypto.destroy(master)
            return@withContext null
        }

        val unwrappedKey = runCatching {
            VaultCrypto.decryptAesGcm(
                keyBytes = master,
                envelope = CipherEnvelope(
                    version = key.wrappingVersion,
                    nonce = key.wrappedKeyNonce,
                    ciphertext = key.wrappedKeyCiphertext,
                ),
                aad = item.keyRef.toByteArray(Charsets.UTF_8),
            )
        }.getOrNull() ?: run {
            VaultCrypto.destroy(master)
            return@withContext null
        }

        val plain = runCatching {
            VaultCrypto.decryptAesGcm(
                keyBytes = unwrappedKey,
                envelope = CipherEnvelope(
                    version = item.cryptoVersion,
                    nonce = item.nonce,
                    ciphertext = item.ciphertext,
                ),
                aad = (item.aad ?: memoryRef).toByteArray(Charsets.UTF_8),
            )
        }.getOrNull()

        VaultCrypto.destroy(unwrappedKey)
        VaultCrypto.destroy(master)
        plain?.toString(Charsets.UTF_8)
    }

    fun getTextBlocking(context: Context, memoryRef: String): String? {
        return runCatching {
            runBlocking(Dispatchers.IO) { getText(context, memoryRef) }
        }.getOrNull()
    }

    suspend fun getUpdatedAt(memoryRef: String): Long? = withContext(Dispatchers.IO) {
        MyApplication.database.memoryVaultDao().getUpdatedAt(memoryRef)
    }

    fun getUpdatedAtBlocking(memoryRef: String): Long? {
        return runCatching { runBlocking(Dispatchers.IO) { getUpdatedAt(memoryRef) } }.getOrNull()
    }

    suspend fun deleteMemoryRef(memoryRef: String) = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        val item = dao.getVaultItem(memoryRef)
        dao.deleteVaultItem(memoryRef)
        item?.let { dao.deleteVaultKey(it.keyRef) }
        dao.deletePolicy(memoryRef)
        dao.deleteEmbedding(memoryRef)
        MemorySyncPreparationService.cancelQueuedForMemoryRef(memoryRef, "Memory deleted locally")
    }

    suspend fun listMemoryRefsByPrefix(prefix: String): List<String> = withContext(Dispatchers.IO) {
        MyApplication.database.memoryVaultDao().listMemoryRefsByPrefix("$prefix%")
    }

    suspend fun deleteAllScreenOcrArtifacts() = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        val refs = dao.listMemoryRefsByPrefix("file:screen_captures/%")
        refs.forEach { ref -> deleteMemoryRef(ref) }
        MyApplication.database.memoryChunkDao().deleteBySource("screen_capture")
    }

    fun deleteAllScreenOcrArtifactsBlocking() {
        runCatching { runBlocking(Dispatchers.IO) { deleteAllScreenOcrArtifacts() } }
    }

    suspend fun enforceScreenOcrRetention(context: Context) = withContext(Dispatchers.IO) {
        val days = MemoryModeManager.getScreenOcrRetentionDays(context)
        val beforeTs = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
        val dao = MyApplication.database.memoryVaultDao()
        val refs = dao.listExpiredMemoryRefs(
            sourceType = MemorySourceType.SCREEN_OCR.name.lowercase(Locale.US),
            beforeTs = beforeTs,
        )
        refs.forEach { ref -> deleteMemoryRef(ref) }
        MyApplication.database.memoryChunkDao().deleteSourceOlderThan("screen_capture", beforeTs)
    }

    fun enforceScreenOcrRetentionBlocking(context: Context) {
        runCatching { runBlocking(Dispatchers.IO) { enforceScreenOcrRetention(context) } }
    }

    suspend fun exportSnapshotJson(): JSONObject = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        val out = JSONObject()
        out.put("schema_version", 1)

        val items = JSONArray()
        dao.listVaultItems().forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("memoryRef", item.memoryRef)
                    .put("keyRef", item.keyRef)
                    .put("cryptoVersion", item.cryptoVersion)
                    .put("nonce", b64(item.nonce))
                    .put("ciphertext", b64(item.ciphertext))
                    .put("aad", item.aad)
                    .put("createdAt", item.createdAt)
                    .put("updatedAt", item.updatedAt)
            )
        }
        out.put("vault_items", items)

        val keys = JSONArray()
        dao.listVaultKeys().forEach { key ->
            keys.put(
                JSONObject()
                    .put("id", key.id)
                    .put("keyRef", key.keyRef)
                    .put("wrappingVersion", key.wrappingVersion)
                    .put("wrappedKeyNonce", b64(key.wrappedKeyNonce))
                    .put("wrappedKeyCiphertext", b64(key.wrappedKeyCiphertext))
                    .put("createdAt", key.createdAt)
                    .put("rotatedAt", key.rotatedAt)
            )
        }
        out.put("vault_item_keys", keys)

        val policies = JSONArray()
        dao.listPolicies().forEach { p ->
            policies.put(
                JSONObject()
                    .put("memoryRef", p.memoryRef)
                    .put("sourceType", p.sourceType)
                    .put("sensitivityLevel", p.sensitivityLevel)
                    .put("syncEligibility", p.syncEligibility)
                    .put("retentionPolicy", p.retentionPolicy)
                    .put("derivedFromIdsCsv", p.derivedFromIdsCsv)
                    .put("provenance", p.provenance)
                    .put("containsPotentialSecrets", p.containsPotentialSecrets)
                    .put("requiresExplicitConsentForCloud", p.requiresExplicitConsentForCloud)
                    .put("sourceTimestampMs", p.sourceTimestampMs)
                    .put("createdAt", p.createdAt)
                    .put("updatedAt", p.updatedAt)
            )
        }
        out.put("memory_policy_metadata", policies)

        out.put("sync_payload_manifest", jsonArrayPayloads(dao.listPayloadManifests()))
        out.put("sync_preparation_queue", jsonArrayQueue(dao.listSyncQueue()))
        out.put("local_embedding_store", jsonArrayEmbeddings(dao.listEmbeddings()))
        out.put("local_search_index_state", jsonArrayIndexState(dao.listIndexState()))
        out.put("migration_state", jsonArrayMigrationState(dao.listMigrationState()))

        dao.getModePreference()?.let {
            out.put("memory_mode_preferences", jsonModePreference(it))
        }
        dao.getLockState()?.let {
            out.put("vault_lock_state", jsonLockState(it))
        }

        out
    }

    fun exportSnapshotJsonBlocking(): JSONObject {
        return runBlocking(Dispatchers.IO) { exportSnapshotJson() }
    }

    suspend fun importSnapshotJson(snapshot: JSONObject) = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()

        snapshot.optJSONArray("vault_item_keys")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.upsertVaultItemKey(
                    VaultItemKeyEntity(
                        id = o.optLong("id", 0L),
                        keyRef = o.optString("keyRef"),
                        wrappingVersion = o.optInt("wrappingVersion", 1),
                        wrappedKeyNonce = b64Decode(o.optString("wrappedKeyNonce")),
                        wrappedKeyCiphertext = b64Decode(o.optString("wrappedKeyCiphertext")),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        rotatedAt = o.optLong("rotatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("vault_items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.upsertVaultItem(
                    VaultItemEntity(
                        id = o.optLong("id", 0L),
                        memoryRef = o.optString("memoryRef"),
                        keyRef = o.optString("keyRef"),
                        cryptoVersion = o.optInt("cryptoVersion", 1),
                        nonce = b64Decode(o.optString("nonce")),
                        ciphertext = b64Decode(o.optString("ciphertext")),
                        aad = optNullableString(o, "aad"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("memory_policy_metadata")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.upsertPolicy(
                    MemoryPolicyMetadataEntity(
                        memoryRef = o.optString("memoryRef"),
                        sourceType = o.optString("sourceType"),
                        sensitivityLevel = o.optString("sensitivityLevel"),
                        syncEligibility = o.optString("syncEligibility"),
                        retentionPolicy = optNullableString(o, "retentionPolicy"),
                        derivedFromIdsCsv = optNullableString(o, "derivedFromIdsCsv"),
                        provenance = optNullableString(o, "provenance"),
                        containsPotentialSecrets = o.optBoolean("containsPotentialSecrets", false),
                        requiresExplicitConsentForCloud = o.optBoolean("requiresExplicitConsentForCloud", false),
                        sourceTimestampMs = o.optLong("sourceTimestampMs", System.currentTimeMillis()),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("sync_payload_manifest")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.insertPayloadManifest(
                    SyncPayloadManifestEntity(
                        id = o.optLong("id", 0L),
                        memoryRef = o.optString("memoryRef"),
                        schemaVersion = o.optInt("schemaVersion", 1),
                        payloadJson = o.optString("payloadJson"),
                        checksumSha256 = o.optString("checksumSha256"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("sync_preparation_queue")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.insertSyncQueue(
                    SyncPreparationQueueEntity(
                        id = o.optLong("id", 0L),
                        memoryRef = o.optString("memoryRef"),
                        actionType = o.optString("actionType"),
                        status = o.optString("status"),
                        payloadManifestId = o.optLong("payloadManifestId", 0L),
                        lastError = optNullableString(o, "lastError"),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("local_embedding_store")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.upsertEmbedding(
                    LocalEmbeddingStoreEntity(
                        memoryRef = o.optString("memoryRef"),
                        embeddingJson = o.optString("embeddingJson"),
                        tagsJson = o.optString("tagsJson"),
                        modelVersion = o.optString("modelVersion"),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("local_search_index_state")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.upsertIndexState(
                    LocalSearchIndexStateEntity(
                        stateKey = o.optString("stateKey"),
                        stateValue = o.optString("stateValue"),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONArray("migration_state")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                dao.upsertMigrationState(
                    MigrationStateEntity(
                        migrationKey = o.optString("migrationKey"),
                        status = o.optString("status"),
                        lastProcessedRef = optNullableString(o, "lastProcessedRef"),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    )
                )
            }
        }

        snapshot.optJSONObject("memory_mode_preferences")?.let { o ->
            dao.upsertModePreference(
                MemoryModePreferenceEntity(
                    id = o.optInt("id", 1),
                    selectedMode = o.optString("selectedMode", MemoryPrivacyMode.PRIVATE_LOCAL.name),
                    screenOcrRetentionDays = o.optInt("screenOcrRetentionDays", 7),
                    screenOcrCaptureEnabled = o.optBoolean("screenOcrCaptureEnabled", true),
                    explicitFactsSyncEnabled = o.optBoolean("explicitFactsSyncEnabled", true),
                    dailyFactsSyncEnabled = o.optBoolean("dailyFactsSyncEnabled", true),
                    screenOcrSyncEnabled = o.optBoolean("screenOcrSyncEnabled", false),
                    derivedSummariesSyncEnabled = o.optBoolean("derivedSummariesSyncEnabled", false),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                )
            )
        }

        snapshot.optJSONObject("vault_lock_state")?.let { o ->
            dao.upsertLockState(
                VaultLockStateEntity(
                    id = o.optInt("id", 1),
                    isLocked = o.optBoolean("isLocked", false),
                    requiresPassphrase = o.optBoolean("requiresPassphrase", false),
                    lockedAt = o.optLong("lockedAt", 0L),
                    lastUnlockedAt = o.optLong("lastUnlockedAt", 0L),
                )
            )
        }
    }

    fun importSnapshotJsonBlocking(snapshot: JSONObject) {
        runBlocking(Dispatchers.IO) { importSnapshotJson(snapshot) }
    }

    suspend fun resetAllVaultTables() = withContext(Dispatchers.IO) {
        val dao = MyApplication.database.memoryVaultDao()
        dao.deleteAllSyncQueue()
        dao.deleteAllSyncPayloads()
        dao.deleteAllEmbeddings()
        dao.deleteAllIndexState()
        dao.deleteAllPolicies()
        dao.deleteAllVaultItems()
        dao.deleteAllVaultKeys()
        dao.deleteAllMigrationState()
        dao.deleteAllModePreferences()
        dao.deleteAllLockState()
    }

    fun resetAllVaultTablesBlocking() {
        runCatching { runBlocking(Dispatchers.IO) { resetAllVaultTables() } }
    }

    private fun keyRefFor(memoryRef: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(memoryRef.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64Decode(raw: String): ByteArray = Base64.decode(raw, Base64.NO_WRAP)

    private fun optNullableString(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val value = obj.optString(key, "")
        return value.takeIf { it.isNotBlank() }
    }

    private fun jsonArrayPayloads(list: List<SyncPayloadManifestEntity>): JSONArray {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("memoryRef", item.memoryRef)
                    .put("schemaVersion", item.schemaVersion)
                    .put("payloadJson", item.payloadJson)
                    .put("checksumSha256", item.checksumSha256)
                    .put("createdAt", item.createdAt)
            )
        }
        return arr
    }

    private fun jsonArrayQueue(list: List<SyncPreparationQueueEntity>): JSONArray {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("memoryRef", item.memoryRef)
                    .put("actionType", item.actionType)
                    .put("status", item.status)
                    .put("payloadManifestId", item.payloadManifestId)
                    .put("lastError", item.lastError)
                    .put("createdAt", item.createdAt)
                    .put("updatedAt", item.updatedAt)
            )
        }
        return arr
    }

    private fun jsonArrayEmbeddings(list: List<LocalEmbeddingStoreEntity>): JSONArray {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject()
                    .put("memoryRef", item.memoryRef)
                    .put("embeddingJson", item.embeddingJson)
                    .put("tagsJson", item.tagsJson)
                    .put("modelVersion", item.modelVersion)
                    .put("updatedAt", item.updatedAt)
            )
        }
        return arr
    }

    private fun jsonArrayIndexState(list: List<LocalSearchIndexStateEntity>): JSONArray {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject()
                    .put("stateKey", item.stateKey)
                    .put("stateValue", item.stateValue)
                    .put("updatedAt", item.updatedAt)
            )
        }
        return arr
    }

    private fun jsonArrayMigrationState(list: List<MigrationStateEntity>): JSONArray {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject()
                    .put("migrationKey", item.migrationKey)
                    .put("status", item.status)
                    .put("lastProcessedRef", item.lastProcessedRef)
                    .put("updatedAt", item.updatedAt)
            )
        }
        return arr
    }

    private fun jsonModePreference(item: MemoryModePreferenceEntity): JSONObject {
        return JSONObject()
            .put("id", item.id)
            .put("selectedMode", item.selectedMode)
            .put("screenOcrRetentionDays", item.screenOcrRetentionDays)
            .put("screenOcrCaptureEnabled", item.screenOcrCaptureEnabled)
            .put("explicitFactsSyncEnabled", item.explicitFactsSyncEnabled)
            .put("dailyFactsSyncEnabled", item.dailyFactsSyncEnabled)
            .put("screenOcrSyncEnabled", item.screenOcrSyncEnabled)
            .put("derivedSummariesSyncEnabled", item.derivedSummariesSyncEnabled)
            .put("updatedAt", item.updatedAt)
    }

    private fun jsonLockState(item: VaultLockStateEntity): JSONObject {
        return JSONObject()
            .put("id", item.id)
            .put("isLocked", item.isLocked)
            .put("requiresPassphrase", item.requiresPassphrase)
            .put("lockedAt", item.lockedAt)
            .put("lastUnlockedAt", item.lastUnlockedAt)
    }
}
