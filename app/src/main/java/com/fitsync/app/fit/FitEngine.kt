package com.fitsync.app.fit

import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.random.Random

/**
 * Lightweight FIT Activity parser for on-device validation and metadata extraction.
 * Intentionally avoids full SDK dependency for Phase 1; can be swapped for Garmin FIT SDK later.
 */
class FitEngine {

    fun parseFile(file: File): FitParseResult {
        val bytes = when {
            file.name.endsWith(".zip", ignoreCase = true) -> extractFirstFitFromZip(file)
            else -> file.readBytes()
        } ?: return FitParseResult(
            validationStatus = ValidationStatus.UNSUPPORTED,
            errorMessage = "ZIP 中未找到 FIT 文件",
        )
        return parseBytes(bytes)
    }

    fun parseBytes(bytes: ByteArray): FitParseResult {
        if (bytes.size < 14) {
            return FitParseResult(
                validationStatus = ValidationStatus.TRUNCATED,
                errorMessage = "文件过短，不是合法 FIT",
            )
        }

        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize !in listOf(12, 14) || bytes.size < headerSize) {
            return FitParseResult(
                validationStatus = ValidationStatus.UNSUPPORTED,
                errorMessage = "FIT Header 长度异常",
            )
        }

        val signature = String(bytes, 8, 4, Charsets.US_ASCII)
        if (signature != ".FIT") {
            return FitParseResult(
                validationStatus = ValidationStatus.UNSUPPORTED,
                errorMessage = "缺少 .FIT 签名",
            )
        }

        val dataSize = FitCrc.readUInt32Le(bytes, 4).toInt()
        val expectedTotal = headerSize + dataSize + 2
        val truncated = bytes.size < expectedTotal
        val contentEnd = minOf(bytes.size, headerSize + dataSize)

        val crcOk = if (bytes.size >= headerSize + dataSize + 2) {
            val expectedCrc = FitCrc.readUInt16Le(bytes, headerSize + dataSize)
            val actualCrc = FitCrc.compute(bytes, 0, headerSize + dataSize)
            expectedCrc == actualCrc
        } else {
            false
        }

        // Header CRC (optional 14-byte header)
        val warnings = mutableListOf<String>()
        if (headerSize == 14) {
            val headerCrc = FitCrc.readUInt16Le(bytes, 12)
            val headerActual = FitCrc.compute(bytes, 0, 12)
            if (headerCrc != 0 && headerCrc != headerActual) {
                warnings += "Header CRC 不匹配"
            }
        }
        if (!crcOk) warnings += "文件 CRC 无效或缺失"
        if (truncated) warnings += "文件可能被截断"

        val decoded = decodeMessages(bytes, headerSize, contentEnd)
        if (!decoded.isActivityFile && decoded.fileType != null && decoded.fileType != 4) {
            return FitParseResult(
                validationStatus = ValidationStatus.NOT_ACTIVITY,
                errorMessage = "FIT 文件类型不是 Activity (type=${decoded.fileType})",
                manufacturer = decoded.manufacturer,
                product = decoded.product,
            )
        }

        val validation = when {
            truncated -> ValidationStatus.TRUNCATED
            !crcOk && decoded.recordCount == 0 && !decoded.hasSession -> ValidationStatus.CRC_INVALID
            !crcOk -> ValidationStatus.VALID_WITH_WARNINGS
            warnings.isNotEmpty() -> ValidationStatus.VALID_WITH_WARNINGS
            decoded.startTimeEpochMs == null -> ValidationStatus.VALID_WITH_WARNINGS
            else -> ValidationStatus.VALID
        }

