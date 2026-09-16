package com.fitsync.app.fit

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.roundToInt

/**
 * 轨迹与曲线采样序列的 JSON 编解码。
 *
 * 放在 fit 包而不是 ImportService 内部，是因为 UI 层（活动列表缩略图、活动详情曲线、
 * 地图轨迹）也要读同一份数据；两边各写一遍解析必然会漂移。
 */
object TrackCodec {

    /** 高密度轨迹的编码格式版本；将来换格式时递增，老数据解码返回空表并由 UI 回退。 */
    private const val DENSE_FORMAT_VERSION = 1

    /** 解码时的点数上限：防御被截断/伪造的字符串触发超大内存分配。 */
    private const val DENSE_MAX_DECODE_POINTS = 20_000

    /** 微度：1e-6 度 ≈ 11cm，足够画图且能用 Int 精确定点。 */
    private const val MICRO_DEGREES = 1_000_000.0

    fun encodeTrack(points: List<LatLng>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().put("lat", p.lat).put("lon", p.lon))
        }
        return arr.toString()
    }

    fun decodeTrack(json: String?): List<LatLng> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(LatLng(o.getDouble("lat"), o.getDouble("lon")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 曲线采样：用短键压缩体积，缺测值直接不写键。 */
    fun encodeProfile(samples: List<ProfileSample>): String {
        if (samples.isEmpty()) return "[]"
        val arr = JSONArray()
        samples.forEach { s ->
            val o = JSONObject().put("t", s.seconds)
            s.speedMps?.let { o.put("v", it) }
            s.altitudeMeters?.let { o.put("a", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decodeProfile(json: String?): List<ProfileSample> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ProfileSample(
                            seconds = o.getInt("t"),
                            speedMps = if (o.has("v")) o.getDouble("v") else null,
                            altitudeMeters = if (o.has("a")) o.getDouble("a") else null,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // 高密度地图轨迹：微度 int + 相邻差分 + zigzag varint + Base64。
    // 4000 点的长活动编码后通常 15~22KB，比同规模 JSON 小一个数量级；
    // 用 java.util.Base64（而不是 android.util.Base64）以便 JVM 单测可直接覆盖。
    // ---------------------------------------------------------------------

    /** 编码：<格式版本><点数><Δlat><Δlon>...。少于 2 点没有画线意义，返回 null。 */
    fun encodeDenseTrack(points: List<LatLng>): String? {
        if (points.size < 2) return null
        val out = ByteArrayOutputStream(points.size * 5 + 8)
        out.write(DENSE_FORMAT_VERSION)
        writeDenseVarint(out, points.size)
        var prevLat = 0
        var prevLon = 0
        points.forEach { point ->
            val lat = (point.lat * MICRO_DEGREES).roundToInt()
            val lon = (point.lon * MICRO_DEGREES).roundToInt()
            writeDenseVarint(out, (lat - prevLat).zigzag())
            writeDenseVarint(out, (lon - prevLon).zigzag())
            prevLat = lat
            prevLon = lon
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** 解码：任何损坏（版本不符 / 截断 / 点数越界 / Base64 非法）都返回空表，不抛异常。 */
    fun decodeDenseTrack(base64: String?): List<LatLng> {
        if (base64.isNullOrBlank()) return emptyList()
        return try {
            val input = ByteArrayInputStream(Base64.getDecoder().decode(base64))
            if (input.read() != DENSE_FORMAT_VERSION) return emptyList()
            val count = readDenseVarint(input)
            if (count < 2 || count > DENSE_MAX_DECODE_POINTS) return emptyList()
            val result = ArrayList<LatLng>(count)
            var lat = 0
            var lon = 0
            repeat(count) {
                lat += readDenseVarint(input).unzigzag()
                lon += readDenseVarint(input).unzigzag()
                result += LatLng(lat / MICRO_DEGREES, lon / MICRO_DEGREES)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 地图取点唯一入口：优先高密度轨迹，缺失或损坏时回退 21 点摘要
     * （老数据，或原始 FIT 已被清理、无法重算的副本）。
     */
    fun decodeBestTrack(denseBase64: String?, summaryJson: String?): List<LatLng> {
        val dense = decodeDenseTrack(denseBase64)
        return if (dense.size >= 2) dense else decodeTrack(summaryJson)
    }

    private fun writeDenseVarint(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (true) {
            val byte = rest and 0x7F
            rest = rest ushr 7
            if (rest == 0) {
                out.write(byte)
                return
            }
            out.write(byte or 0x80)
        }
    }

    private fun readDenseVarint(input: ByteArrayInputStream): Int {
        var result = 0
        for (shift in 0..28 step 7) {
            val byte = input.read()
            if (byte < 0) throw IllegalStateException("varint 截断")
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
        }
        throw IllegalStateException("varint 过长")
    }

    private fun Int.zigzag(): Int = (this shl 1) xor (this shr 31)

    private fun Int.unzigzag(): Int = (this ushr 1) xor -(this and 1)
}
