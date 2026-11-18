package com.fersaiyan.cyanbridge.glasses.offlineauth

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The RCSP **symmetric** challenge-response: the glasses send a random challenge, the controller
 * answers with a block derived from the challenge and the shared [HeyCyanAuthProtocol.DEFAULT_LINK_KEY].
 * The glasses compute the same block and compare. Because the key is the public SDK default, this is
 * owner-replicable and needs no server — it is the device's own handshake, not a forged server token.
 *
 * The exact block cipher is the one piece still pending byte-accurate confirmation. Per add.78's
 * crypto scan of the A02S firmware, the on-device RCSP primitive is a **custom JieLi block cipher**
 * (key-schedule FUN_0603b7d8 / 8-round SPN core FUN_0603b92c, per add.75), used by stock jl_bt_ota
 * RcspAuth's getEncryptedAuthData/getAuthData — it is **NOT AES** (no AES S-box exists in the image)
 * and not XTEA. The default here is therefore an explicitly-labeled placeholder, not AES, so nobody
 * hardens around the wrong cipher. It is isolated behind [ChallengeCipher] so swapping in the verified
 * JieLi cipher is a one-line change; the production path and the simulated glasses use the SAME
 * instance, so the end-to-end flow is self-consistent regardless of which concrete cipher is used.
 */
class HeyCyanSymmetricAuth(
    private val key: ByteArray = HeyCyanAuthProtocol.DEFAULT_LINK_KEY,
    private val cipher: ChallengeCipher = JieLiRcspPlaceholderCipher,
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
     * DEFAULT — **JieLi RCSP native cipher placeholder (UNVERIFIED; NOT the real on-device cipher,
     * NOT AES)**. A deterministic keyed 8-round substitution/permutation stand-in (nodding to the
     * JieLi 8-round SPN lineage) so the simulator and app agree and the end-to-end flow is validated.
     * The authoritative transform is the custom JieLi cipher in the jl_bt_ota native lib
     * (FUN_0603b7d8 key-schedule / FUN_0603b92c core); drop that in here and validate on the owner
     * device before any hardware use. Do NOT harden around this or around AES.
     */
    object JieLiRcspPlaceholderCipher : ChallengeCipher {
        override fun transform(key: ByteArray, block: ByteArray): ByteArray {
            val state = block.copyOf()
            for (round in 0 until 8) {
                for (i in state.indices) {
                    val mixed = (state[i].toInt() and 0xFF) xor (key[(i + round) % key.size].toInt() and 0xFF)
                    // keyed add + rotate: deterministic, keyed, non-cryptographic placeholder.
                    val rotated = ((mixed + round + i) and 0xFF)
                    state[i] = (((rotated shl 1) or (rotated ushr 7)) and 0xFF).toByte()
                }
            }
            return state
        }
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
     * HeyCyan **server** path uses XTEA, but add.78 confirms the native RCSP handshake does NOT; kept
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