        val base = FitParseResult(
            validationStatus = validation,
            warnings = warnings,
            errorMessage = if (validation == ValidationStatus.CRC_INVALID) "CRC 校验失败" else null,
            sportType = decoded.sportType,
            startTimeEpochMs = decoded.startTimeEpochMs,
            endTimeEpochMs = decoded.endTimeEpochMs,
            elapsedSeconds = decoded.elapsedSeconds,
            movingSeconds = decoded.movingSeconds,
            distanceMeters = decoded.distanceMeters,
            elevationGain = decoded.elevationGain,
            manufacturer = decoded.manufacturer,
            product = decoded.product,
            deviceSerial = decoded.deviceSerial,
            hasGps = decoded.trackSample.isNotEmpty(),
            hasHeartRate = decoded.hasHeartRate,
            hasCadence = decoded.hasCadence,
            hasPower = decoded.hasPower,
            hasLaps = decoded.hasLaps,
            hasSession = decoded.hasSession,
            recordCount = decoded.recordCount,
            trackSample = decoded.trackSample,
            avgHeartRate = decoded.avgHeartRate,
            avgPower = decoded.avgPower,
            avgCadence = decoded.avgCadence,
            isActivityFile = decoded.isActivityFile || decoded.hasSession || decoded.recordCount > 0,
        )

