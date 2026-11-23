package com.fersaiyan.cyanbridge.glasses.offlineauth

/**
 * The JieLi RCSP symmetric block cipher used by the HeyCyan A02S-family connection-auth handshake: a
 * 16-byte-block, 16-byte-key, 8-round substitution-permutation network with two 256-byte S-boxes and a key
 * schedule driven by a constant table. It is not AES or TEA.
 *
 * It restores the device owner's own access to their own glasses via the device's own symmetric
 * handshake (the public SDK-default link key); it mints no server signature and contacts no server.
 */
object JieLiRcspCipher : HeyCyanSymmetricAuth.ChallengeCipher {

    /** The cipher direction the glasses' RCSP verify uses. */
    const val RCSP_DIR = 1

    // 16-bit index selector. Bits set at {0,3,4,7,8,11,12,15} ("S1-set"): drive S-box choice and the
    // key-add polarity (STEP2/final XOR-when-set; STEP4 opposite).
    private const val MASK = 0x9999

    // Fixed byte permutation: out[i] = in[PERM[i]].
    private val PERM = intArrayOf(8, 11, 12, 15, 2, 1, 6, 5, 10, 9, 14, 13, 0, 7, 4, 3)

    // Cipher tables.
    private val SBOX1 = hex(
        "012de293be4515ae780387a4b838cf3f08670994eb26a86bbd18341bbbbf72f74035489c512f3b55e3c09fd8d3f38db1" +
            "ffa73edc8677d7a611fbf4ba92916483f133efda2cb5b22b88d199cb8c841d14819771ca5fa38b573c82c4525c1ce8a0" +
            "04b4854af61354b6df0c1a8edee039fc209b244ea9989eabf260d06ceafac7d900d41f6e43bcec5389fe7a5d49c932c2" +
            "f99af86d16db599644e9cde646428f0ac1ccb965b0d2c6ac1e4162292e0e7450025ac3257b8a2a5bf0060d476f709d7e" +
            "10ce1227d54c4fd679306836757de4ed806a9037a25e76aac57f3dafa5e51961fd4d7cb70beead4b22f5e7732321c805" +
            "e166ddb3586963560fa1319517073a28",
    )
    private val SBOX2 = hex(
        "8000b00960efb9fd10129fe469baadf8c038c2654f0694fc19de6a1b5d4ea88270ede8ec72b315c3ffabb6474401ac25" +
            "c9fa8e411a21cbd30d6efe2658da320f20a99d8498059cbb228c63e7c5e173c6af245b876627f757f496b1b75c8bd554" +
            "79dfaaf63ea3f111caf5d1177b9383bcbd521eebaeccd63508c88ab4e2cdbfd9d050593f4d62340a4888b5564c2e6b9e" +
            "d23d3c0313fb9751754a917123be762a5ff9d4550bdc37311674d777a7e607dba42f46f3614567e30ca23b1c8518041d" +
            "29a08fb25ad8a67eee8d534ba19ac10e7a49a52c81c4c7362b7f439533f26c686df00228cedd9bea5e997c1486cfe542" +
            "b840782d3ae9641f92907d396fe08930",
    )
    private val KCONST_WIDE = hex(
        "fe7204061e740406427404068474040677f156247e471b86bd708e1e3b73160364ac285ac9b337c50a10b7a3bab19746" +
            "3d05dc666ef69af80d589567c6aaabeca0689b96d4ebbf434936e96a89d8c38a946399bc7bbec122bb5c71d51f92575d" +
            "8f44411d51e64017fbfd193234b8612aca236fda39f7a2017fd631e7de8004dd2c5982afa8e00fcda1123e30d11cd03a" +
            "33722e4f9002130675ce87c2efb2ad7d3815e1529f7a6c2f27c4e281a9cf8dc0d7dfff6076148c5e5509e408c74220fc" +
            "d25091d94c629ee8b9a6f91a00210bfa359c4e4b6948cb0ec8a45bea8407b418f4ae6bdba7cc3f8b4a0c3c25e5544d45" +
            "83ed11f0b05393f27426b59d6d7cf32df156247e471b86bd708e1e3b731603b6ac285ac9b337c50a10b7a3bab1974688" +
            "0000ff5500000000000f0000607d0406fa7b0406567e0406ccffffffdcffffff56feffffd85f0c06000000002e830406" +
            "000000009c830406d4830406df5f0c06",
    )

