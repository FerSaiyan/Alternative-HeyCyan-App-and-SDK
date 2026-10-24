package com.fersaiyan.cyanbridge.shared.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PersistenceEntityTest {
    @Test
    fun memoryVaultEntityEqualityUsesIdAndContentButNotTheEmbedding() {
        val a = MemoryVaultItemEntity("x", "hello", "manual", 1L, 2L, byteArrayOf(1, 2, 3))
        val b = MemoryVaultItemEntity("x", "hello", "manual", 1L, 2L, byteArrayOf(4, 5, 6))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        assertNotEquals(a, MemoryVaultItemEntity("x", "world", "manual", 1L, 2L))
    }
}