        val quality = FitQualityScorer.score(
            result = base,
            crcOk = crcOk,
            looksReexported = decoded.manufacturer == null,
        )
        return base.copy(qualityScore = quality)
    }

    fun isLikelyFit(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT"
    }

    private fun extractFirstFitFromZip(zipFile: File): ByteArray? {
        ZipFile(zipFile).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".fit", ignoreCase = true) }
                ?: return null
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private data class Decoded(
        val fileType: Int? = null,
        val isActivityFile: Boolean = false,
        val manufacturer: Int? = null,
        val product: Int? = null,
        val deviceSerial: String? = null,
        val sportType: SportType = SportType.UNKNOWN,
        val startTimeEpochMs: Long? = null,
        val endTimeEpochMs: Long? = null,
        val elapsedSeconds: Int? = null,
        val movingSeconds: Int? = null,
        val distanceMeters: Double? = null,
        val elevationGain: Double? = null,
        val hasSession: Boolean = false,
        val hasLaps: Boolean = false,
        val hasHeartRate: Boolean = false,
        val hasCadence: Boolean = false,
        val hasPower: Boolean = false,
        val recordCount: Int = 0,
        val trackSample: List<LatLng> = emptyList(),
        val avgHeartRate: Int? = null,
        val avgPower: Int? = null,
        val avgCadence: Int? = null,
    )

    private data class FieldDef(val fieldNum: Int, val size: Int, val baseType: Int)
    private data class DeveloperFieldDef(val size: Int)
    private data class MesgDef(
        val localNum: Int,
        val globalMesgNum: Int,
        val fields: List<FieldDef>,
        val developerFields: List<DeveloperFieldDef>,
        val littleEndian: Boolean,
    )

    /** 复用 256 个 FIT 字段槽，避免每条 Record 创建 HashMap/装箱 Long。 */
    private class FieldValues {
        private val values = LongArray(256)
        private val present = BooleanArray(256)
        private val touched = IntArray(256)
        private var touchedCount = 0

        fun clear() {
            for (i in 0 until touchedCount) present[touched[i]] = false
            touchedCount = 0
        }

        fun put(fieldNum: Int, value: Long) {
            if (!present[fieldNum]) touched[touchedCount++] = fieldNum
            present[fieldNum] = true
            values[fieldNum] = value
        }

        operator fun get(fieldNum: Int): Long? = if (present[fieldNum]) values[fieldNum] else null
    }

    private fun decodeMessages(bytes: ByteArray, start: Int, end: Int): Decoded {
        val defs = arrayOfNulls<MesgDef>(16)
        val values = FieldValues()
        var offset = start
        var fileType: Int? = null
        var manufacturer: Int? = null
        var product: Int? = null
        var serial: String? = null
        var sport = SportType.UNKNOWN
        var startMs: Long? = null
        var endMs: Long? = null
        var elapsed: Int? = null
        var moving: Int? = null
        var distance: Double? = null
        var sessionAscentSum = 0.0
        var hasSessionAscent = false
        var hasSession = false
        var hasLaps = false
        var hasHr = false
        var hasCad = false
        var hasPwr = false
        var recordCount = 0
        val track = TrackPointSampler()
        var avgHr: Int? = null
        var avgPwr: Int? = null
        var avgCad: Int? = null
        var lastTimestampRaw: Long? = null
        var recordDistance: Double? = null
        var lapAscent = 0.0
        var hasLapAscent = false
        val ascentAccumulator = AscentAccumulator()
        var heartRateSum = 0L
        var heartRateCount = 0

        // FIT epoch: 1989-12-31 00:00:00 UTC
        val fitEpochOffsetMs = 631065600000L

        fun readTimestamp(raw: Long): Long? {
            if (raw == 0xFFFFFFFFL || raw == 0L) return null
            return fitEpochOffsetMs + raw * 1000L
        }

        while (offset < end) {
            val header = bytes[offset].toInt() and 0xFF
            offset++
            val isCompressedTs = header and 0x80 != 0
            val isDefinition = !isCompressedTs && header and 0x40 != 0
            val localNum = if (isCompressedTs) (header shr 5) and 0x03 else header and 0x0F
            val compressedTimeOffset = if (isCompressedTs) header and 0x1F else null
            if (isDefinition) {
                if (offset + 5 > end) break
                val architecture = bytes[offset + 1].toInt() and 0xFF
                val littleEndian = architecture == 0
                val global = if (littleEndian) {
                    FitCrc.readUInt16Le(bytes, offset + 2)
                } else {
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
                }
                val fieldCount = bytes[offset + 4].toInt() and 0xFF
                offset += 5
                val fields = ArrayList<FieldDef>(fieldCount)
                var ok = true
                repeat(fieldCount) {
                    if (offset + 3 > end) {
                        ok = false
                        return@repeat
                    }
                    fields += FieldDef(
                        fieldNum = bytes[offset].toInt() and 0xFF,
                        size = bytes[offset + 1].toInt() and 0xFF,
                        baseType = bytes[offset + 2].toInt() and 0xFF,
                    )
                    offset += 3
                }
                if (!ok) break
                // Developer fields
                val developerFields = ArrayList<DeveloperFieldDef>()
                if (header and 0x20 != 0) {
                    if (offset >= end) break
                    val devCount = bytes[offset].toInt() and 0xFF
                    offset++
                    if (offset + devCount * 3 > end) break
                    repeat(devCount) {
                        developerFields += DeveloperFieldDef(
                            size = bytes[offset + 1].toInt() and 0xFF,
                        )
                        offset += 3
                    }
                }
                defs[localNum] = MesgDef(localNum, global, fields, developerFields, littleEndian)
            } else {
                val def = defs[localNum] ?: break
                val nativeSize = def.fields.sumOf { it.size }
                val mesgSize = nativeSize + def.developerFields.sumOf { it.size }
                if (offset + mesgSize > end) break
                readFields(bytes, offset, nativeSize, def, values)
                offset += mesgSize
                val explicitTimestamp = values[253]
                val messageTimestamp = when {
                    explicitTimestamp != null -> explicitTimestamp
                    compressedTimeOffset != null && lastTimestampRaw != null -> {
                        val previous = lastTimestampRaw
                        val previousOffset = (previous and 0x1F).toInt()
                        (previous and 0xFFFFFFE0L) + compressedTimeOffset +
                            if (compressedTimeOffset < previousOffset) 0x20 else 0
                    }
                    else -> null
                }
                if (messageTimestamp != null) lastTimestampRaw = messageTimestamp

                when (def.globalMesgNum) {
                    0 -> { // FileId
                        fileType = values[0]?.toInt()
                        manufacturer = values[1]?.toInt() ?: manufacturer
                        product = values[2]?.toInt() ?: product
                        values[3]?.let { serial = it.toString() }
                    }
                    18 -> { // Session
                        hasSession = true
                        values[2]?.let { // start_time
                            startMs = readTimestamp(it)
                        }
                        values[7]?.let { // total_elapsed_time (uint32 ms scale 1000)
                            elapsed = (it / 1000.0).toInt()
                        }
                        values[8]?.let {
                            moving = (it / 1000.0).toInt()
                        }
                        values[9]?.let { // total_distance cm? scale 100
                            distance = it / 100.0
                        }
                        values[5]?.let { sport = mapSport(it.toInt()) }
                        values[16]?.let { avgHr = it.toInt() }
                        values[18]?.let { avgCad = it.toInt() }
                        values[20]?.let { avgPwr = it.toInt() }
                        // total_ascent：uint16，单位 m（多 session 累加）
                        values[22]?.let {
                            sessionAscentSum += it.toDouble()
                            hasSessionAscent = true
                        }
                        messageTimestamp?.let { readTimestamp(it) }?.let { endMs = it }
                    }
                    19 -> { // Lap
                        hasLaps = true
                        values[22]?.let {
                            lapAscent += it.toDouble()
                            hasLapAscent = true
                        }
                    }
                    20 -> { // Record
                        recordCount++
                        val lat = values[0]
                        val lon = values[1]
                        if (lat != null && lon != null) {
                            val la = semicirclesToDegrees(lat.toInt())
                            val lo = semicirclesToDegrees(lon.toInt())
                            if (abs(la) <= 90 && abs(lo) <= 180 && (la != 0.0 || lo != 0.0)) {
                                track.add(LatLng(la, lo))
                            }
                        }
                        if (values[3] != null) hasHr = true
                        if (values[4] != null) hasCad = true
                        if (values[7] != null) hasPwr = true
                        values[3]?.let {
                            heartRateSum += it
                            heartRateCount++
                        }
                        values[5]?.let {
                            val currentDistance = it / 100.0
                            recordDistance = maxOf(recordDistance ?: currentDistance, currentDistance)
                        }
                        val altitude = values[78]?.let { decodeAltitudeMeters(it) }
                            ?: values[2]?.let { decodeAltitudeMeters(it) }
                        altitude?.let { ascentAccumulator.add(it) }
                        messageTimestamp?.let { ts ->
                            val t = readTimestamp(ts)
                            if (t != null) {
                                if (startMs == null) startMs = t
                                endMs = t
                            }
                        }
                    }
                    23 -> { // DeviceInfo
                        manufacturer = values[2]?.toInt() ?: manufacturer
                        product = values[4]?.toInt() ?: product
                        values[3]?.let { serial = it.toString() }
                    }
                }
            }
        }

        val startSnapshot = startMs
        val endSnapshot = endMs
        val elapsedResolved = elapsed ?: if (startSnapshot != null && endSnapshot != null && endSnapshot >= startSnapshot) {
            ((endSnapshot - startSnapshot) / 1000L).toInt()
        } else {
            null
        }
        if (endMs == null && startSnapshot != null && elapsedResolved != null) {
            endMs = startSnapshot + elapsedResolved * 1000L
        }
        val averageHeartRate = avgHr ?: if (heartRateCount > 0) {
            (heartRateSum.toDouble() / heartRateCount).toInt()
        } else {
            null
        }
        val elevationGain = resolveElevationGain(
            hasSessionAscent = hasSessionAscent,
            sessionAscentSum = sessionAscentSum,
            hasLapAscent = hasLapAscent,
            lapAscentSum = lapAscent,
            recordAscent = ascentAccumulator.total,
        )

        return Decoded(
            fileType = fileType,
            isActivityFile = fileType == null || fileType == 4,
            manufacturer = manufacturer,
            product = product,
            deviceSerial = serial,
            sportType = sport,
            startTimeEpochMs = startMs,
            endTimeEpochMs = endMs,
            elapsedSeconds = elapsedResolved,
            movingSeconds = moving,
            distanceMeters = distance ?: recordDistance,
            elevationGain = elevationGain,
            hasSession = hasSession,
            hasLaps = hasLaps,
            hasHeartRate = hasHr || averageHeartRate != null,
            hasCadence = hasCad || avgCad != null,
            hasPower = hasPwr || avgPwr != null,
            recordCount = recordCount,
            trackSample = sampleTrack(track.points()),
            avgHeartRate = averageHeartRate,
            avgPower = avgPwr,
            avgCadence = avgCad,
        )
    }

    private fun readFields(
        bytes: ByteArray,
        messageOffset: Int,
        messageSize: Int,
        def: MesgDef,
        output: FieldValues,
    ) {
        output.clear()
        var pos = 0
        for (field in def.fields) {
            if (pos + field.size > messageSize) break
            val value = readScalar(
                bytes = bytes,
                offset = messageOffset + pos,
                size = field.size,
                base = field.baseType and 0x1F,
                littleEndian = def.littleEndian,
            )
            pos += field.size
            if (value != null) output.put(field.fieldNum, value)
        }
    }

    private fun readScalar(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        base: Int,
        littleEndian: Boolean,
    ): Long? = when (base) {
        0, 2, 13 -> if (size == 1) {
            (bytes[offset].toInt() and 0xFF).toLong().takeUnless { it == 0xFFL }
        } else null
        1 -> if (size == 1) bytes[offset].toLong().takeUnless { it == 0x7FL } else null
        3 -> if (size == 2) {
            readBits(bytes, offset, size, littleEndian).toShort().toLong().takeUnless { it == 0x7FFFL }
        } else null
        4 -> if (size == 2) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == 0xFFFFL }
        } else null
        5 -> if (size == 4) {
            readBits(bytes, offset, size, littleEndian).toInt().toLong().takeUnless { it == 0x7FFFFFFFL }
        } else null
        6 -> if (size == 4) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == 0xFFFFFFFFL }
        } else null
        10 -> if (size == 1) (bytes[offset].toInt() and 0xFF).toLong().takeUnless { it == 0L } else null
        11 -> if (size == 2) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == 0L }
        } else null
        12 -> if (size == 4) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == 0L }
        } else null
        14 -> if (size == 8) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == Long.MAX_VALUE }
        } else null
        15 -> if (size == 8) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == -1L }
        } else null
        16 -> if (size == 8) {
            readBits(bytes, offset, size, littleEndian).takeUnless { it == 0L }
        } else null
        else -> null
    }

    private fun readBits(bytes: ByteArray, offset: Int, size: Int, littleEndian: Boolean): Long {
        var result = 0L
        if (littleEndian) {
            for (i in 0 until size) {
                result = result or ((bytes[offset + i].toLong() and 0xFFL) shl (i * 8))
            }
        } else {
            for (i in 0 until size) result = (result shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    private fun semicirclesToDegrees(semi: Int): Double = semi * (180.0 / 2147483648.0)

    private fun mapSport(code: Int): SportType = when (code) {
        2 -> SportType.CYCLING
        1 -> SportType.RUNNING
        else -> SportType.OTHER
    }

    private fun sampleTrack(points: List<LatLng>): List<LatLng> {
        if (points.size <= 21) return points
        val result = ArrayList<LatLng>(21)
        for (i in 0..20) {
            val idx = (i * (points.size - 1)) / 20
            result += points[idx]
        }
        return result
    }

    companion object {
        /** Record 累计爬升：小于此阈值的抬升视为噪声 */
        internal const val ASCENT_THRESHOLD_M = 3.0
        /** 单步高差超过此值视为毛刺，不计入并重置参考海拔 */
        internal const val ASCENT_MAX_STEP_M = 50.0
        internal const val ALTITUDE_MIN_M = -200.0
        internal const val ALTITUDE_MAX_M = 9000.0

        /**
         * FIT altitude / enhanced_altitude：scale 5、offset 500 → metres = raw/5 - 500
         */
        internal fun decodeAltitudeMeters(raw: Long): Double = raw / 5.0 - 500.0

        /**
         * Session 汇总优先；否则有效 Lap 合计；否则 Record 平滑累计。
         * Session 字段存在时（含 0）不再回退，避免 GPS 噪声把平路抬成假爬升。
         * Lap 全为 0 时允许回退到 Record。
         */
        internal fun resolveElevationGain(
            hasSessionAscent: Boolean,
            sessionAscentSum: Double,
            hasLapAscent: Boolean,
            lapAscentSum: Double,
            recordAscent: Double,
        ): Double? = when {
            hasSessionAscent -> sessionAscentSum
            hasLapAscent && lapAscentSum > 0.0 -> lapAscentSum
            recordAscent > 0.0 -> recordAscent
            hasLapAscent -> 0.0
            else -> null
        }
    }

    /**
     * 从 Record 海拔序列累计爬升：3 点中值平滑 + 滞后门槛 + 尖峰过滤。
     * 避免 0.5 m 阈值把 GPS 噪声逐点加总。
     */
    internal class AscentAccumulator {
        private val window = ArrayDeque<Double>(3)
        private var reference: Double? = null
        var total: Double = 0.0
            private set

        fun add(altitudeMeters: Double) {
            if (altitudeMeters < ALTITUDE_MIN_M || altitudeMeters > ALTITUDE_MAX_M) return
            if (window.size == 3) window.removeFirst()
            window.addLast(altitudeMeters)
            val smoothed = medianOf(window)
            val ref = reference
            if (ref == null) {
                reference = smoothed
                return
            }
            val delta = smoothed - ref
            if (abs(delta) > ASCENT_MAX_STEP_M) {
                // 毛刺：不计入，把参考点跳到当前平滑值
                reference = smoothed
                return
            }
            if (delta >= ASCENT_THRESHOLD_M) {
                total += delta
                reference = smoothed
            } else if (delta <= -ASCENT_THRESHOLD_M) {
                reference = smoothed
            }
        }

        private fun medianOf(values: ArrayDeque<Double>): Double {
            return when (values.size) {
                0 -> 0.0
                1 -> values[0]
                2 -> (values[0] + values[1]) / 2.0
                else -> {
                    val a = values[0]
                    val b = values[1]
                    val c = values[2]
                    a + b + c - minOf(a, b, c) - maxOf(a, b, c)
                }
            }
        }
    }
}

/**
 * 有界的流式蓄水池采样。始终保留首尾点，中间点覆盖整段轨迹，避免长活动只记录开头 64 个点。
 */
internal class TrackPointSampler(
    private val capacity: Int = 64,
    seed: Int = 0,
) {
    private data class IndexedPoint(val index: Long, val point: LatLng)

    private val random = Random(seed)
    private val middle = ArrayList<IndexedPoint>((capacity - 2).coerceAtLeast(0))
    private var first: IndexedPoint? = null
    private var last: IndexedPoint? = null
    private var nextIndex = 0L
    private var middleSeen = 0L

    init {
        require(capacity >= 2) { "轨迹采样容量至少为 2" }
    }

    fun add(point: LatLng) {
        val current = IndexedPoint(nextIndex++, point)
        val previousLast = last
        if (first == null) first = current
        last = current
        if (previousLast == null || previousLast.index == first?.index) return

        middleSeen++
        val middleCapacity = capacity - 2
        if (middle.size < middleCapacity) {
            middle += previousLast
        } else if (middleCapacity > 0) {
            val slot = random.nextLong(middleSeen)
            if (slot < middleCapacity) middle[slot.toInt()] = previousLast
        }
    }

    fun points(): List<LatLng> {
        val start = first ?: return emptyList()
        val end = last ?: return emptyList()
        if (start.index == end.index) return listOf(start.point)
        return buildList {
            add(start)
            addAll(middle.sortedBy { it.index })
            add(end)
        }.distinctBy { it.index }.sortedBy { it.index }.map { it.point }
    }
}
