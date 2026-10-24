package com.fersaiyan.cyanbridge.memoryvault.contracts

data class WrappedKeyManifest(
    val keyRef: String,
    val wrappingVersion: Int,
    val wrappedKeyNonceB64: String,
    val wrappedKeyCiphertextB64: String,
)

data class EncryptedMemoryBlobManifest(
    val memoryRef: String,
    val cryptoVersion: Int,
    val nonceB64: String,
    val ciphertextB64: String,
    val aad: String?,
)

data class MemorySyncRecord(
    val memoryRef: String,
    val actionType: String,
    val policyVersion: Int,
    val keyManifest: WrappedKeyManifest,
    val blobManifest: EncryptedMemoryBlobManifest,
)

data class MemorySyncBatchRequest(
    val deviceId: String,
    val records: List<MemorySyncRecord>,
)

data class MemorySyncBatchResponse(
    val acceptedRefs: List<String>,
    val rejectedRefs: List<String>,
    val serverCursor: String?,
)

data class CloudMemorySearchRequest(
    val query: String,
    val limit: Int,
    val includeSources: List<String>,
)

data class CloudMemorySearchHit(
    val memoryRef: String,
    val score: Double,
    val snippet: String,
)

data class CloudMemorySearchResponse(
    val hits: List<CloudMemorySearchHit>,
)

data class DeviceEnrollmentRequest(
    val deviceId: String,
    val publicKeyPem: String,
    val appVersion: String,
)

data class DeviceEnrollmentResponse(
    val enrollmentId: String,
    val encryptedBootstrap: String,
)

interface MemorySyncApi {
    suspend fun uploadBatch(request: MemorySyncBatchRequest): Result<MemorySyncBatchResponse>
}

interface CloudMemorySearchApi {
    suspend fun search(request: CloudMemorySearchRequest): Result<CloudMemorySearchResponse>
}

interface VaultDeviceEnrollmentApi {
    suspend fun enroll(request: DeviceEnrollmentRequest): Result<DeviceEnrollmentResponse>
}

class NotConfiguredMemorySyncApi : MemorySyncApi {
    override suspend fun uploadBatch(request: MemorySyncBatchRequest): Result<MemorySyncBatchResponse> {
        return Result.failure(IllegalStateException("Memory sync backend is not configured"))
    }
}

class NotConfiguredCloudMemorySearchApi : CloudMemorySearchApi {
    override suspend fun search(request: CloudMemorySearchRequest): Result<CloudMemorySearchResponse> {
        return Result.failure(IllegalStateException("Cloud memory search backend is not configured"))
    }
}

class NotConfiguredVaultDeviceEnrollmentApi : VaultDeviceEnrollmentApi {
    override suspend fun enroll(request: DeviceEnrollmentRequest): Result<DeviceEnrollmentResponse> {
        return Result.failure(IllegalStateException("Vault enrollment backend is not configured"))
    }
}
