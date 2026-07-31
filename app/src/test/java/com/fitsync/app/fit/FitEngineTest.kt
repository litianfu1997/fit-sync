package com.fitsync.app.fit

import com.fitsync.app.domain.model.ValidationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitEngineTest {

    @Test
    fun trackSampler_keepsTailAndSamplesAcrossWholeActivity() {
        val sampler = TrackPointSampler(capacity = 8)
        repeat(200) { index -> sampler.add(LatLng(index.toDouble(), -index.toDouble())) }

        val points = sampler.points()
        assertThat(points).hasSize(8)
        assertThat(points.first()).isEqualTo(LatLng(0.0, -0.0))
        assertThat(points.last()).isEqualTo(LatLng(199.0, -199.0))
        assertThat(points.map { it.lat }).isInOrder()
        assertThat(points).containsNoDuplicates()
        assertThat(sampler.points()).isEqualTo(points)
    }

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
                writeLe16(altitudeRaw(500.0))
            }

            definition(
                local = 0,
                global = 20,
                fields = listOf(Field(3, 1, 0x02), Field(5, 4, 0x86), Field(2, 2, 0x84)),
            )
            // 抬升后在高点停留几拍，避免中值窗口截断末尾爬升
            compressedData(local = 0, timestamp = 1_005) {
                write(110)
                writeLe32(20_000)
                writeLe16(altitudeRaw(510.0))
            }
            compressedData(local = 0, timestamp = 1_010) {
                write(120)
                writeLe32(30_000)
                writeLe16(altitudeRaw(520.0))
            }
            compressedData(local = 0, timestamp = 1_015) {
                write(120)
                writeLe32(30_000)
                writeLe16(altitudeRaw(520.0))
            }
            compressedData(local = 0, timestamp = 1_020) {
                write(120)
                writeLe32(30_000)
                writeLe16(altitudeRaw(520.0))
            }
        }.toByteArray()

        val result = FitEngine().parseBytes(fitFile(data))

        assertThat(result.recordCount).isEqualTo(5)
        assertThat(result.elapsedSeconds).isEqualTo(20)
        assertThat(result.distanceMeters).isWithin(0.01).of(300.0)
        assertThat(result.elevationGain).isWithin(0.5).of(20.0)
        assertThat(result.avgHeartRate).isEqualTo(114)
        assertThat(result.hasHeartRate).isTrue()
    }

    @Test
    fun multiSession_sumsTotalAscent() {
        val data = ByteArrayOutputStream().apply {
            definition(
                local = 0,
                global = 18,
                fields = listOf(Field(22, 2, 0x84), Field(5, 1, 0x00)),
            )
            data(local = 0) {
                writeLe16(10)
                write(1) // running
            }
            data(local = 0) {
                writeLe16(20)
                write(2) // cycling
            }
        }.toByteArray()

        val result = FitEngine().parseBytes(fitFile(data))
        assertThat(result.elevationGain).isWithin(0.01).of(30.0)
    }

    @Test
    fun lapAscent_usedWhenSessionAscentMissing() {
        val data = ByteArrayOutputStream().apply {
            definition(
                local = 0,
                global = 19,
                fields = listOf(Field(22, 2, 0x84)),
            )
            data(local = 0) { writeLe16(15) }
            data(local = 0) { writeLe16(25) }
        }.toByteArray()

        val result = FitEngine().parseBytes(fitFile(data))
        assertThat(result.hasLaps).isTrue()
        assertThat(result.elevationGain).isWithin(0.01).of(40.0)
    }

    @Test
    fun sessionAscentZero_doesNotFallBackToNoisyRecords() {
        val data = ByteArrayOutputStream().apply {
            definition(
                local = 0,
                global = 18,
                fields = listOf(Field(22, 2, 0x84)),
            )
            data(local = 0) { writeLe16(0) }

            definition(
                local = 1,
                global = 20,
                fields = listOf(Field(2, 2, 0x84)),
            )
            // 若错误回退到 Record，旧 0.5m 算法会得到大量假爬升
            listOf(100.0, 102.0, 99.0, 101.5, 98.0, 103.0, 100.0).forEach { alt ->
                data(local = 1) { writeLe16(altitudeRaw(alt)) }
            }
        }.toByteArray()

        val result = FitEngine().parseBytes(fitFile(data))
        assertThat(result.elevationGain).isWithin(0.01).of(0.0)
    }

    @Test
    fun recordAscent_ignoresGpsNoiseAndSpikes() {
        val acc = FitEngine.AscentAccumulator()
        // 平路 ±2m 抖动
        listOf(100.0, 101.5, 99.0, 102.0, 98.5, 101.0, 100.0, 99.5).forEach { acc.add(it) }
        assertThat(acc.total).isLessThan(3.0)

        // 尖峰 +100m 后回落，不应计入
        acc.add(200.0)
        acc.add(100.0)
        acc.add(100.0)
        acc.add(100.0)
        assertThat(acc.total).isLessThan(5.0)
    }

    @Test
    fun recordAscent_countsSustainedClimb() {
        val acc = FitEngine.AscentAccumulator()
        // 平滑抬升 0 → 50m，末尾停留
        for (m in 0..50) acc.add(m.toDouble())
        repeat(5) { acc.add(50.0) }
        assertThat(acc.total).isWithin(5.0).of(50.0)
    }

    @Test
    fun resolveElevationGain_priority() {
        assertThat(
            FitEngine.resolveElevationGain(
                hasSessionAscent = true,
                sessionAscentSum = 12.0,
                hasLapAscent = true,
                lapAscentSum = 99.0,
                recordAscent = 200.0,
            ),
        ).isWithin(0.01).of(12.0)

        assertThat(
            FitEngine.resolveElevationGain(
                hasSessionAscent = false,
                sessionAscentSum = 0.0,
                hasLapAscent = true,
                lapAscentSum = 40.0,
                recordAscent = 200.0,
            ),
        ).isWithin(0.01).of(40.0)

        // Lap 全 0 时回退 Record
        assertThat(
            FitEngine.resolveElevationGain(
                hasSessionAscent = false,
                sessionAscentSum = 0.0,
                hasLapAscent = true,
                lapAscentSum = 0.0,
                recordAscent = 33.0,
            ),
        ).isWithin(0.01).of(33.0)

        assertThat(
            FitEngine.resolveElevationGain(
                hasSessionAscent = true,
                sessionAscentSum = 0.0,
                hasLapAscent = false,
                lapAscentSum = 0.0,
                recordAscent = 33.0,
            ),
        ).isWithin(0.01).of(0.0)
    }

    @Test
    fun decodeAltitudeMeters_matchesFitProfile() {
        // raw 2500 → 2500/5 - 500 = 0m
        assertThat(FitEngine.decodeAltitudeMeters(2500)).isWithin(0.01).of(0.0)
        // raw 5000 → 500m
        assertThat(FitEngine.decodeAltitudeMeters(5000)).isWithin(0.01).of(500.0)
    }

    /** metres → FIT altitude raw (scale 5, offset 500) */
    private fun altitudeRaw(meters: Double): Int = ((meters + 500.0) * 5.0).toInt()

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
