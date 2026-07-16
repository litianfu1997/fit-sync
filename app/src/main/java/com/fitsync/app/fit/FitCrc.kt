package com.fitsync.app.fit

/**
 * FIT CRC-16 as defined by the Garmin FIT protocol.
 */
object FitCrc {
    private val table: IntArray = IntArray(16) { n ->
        var crc = n
        repeat(4) {
            crc = if (crc and 1 != 0) {
                0x0CC1 xor (crc shr 1)
            } else {
                crc shr 1
            }
        }
        crc
    }

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            val byte = bytes[i].toInt() and 0xFF
            var tmp = table[crc and 0x0F]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[byte and 0x0F]
            tmp = table[crc and 0x0F]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[(byte shr 4) and 0x0F]
        }
        return crc and 0xFFFF
    }

    fun readUInt16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}
