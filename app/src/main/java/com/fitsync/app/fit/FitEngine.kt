package com.fitsync.app.fit

import com.fitsync.app.domain.model.SportType
import com.fitsync.app.domain.model.ValidationStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.abs

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
    private data class MesgDef(
        val localNum: Int,
        val globalMesgNum: Int,
        val fields: List<FieldDef>,
        val littleEndian: Boolean,
    )

    private fun decodeMessages(bytes: ByteArray, start: Int, end: Int): Decoded {
        val defs = HashMap<Int, MesgDef>()
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
        var elev: Double? = null
        var hasSession = false
        var hasLaps = false
        var hasHr = false
        var hasCad = false
        var hasPwr = false
        var recordCount = 0
        val track = ArrayList<LatLng>()
        var avgHr: Int? = null
        var avgPwr: Int? = null
        var avgCad: Int? = null

        // FIT epoch: 1989-12-31 00:00:00 UTC
        val fitEpochOffsetMs = 631065600000L

        fun readTimestamp(raw: Long): Long? {
            if (raw == 0xFFFFFFFFL || raw == 0L) return null
            return fitEpochOffsetMs + raw * 1000L
        }

        while (offset < end) {
            val header = bytes[offset].toInt() and 0xFF
            offset++
            val isDefinition = header and 0x40 != 0
            val isCompressedTs = header and 0x80 != 0
            if (isCompressedTs) {
                // Skip compressed timestamp header records when def missing.
                val local = (header shr 5) and 0x03
                val def = defs[local] ?: break
                offset += def.fields.sumOf { it.size }
                continue
            }
            val localNum = header and 0x0F
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
                if (header and 0x20 != 0) {
                    if (offset >= end) break
                    val devCount = bytes[offset].toInt() and 0xFF
                    offset++
                    offset += devCount * 3
                }
                defs[localNum] = MesgDef(localNum, global, fields, littleEndian)
            } else {
                val def = defs[localNum] ?: break
                val mesgSize = def.fields.sumOf { it.size }
                if (offset + mesgSize > end) break
                val mesgBytes = bytes.copyOfRange(offset, offset + mesgSize)
                offset += mesgSize
                val values = readFields(mesgBytes, def)

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
                        values[22]?.let { elev = it / 5.0 } // enhanced or total_ascent often uint16
                    }
                    19 -> hasLaps = true // Lap
                    20 -> { // Record
                        recordCount++
                        val lat = values[0]
                        val lon = values[1]
                        if (lat != null && lon != null) {
                            val la = semicirclesToDegrees(lat.toInt())
                            val lo = semicirclesToDegrees(lon.toInt())
                            if (abs(la) <= 90 && abs(lo) <= 180 && (la != 0.0 || lo != 0.0)) {
                                if (track.size < 64) track += LatLng(la, lo)
                            }
                        }
                        if (values[3] != null) hasHr = true
                        if (values[4] != null) hasCad = true
                        if (values[7] != null) hasPwr = true
                        values[253]?.let { ts ->
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
        val elapsedSnapshot = elapsed
        if (endMs == null && startSnapshot != null && elapsedSnapshot != null) {
            endMs = startSnapshot + elapsedSnapshot * 1000L
        }

        return Decoded(
            fileType = fileType,
            isActivityFile = fileType == null || fileType == 4,
            manufacturer = manufacturer,
            product = product,
            deviceSerial = serial,
            sportType = sport,
            startTimeEpochMs = startMs,
            endTimeEpochMs = endMs,
            elapsedSeconds = elapsed,
            movingSeconds = moving,
            distanceMeters = distance,
            elevationGain = elev,
            hasSession = hasSession,
            hasLaps = hasLaps,
            hasHeartRate = hasHr || avgHr != null,
            hasCadence = hasCad || avgCad != null,
            hasPower = hasPwr || avgPwr != null,
            recordCount = recordCount,
            trackSample = sampleTrack(track),
            avgHeartRate = avgHr,
            avgPower = avgPwr,
            avgCadence = avgCad,
        )
    }

    private fun readFields(mesgBytes: ByteArray, def: MesgDef): Map<Int, Long> {
        val map = HashMap<Int, Long>()
        var pos = 0
        val order = if (def.littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        for (field in def.fields) {
            if (pos + field.size > mesgBytes.size) break
            val slice = mesgBytes.copyOfRange(pos, pos + field.size)
            pos += field.size
            val base = field.baseType and 0x1F
            val value: Long? = when {
                field.size == 1 && base in listOf(0, 1, 2, 10, 13) ->
                    (slice[0].toInt() and 0xFF).toLong().takeUnless { it == 0xFFL }
                field.size == 2 -> {
                    val v = ByteBuffer.wrap(slice).order(order).short.toInt() and 0xFFFF
                    v.toLong().takeUnless { it == 0xFFFFL }
                }
                field.size == 4 -> {
                    val v = ByteBuffer.wrap(slice).order(order).int.toLong() and 0xFFFFFFFFL
                    v.takeUnless { it == 0xFFFFFFFFL }
                }
                field.size == 8 -> ByteBuffer.wrap(slice).order(order).long
                else -> null
            }
            if (value != null) map[field.fieldNum] = value
        }
        return map
    }

    private fun semicirclesToDegrees(semi: Int): Double = semi * (180.0 / Int.MAX_VALUE.toDouble())

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
}
