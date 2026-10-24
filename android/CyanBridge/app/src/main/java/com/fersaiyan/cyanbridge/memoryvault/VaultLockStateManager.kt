package com.fersaiyan.cyanbridge.memoryvault

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.entity.VaultLockStateEntity
import com.fersaiyan.cyanbridge.memoryvault.crypto.VaultKeyManager
import com.fersaiyan.cyanbridge.shared.prefs.MemoryPrefs.VaultLock
import com.fersaiyan.cyanbridge.shared.prefs.clear
import com.fersaiyan.cyanbridge.shared.prefs.edit
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.set
import com.fersaiyan.cyanbridge.shared.prefs.store
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object VaultLockStateManager {
    fun isLocked(context: Context): Boolean = VaultLock.locked[context]

    fun requiresPassphrase(context: Context): Boolean = VaultKeyManager.requiresPassphrase(context)

    fun lock(context: Context) {
        val now = System.currentTimeMillis()
        VaultLock.edit(context) {
            this[VaultLock.locked] = true
            this[VaultLock.lockedAt] = now
        }
        VaultKeyManager.lock()
        persist(context)
    }

    fun unlockWithDevice(context: Context): Boolean {
        if (VaultKeyManager.requiresPassphrase(context)) return false
        val ok = VaultKeyManager.unlockWithDevice(context)
        if (ok) markUnlocked(context)
        return ok
    }

    fun unlockWithPassphrase(context: Context, passphrase: CharArray): Boolean {
        val ok = VaultKeyManager.unlockWithPassphrase(context, passphrase)
        if (ok) markUnlocked(context)
        return ok
    }

    private fun markUnlocked(context: Context) {
        val now = System.currentTimeMillis()
        VaultLock.edit(context) {
            this[VaultLock.locked] = false
            this[VaultLock.lastUnlockedAt] = now
        }
        persist(context)
    }

    fun setPassphrase(context: Context, passphrase: CharArray): Boolean {
        val ok = VaultKeyManager.setPassphrase(context, passphrase)
        if (ok) {
            VaultLock.locked[context] = true
            VaultKeyManager.lock()
            persist(context)
        }
        return ok
    }

    fun clearPassphrase(context: Context) {
        VaultKeyManager.clearPassphrase(context)
        persist(context)
    }

    fun clearAll(context: Context) {
        VaultLock.clear(context)
        VaultKeyManager.clearAll(context)
        runCatching {
            runBlocking(Dispatchers.IO) {
                MyApplication.database.memoryVaultDao().deleteAllLockState()
            }
        }
    }

    private fun persist(context: Context) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                val p = VaultLock.store(context)
                MyApplication.database.memoryVaultDao().upsertLockState(
                    VaultLockStateEntity(
                        isLocked = p[VaultLock.locked],
                        requiresPassphrase = VaultKeyManager.requiresPassphrase(context),
                        lockedAt = p[VaultLock.lockedAt],
                        lastUnlockedAt = p[VaultLock.lastUnlockedAt],
                    )
                )
            }
        }
    }
}
