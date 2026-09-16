package com.fitsync.app.fit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64
import kotlin.math.abs

class TrackCodecDenseTrackTest {

    @Test
    fun roundTrip_keepsPointsWithinMicroDegree() {
        val points = realisticTrack(4000)
        val encoded = TrackCodec.encodeDenseTrack(points)

        val decoded = TrackCodec.decodeDenseTrack(encoded)

        assertThat(decoded).hasSize(points.size)
        decoded.forEachIndexed { index, point ->
            assertThat(abs(point.lat - points[index].lat)).isLessThan(1e-6)
            assertThat(abs(point.lon - points[index].lon)).isLessThan(1e-6)
        }
    }

    @Test
    fun encode_returnsNullForDegenerateTracks() {
        assertThat(TrackCodec.encodeDenseTrack(emptyList())).isNull()
        assertThat(TrackCodec.encodeDenseTrack(listOf(LatLng(30.0, 120.0)))).isNull()
    }

    @Test
    fun decode_returnsEmptyForGarbageInsteadOfThrowing() {
        assertThat(TrackCodec.decodeDenseTrack(null)).isEmpty()
        assertThat(TrackCodec.decodeDenseTrack("")).isEmpty()
        assertThat(TrackCodec.decodeDenseTrack("not base64 %%%")).isEmpty()
        // 合法 Base64 但内容被截断
        val truncated = Base64.getEncoder().encodeToString(byteArrayOf(1, 100, 0x80.toByte()))
        assertThat(TrackCodec.decodeDenseTrack(truncated)).isEmpty()
    }

    @Test
    fun decode_returnsEmptyForUnknownFormatVersion() {
        val encoded = TrackCodec.encodeDenseTrack(realisticTrack(50))!!
        val bytes = Base64.getDecoder().decode(encoded)
        bytes[0] = 99
        val bumped = Base64.getEncoder().encodeToString(bytes)

        assertThat(TrackCodec.decodeDenseTrack(bumped)).isEmpty()
    }

    @Test
    fun decode_returnsEmptyWhenPointCountExceedsGuard() {
        // 手工构造 <版本1><点数 1_000_000>，避免解码时按声明长度预分配大数组
        val payload = java.io.ByteArrayOutputStream().apply {
            write(1)
            var value = 1_000_000
            while (true) {
                val byte = value and 0x7F
                value = value ushr 7
                if (value == 0) { write(byte); break }
                write(byte or 0x80)
            }
        }.toByteArray()

        assertThat(TrackCodec.decodeDenseTrack(Base64.getEncoder().encodeToString(payload))).isEmpty()
    }

    @Test
    fun decodeBestTrack_prefersDenseAndFallsBackToSummary() {
        val dense = realisticTrack(500)
        val summary = realisticTrack(21)
        val denseEncoded = TrackCodec.encodeDenseTrack(dense)
        val summaryJson = TrackCodec.encodeTrack(summary)

        assertThat(TrackCodec.decodeBestTrack(denseEncoded, summaryJson)).hasSize(500)
        assertThat(TrackCodec.decodeBestTrack(null, summaryJson)).hasSize(21)
        assertThat(TrackCodec.decodeBestTrack("bad", summaryJson)).hasSize(21)
    }

    /** 真实骑行量级：1 秒约 8 米（≈1e-4 度），相邻差分应稳定落在 4 字节/点附近。 */
    @Test
    fun encoding_staysCompactForTypicalSpacing() {
        val points = realisticTrack(4000)
        val encoded = TrackCodec.encodeDenseTrack(points)!!
        val bytes = Base64.getDecoder().decode(encoded)

        assertThat(bytes.size.toDouble() / points.size).isLessThan(8.0)
    }

    private fun realisticTrack(count: Int): List<LatLng> = List(count) { index ->
        // 向北直行 + 轻微左右摆动，模拟真实轨迹的相邻点间距
        LatLng(
            lat = 30.0 + index * 0.0001,
            lon = 120.0 + kotlin.math.sin(index / 25.0) * 0.0002,
        )
    }
}
