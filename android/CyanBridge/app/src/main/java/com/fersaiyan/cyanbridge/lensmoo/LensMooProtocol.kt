package com.fersaiyan.cyanbridge.lensmoo

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun ByteArray.u16(i: Int) = (this[i].toInt() and 255) or ((this[i+1].toInt() and 255) shl 8)
internal fun ByteArray.u32(i: Int): Int = ByteBuffer.wrap(this, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
internal object LensMooProtocol {
    fun crc(data: ByteArray): Int {
        var crc = 65535
        for (b in data) { crc = crc xor ((b.toInt() and 255) shl 8); repeat(8) { crc = ((crc shl 1) xor (if (crc and 32768 != 0) 0x1021 else 0)) and 65535 } }
        return crc
    }
    fun envelope(head: Int, order: Int, cmd: Int, data: ByteArray, divide: Int = 0): ByteArray =
        ByteBuffer.allocate(16 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(head.toByte()).put(order.toByte()).putShort(cmd.toShort()).putShort(divide.toShort()).putShort(data.size.toShort()).putInt(0).putInt(crc(data)).put(data).array()
    fun node(order: Int, pkg: Int, urn: String, data: ByteArray, action: Int, format: Int, command: Int = 1): ByteArray {
        require(urn.length == 4 && data.size <= 3000)
        val payload = ByteBuffer.allocate(17 + data.size).order(ByteOrder.LITTLE_ENDIAN).putShort(pkg.toShort()).putInt(-1).putShort(action.toShort()).put(0).put(1).put(urn.toByteArray(Charsets.US_ASCII)).put(format.toByte()).putShort(data.size.toShort()).put(data).array()
        return envelope(0x30, order, command, payload, if(format == 2) 4 else 0)
    }
    data class Node(val pkg: Int, val urn: String, val format: Int, val data: ByteArray)
    fun nodes(frame: ByteArray): List<Node> {
        val p = frame.copyOfRange(16, frame.size); require(p.size >= 10)
        var offset = 10
        val result = mutableListOf<Node>()
        repeat(p[9].toInt() and 255) {
            require(offset + 7 <= p.size); val n = p.u16(offset+5); require(offset+7+n <= p.size)
            result += Node(p.u16(0), String(p,offset,4,Charsets.US_ASCII),p[offset+4].toInt() and 255,p.copyOfRange(offset+7,offset+7+n)); offset += 7+n
        }
        require(offset == p.size); return result
    }
    class Parser {
        private var pending = byteArrayOf()
        fun feed(data: ByteArray): List<ByteArray> {
            pending += data; require(pending.size <= 131072)
            val out = mutableListOf<ByteArray>()
            while(pending.size >= 16) {
                require((pending[0].toInt() and 255) in setOf(0x30,0x1e,0x1f,0x2b,0x1a,0x0e,0x4a)) { "Unknown SJ header" }
                val size = 16 + pending.u16(6); require(size <= 32784)
                if(pending.size < size) break
                val f = pending.copyOfRange(0,size); require(f.u32(12) == crc(f.copyOfRange(16,size))) { "SJ CRC mismatch" }
                out += f; pending = pending.copyOfRange(size,pending.size)
            }; return out
        }
    }
    fun crypt(seed: Int, data: ByteArray): ByteArray {
        var state = seed and 65535
        return data.map { byte -> var v=0; for(bit in 7 downTo 0) { val high=(state shr 15) and 1; v=(v shl 1) or (((byte.toInt() shr bit) and 1) xor high); state=((state shl 1) xor (if(high==1) 0xd103 else 0)) and 65535 }; v.toByte() }.toByteArray()
    }
    fun verify(request: ByteArray, response: ByteArray): Boolean {
        if(request.size!=64 || response.size!=64) return false
        val original=crypt(((request[14].toInt() and 255) shl 8) or (request[15].toInt() and 255),request.copyOfRange(16,48))
        val restored=crypt(request.copyOfRange(16,48).sumOf { it.toInt() and 255 },response.copyOfRange(16,48))
        return restored.indices.all { (restored[it].toInt() xor request[48+it%16].toInt()).toByte() == original[it] }
    }
}
