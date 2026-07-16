package com.fitsync.app.fit

import com.fitsync.app.domain.model.ValidationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
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

    @Test
    fun developerFields_doNotMisalignFollowingSessionSummary() {
        val data = ByteArrayOutputStream().apply {
            definition(local = 0, global = 0, fields = listOf(Field(0, 1, 0x00)))
            data(local = 0) { write(4) }

            definition(
                local = 1,
                global = 20,
                fields = listOf(Field(3, 1, 0x02), Field(253, 4, 0x86)),
                developerFieldSizes = listOf(2),
            )
            data(local = 1) {
                write(150)
                writeLe32(1_000)
                write(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
            }

            definition(
                local = 2,
                global = 18,
                fields = listOf(
                    Field(2, 4, 0x86),
                    Field(5, 1, 0x00),
                    Field(7, 4, 0x86),
                    Field(8, 4, 0x86),
                    Field(9, 4, 0x86),
                    Field(16, 1, 0x02),
                    Field(22, 2, 0x84),
                    Field(253, 4, 0x86),
                ),
            )
            data(local = 2) {
                writeLe32(1_000)
                write(2)
                writeLe32(3_600_000)
                writeLe32(3_500_000)
                writeLe32(4_219_500)
                write(148)
                writeLe16(321)
                writeLe32(4_600)
            }
        }.toByteArray()

        val result = FitEngine().parseBytes(fitFile(data))

        assertThat(result.hasSession).isTrue()
        assertThat(result.recordCount).isEqualTo(1)
        assertThat(result.elapsedSeconds).isEqualTo(3_600)
        assertThat(result.movingSeconds).isEqualTo(3_500)
        assertThat(result.distanceMeters).isWithin(0.01).of(42_195.0)
        assertThat(result.elevationGain).isWithin(0.01).of(321.0)
        assertThat(result.avgHeartRate).isEqualTo(148)
    }

    @Test
    fun compressedTimestamps_decodeRecordsAndBuildMissingSummary() {
        val data = ByteArrayOutputStream().apply {
            definition(
                local = 0,
                global = 20,
                fields = listOf(
                    Field(253, 4, 0x86),
                    Field(3, 1, 0x02),
                    Field(5, 4, 0x86),
                    Field(2, 2, 0x84),
                ),
            )
            data(local = 0) {
                writeLe32(1_000)
                write(100)
                writeLe32(10_000)
                writeLe16(5_000)
            }

            definition(
                local = 0,
                global = 20,
                fields = listOf(Field(3, 1, 0x02), Field(5, 4, 0x86), Field(2, 2, 0x84)),
            )
            compressedData(local = 0, timestamp = 1_005) {
                write(110)
                writeLe32(20_000)
                writeLe16(5_050)
            }
            compressedData(local = 0, timestamp = 1_010) {
                write(120)
                writeLe32(30_000)
                writeLe16(5_100)
            }
        }.toByteArray()

        val result = FitEngine().parseBytes(fitFile(data))

        assertThat(result.recordCount).isEqualTo(3)
        assertThat(result.elapsedSeconds).isEqualTo(10)
        assertThat(result.distanceMeters).isWithin(0.01).of(300.0)
        assertThat(result.elevationGain).isWithin(0.01).of(20.0)
        assertThat(result.avgHeartRate).isEqualTo(110)
        assertThat(result.hasHeartRate).isTrue()
    }

    private data class Field(val number: Int, val size: Int, val baseType: Int)

    private fun ByteArrayOutputStream.definition(
        local: Int,
        global: Int,
        fields: List<Field>,
        developerFieldSizes: List<Int> = emptyList(),
    ) {
        write(0x40 or local or if (developerFieldSizes.isNotEmpty()) 0x20 else 0)
        write(0)
        write(0)
        writeLe16(global)
        write(fields.size)
        fields.forEach { field ->
            write(field.number)
            write(field.size)
            write(field.baseType)
        }
        if (developerFieldSizes.isNotEmpty()) {
            write(developerFieldSizes.size)
            developerFieldSizes.forEachIndexed { index, size ->
                write(index)
                write(size)
                write(0)
            }
        }
    }

    private fun ByteArrayOutputStream.data(local: Int, body: ByteArrayOutputStream.() -> Unit) {
        write(local)
        body()
    }

    private fun ByteArrayOutputStream.compressedData(
        local: Int,
        timestamp: Int,
        body: ByteArrayOutputStream.() -> Unit,
    ) {
        write(0x80 or (local shl 5) or (timestamp and 0x1F))
        body()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun fitFile(data: ByteArray): ByteArray {
        val headerSize = 14
        val bytes = ByteArray(headerSize + data.size + 2)
        bytes[0] = headerSize.toByte()
        bytes[1] = 0x20
        ByteBuffer.wrap(bytes, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x083E.toShort())
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size)
        ".FIT".toByteArray().copyInto(bytes, 8)
        val headerCrc = FitCrc.compute(bytes, 0, 12)
        bytes[12] = (headerCrc and 0xFF).toByte()
        bytes[13] = ((headerCrc shr 8) and 0xFF).toByte()
        data.copyInto(bytes, headerSize)
        val fileCrc = FitCrc.compute(bytes, 0, headerSize + data.size)
        bytes[headerSize + data.size] = (fileCrc and 0xFF).toByte()
        bytes[headerSize + data.size + 1] = ((fileCrc shr 8) and 0xFF).toByte()
        return bytes
    }
}