    // Offset of the key-constant window within KCONST_WIDE.
    private const val KCONST_225F_OFF = 0x2f

    private fun bit(i: Int): Int = (MASK ushr i) and 1

    /** Expand a 16-byte key to the 272-byte round-key schedule. */
    fun keySchedule(key: ByteArray): ByteArray {
        require(key.size == 16) { "link key must be 16 bytes" }
        val out = ByteArray(0x110)
        key.copyInto(out, 0, 0, 16)
        var chk = 0
        for (b in key) chk = chk xor (b.toInt() and 0xFF)
        val w = IntArray(17)
        for (i in 0 until 16) w[i] = key[i].toInt() and 0xFF
        w[16] = chk
        for (r in 0 until 16) {
            for (j in 0 until 17) {
                val v = w[j]
                w[j] = ((v shl 3) or (v ushr 5)) and 0xFF // ROL-3, cumulative across rounds
            }
            for (k in 0 until 16) {
                val word = w[(r + 1 + k) % 17]
                val kc = KCONST_WIDE[KCONST_225F_OFF + r * 0x10 - k].toInt() and 0xFF // read descending
                out[0x10 + r * 0x10 + k] = ((word + kc) and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * The 8-round SPN core. [direction] is a mode flag (the function only ever uses the
     * forward S-boxes/permutation), not encrypt/decrypt.
     */
    fun core(block: ByteArray, sched: ByteArray, direction: Int): ByteArray {
        require(block.size == 16) { "block must be 16 bytes" }
        val state = IntArray(16) { block[it].toInt() and 0xFF }
        val l = state.copyOf() // original-block copy (sp buffer); used by the round-2 premix
        for (rnd in 0 until 8) {
            val base = rnd * 0x20
            if (direction != 0 && rnd == 2) premix(state, l)
            keyAdd(state, sched, base, xorWhenBit = true) // STEP2
            for (i in 0 until 16) state[i] = (if (bit(i) == 1) SBOX1 else SBOX2)[state[i]].toInt() and 0xFF
            keyAdd(state, sched, base + 0x10, xorWhenBit = false) // STEP4 (opposite polarity)
            l2(state); perm(state); l2(state); perm(state); l2(state); perm(state); l2(state) // diffusion
        }
        keyAdd(state, sched, 0x100, xorWhenBit = true) // final whitening
        return ByteArray(16) { state[it].toByte() }
    }

    override fun transform(key: ByteArray, block: ByteArray): ByteArray =
        core(block, keySchedule(key), RCSP_DIR)

    // ---- round substeps --------------------------------------------------------------------------

    private fun keyAdd(state: IntArray, key: ByteArray, off: Int, xorWhenBit: Boolean) {
        for (i in 0 until 16) {
            val set = bit(i) == 1
            val doXor = if (xorWhenBit) set else !set
            val k = key[off + i].toInt() and 0xFF
            state[i] = if (doXor) state[i] xor k else (state[i] + k) and 0xFF
        }
    }

    private fun l2(s: IntArray) { // pairwise (a,b) -> (2a+b, a+b)
        var j = 0
        while (j < 16) {
            val a = s[j]
            val b = s[j + 1]
            s[j] = (2 * a + b) and 0xFF
            s[j + 1] = (a + b) and 0xFF
            j += 2
        }
    }

    private fun perm(s: IntArray) {
        val src = s.copyOf()
        for (i in 0 until 16) s[i] = src[PERM[i]]
    }

    private fun premix(state: IntArray, l: IntArray) { // round-2, dir!=0 premix
        for (i in 0 until 16) {
            state[i] = if (bit(i) == 1) state[i] xor l[i] else (state[i] + l[i]) and 0xFF
        }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
