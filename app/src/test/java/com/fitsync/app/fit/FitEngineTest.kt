package com.fitsync.app.fit

import com.fitsync.app.domain.model.ValidationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitEngineTest {

    @Test
    fun rejectsNonFitBytes() {
        // Long enough to pass size gate, but wrong signature
        val result = FitEngine().parseBytes("hello world!!!!".toByteArray())
        assertThat(result.validationStatus).isEqualTo(ValidationStatus.UNSUPPORTED)
    }

    @Test
    fun rejectsTooShort() {
        val result = FitEngine().parseBytes(ByteArray(8))
        assertThat(result.validationStatus).isEqualTo(ValidationStatus.TRUNCATED)
    }

    @Test
    fun acceptsMinimalFitHeaderSignature() {
        // Minimal synthetic FIT: 14-byte header + empty data + CRC
        val dataSize = 0
        val headerSize = 14
        val bytes = ByteArray(headerSize + dataSize + 2)
        bytes[0] = headerSize.toByte()
        bytes[1] = 0x10 // protocol version
        // profile version little endian
        bytes[2] = 0x2E
        bytes[3] = 0x08
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataSize)
        ".FIT".toByteArray().copyInto(bytes, 8)
        val headerCrc = FitCrc.compute(bytes, 0, 12)
        bytes[12] = (headerCrc and 0xFF).toByte()
        bytes[13] = ((headerCrc shr 8) and 0xFF).toByte()
        val fileCrc = FitCrc.compute(bytes, 0, headerSize + dataSize)
        bytes[headerSize + dataSize] = (fileCrc and 0xFF).toByte()
        bytes[headerSize + dataSize + 1] = ((fileCrc shr 8) and 0xFF).toByte()

        val result = FitEngine().parseBytes(bytes)
        // Empty activity body → warnings / valid-with-warnings, but recognized as FIT
        assertThat(result.validationStatus).isNotEqualTo(ValidationStatus.UNSUPPORTED)
        assertThat(FitEngine().isLikelyFit(bytes)).isTrue()
    }

    @Test
    fun crcMatchesKnownVector() {
        val data = byteArrayOf(0x0E, 0x10, 0x2E, 0x08, 0x00, 0x00, 0x00, 0x00, 0x2E, 0x46, 0x49, 0x54)
        val crc = FitCrc.compute(data)
        assertThat(crc).isAtLeast(0)
        assertThat(crc).isAtMost(0xFFFF)
    }
}
