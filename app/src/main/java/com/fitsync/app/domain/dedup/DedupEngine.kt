package com.fitsync.app.domain.dedup

import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.fit.FitParseResult
import com.fitsync.app.fit.LatLng
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class DedupDecision(
    val action: Action,
    val matchedCanonicalId: Long? = null,
    val score: Int = 0,
    val reason: String = "",
) {
    enum class Action {
        CREATE_NEW,
        AUTO_MERGE,
        NEEDS_REVIEW,
    }
}

class DedupEngine {

    fun decide(
        incoming: FitParseResult,
        candidates: List<Pair<CanonicalActivityEntity, List<LatLng>>>,
    ): DedupDecision {
        if (incoming.startTimeEpochMs == null) {
            return DedupDecision(DedupDecision.Action.CREATE_NEW, reason = "缺少开始时间")
        }

        var best: Pair<CanonicalActivityEntity, Int>? = null
        for ((canonical, track) in candidates) {
            if (!ActivityFingerprint.sportCompatible(incoming.sportType, canonical.sportType)) continue
            val score = similarity(incoming, canonical, track)
            if (best == null || score > best.second) {
                best = canonical to score
            }
        }

        val match = best ?: return DedupDecision(DedupDecision.Action.CREATE_NEW, reason = "无候选")
        return when {
            match.second >= 92 -> DedupDecision(
                DedupDecision.Action.AUTO_MERGE,
                matchedCanonicalId = match.first.id,
                score = match.second,
                reason = "高相似度自动合并",
            )
            match.second >= 75 -> DedupDecision(
                DedupDecision.Action.NEEDS_REVIEW,
                matchedCanonicalId = match.first.id,
                score = match.second,
                reason = "疑似重复，需用户确认",
            )
            else -> DedupDecision(
                DedupDecision.Action.CREATE_NEW,
                score = match.second,
                reason = "相似度不足",
            )
        }
    }

    fun filterCandidates(
        all: List<CanonicalActivityEntity>,
        startTimeEpochMs: Long,
        distanceMeters: Double?,
        sportType: SportType,
    ): List<CanonicalActivityEntity> {
        val windowMs = 10 * 60 * 1000L
        return all.filter { c ->
            ActivityFingerprint.sportCompatible(sportType, c.sportType) &&
                abs(c.startTimeEpochMs - startTimeEpochMs) <= windowMs &&
                distanceCompatible(distanceMeters, c.distanceMeters, sportType)
        }
    }

    private fun distanceCompatible(a: Double?, b: Double?, sport: SportType): Boolean {
        if (a == null || b == null || a <= 0 || b <= 0) {
            // Indoor / missing distance: allow candidate by time only
            return sport == SportType.INDOOR_CYCLING || a == null || b == null
        }
        val diff = abs(a - b) / maxOf(a, b)
        return diff <= 0.08
    }

    private fun similarity(
        incoming: FitParseResult,
        canonical: CanonicalActivityEntity,
        track: List<LatLng>,
    ): Int {
        var score = 0.0

        // Start time 25
        val startDiffMin = abs((incoming.startTimeEpochMs ?: 0) - canonical.startTimeEpochMs) / 60_000.0
        score += 25.0 * decay(startDiffMin, 10.0)

        // Elapsed 15
        val inElapsed = incoming.elapsedSeconds?.toDouble()
        val cElapsed = canonical.elapsedSeconds?.toDouble()
        score += 15.0 * ratioScore(inElapsed, cElapsed)

        // Moving 10
        score += 10.0 * ratioScore(incoming.movingSeconds?.toDouble(), canonical.movingSeconds?.toDouble())

        // Distance 15
        score += 15.0 * ratioScore(incoming.distanceMeters, canonical.distanceMeters)

        // GPS track 25
        score += 25.0 * trackScore(incoming.trackSample, track)

        // Sensors / device soft signals 10 total (5+5 simplified)
        if (incoming.manufacturer != null) score += 5.0
        if (incoming.hasPower || incoming.hasHeartRate) score += 5.0

        return score.toInt().coerceIn(0, 100)
    }

    private fun ratioScore(a: Double?, b: Double?): Double {
        if (a == null || b == null || a <= 0 || b <= 0) return 0.4
        val ratio = min(a, b) / maxOf(a, b)
        return ratio.coerceIn(0.0, 1.0)
    }

    private fun decay(diff: Double, max: Double): Double {
        if (diff >= max) return 0.0
        return 1.0 - (diff / max)
    }

    private fun trackScore(a: List<LatLng>, b: List<LatLng>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.35 // neutral for indoor
        val sa = resample(a, 21)
        val sb = resample(b, 21)
        var sum = 0.0
        var max = 0.0
        for (i in sa.indices) {
            val d = haversineMeters(sa[i], sb[i])
            sum += d
            if (d > max) max = d
        }
        val avg = sum / sa.size
        return when {
            avg < 100 && max < 500 -> 1.0
            avg < 250 && max < 1000 -> 0.7
            avg < 500 -> 0.4
            else -> 0.1
        }
    }

    private fun resample(points: List<LatLng>, n: Int): List<LatLng> {
        if (points.size == n) return points
        if (points.size == 1) return List(n) { points[0] }
        val out = ArrayList<LatLng>(n)
        for (i in 0 until n) {
            val idx = (i * (points.size - 1)).toDouble() / (n - 1)
            val lo = idx.toInt().coerceIn(0, points.size - 1)
            out += points[lo]
        }
        return out
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
