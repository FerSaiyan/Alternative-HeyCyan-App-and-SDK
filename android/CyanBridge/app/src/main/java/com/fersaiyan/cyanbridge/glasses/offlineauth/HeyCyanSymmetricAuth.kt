package com.fersaiyan.cyanbridge.glasses.offlineauth

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The RCSP **symmetric** challenge-response: the glasses send a random challenge, the controller
 * answers with a block derived from the challenge and the shared [HeyCyanAuthProtocol.DEFAULT_LINK_KEY].
 * The glasses compute the same block and compare. Because the key is the public SDK default, this is
 * owner-replicable and needs no server — it is the device's own handshake, not a forged server token.
 *
 * The block cipher is the JieLi RCSP primitive ([JieLiRcspCipher]) that stock jl_bt_ota RcspAuth uses; it is
 * not AES. It is isolated behind [ChallengeCipher] so the production path and the simulated glasses use
 * the SAME instance and the end-to-end flow stays self-consistent.
 */
class HeyCyanSymmetricAuth(
    private val key: ByteArray = HeyCyanAuthProtocol.DEFAULT_LINK_KEY,
    private val cipher: ChallengeCipher = JieLiRcspCipher,
) {
    init {
        require(key.size == 16) { "link key must be 16 bytes" }
    }

    /** Derives the 16-byte response the glasses expect for [challenge]. */
    fun respondTo(challenge: ByteArray): ByteArray {
        require(challenge.size == HeyCyanAuthProtocol.CHALLENGE_LEN) {
            "challenge must be ${HeyCyanAuthProtocol.CHALLENGE_LEN} bytes"
        }
        return cipher.transform(key, challenge)
    }

    /** Glasses-side check (used by the simulator and by verification tests). */
    fun verify(challenge: ByteArray, response: ByteArray): Boolean =
        respondTo(challenge).contentEquals(response)

    /** A reversible 16-byte block transform keyed by the 16-byte link key. */
    fun interface ChallengeCipher {
        fun transform(key: ByteArray, block: ByteArray): ByteArray
    }

    /**
     * Optional alternate — AES-128-ECB(block, key). **Not the on-device cipher** (the A02S image has no
     * AES S-box); kept only as a well-known keyed-block option for experimentation, never a default.
     */
    object AesEcbCipher : ChallengeCipher {
        override fun transform(key: ByteArray, block: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                doFinal(block)
            }
    }

    /**
     * Optional alternate — XTEA over the two 64-bit halves of the 16-byte block, 32 rounds. The
     * HeyCyan **server** path uses XTEA, but the native RCSP handshake does NOT; kept
     * only as an experimentation option, never a default.
     */
    object XteaCipher : ChallengeCipher {
        private const val ROUNDS = 32
        private const val DELTA = 0x9E3779B9.toInt()

        override fun transform(key: ByteArray, block: ByteArray): ByteArray {
            val k = IntArray(4) { readLe(key, it * 4) }
            val out = ByteArray(16)
            encryptBlock(k, readLe(block, 0), readLe(block, 4)).let { (v0, v1) ->
                writeLe(out, 0, v0); writeLe(out, 4, v1)
            }
            encryptBlock(k, readLe(block, 8), readLe(block, 12)).let { (v0, v1) ->
                writeLe(out, 8, v0); writeLe(out, 12, v1)
            }
            return out
        }

        private fun encryptBlock(k: IntArray, x0: Int, x1: Int): Pair<Int, Int> {
            var v0 = x0
            var v1 = x1
            var sum = 0
            repeat(ROUNDS) {
                v0 += ((v1 shl 4) xor (v1 ushr 5)) + v1 xor (sum + k[sum and 3])
                sum += DELTA
                v1 += ((v0 shl 4) xor (v0 ushr 5)) + v0 xor (sum + k[(sum ushr 11) and 3])
            }
            return v0 to v1
        }

        private fun readLe(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)

        private fun writeLe(b: ByteArray, off: Int, v: Int) {
            b[off] = v.toByte()
            b[off + 1] = (v ushr 8).toByte()
            b[off + 2] = (v ushr 16).toByte()
            b[off + 3] = (v ushr 24).toByte()
        }
    }
}
