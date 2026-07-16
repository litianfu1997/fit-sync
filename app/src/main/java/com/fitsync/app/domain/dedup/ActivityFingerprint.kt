package com.fitsync.app.domain.dedup

import com.fitsync.app.domain.model.SportType
import com.fitsync.app.fit.FitParseResult
import java.security.MessageDigest

object ActivityFingerprint {
    fun compute(result: FitParseResult): String {
        val startBucket = (result.startTimeEpochMs ?: 0L) / 60_000L // minute
        val distanceBucket = ((result.distanceMeters ?: 0.0) / 100.0).toInt() // 100m
        val elapsedBucket = (result.elapsedSeconds ?: 0) / 30 // 30s
        val sport = result.sportType.name
        val manufacturer = result.manufacturer?.toString() ?: "-"
        val raw = listOf(sport, startBucket, distanceBucket, elapsedBucket, manufacturer).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun sportCompatible(a: SportType, b: SportType): Boolean {
        if (a == SportType.UNKNOWN || b == SportType.UNKNOWN) return true
        if (a == b) return true
        val cycling = setOf(SportType.CYCLING, SportType.INDOOR_CYCLING, SportType.MTB, SportType.GRAVEL)
        return a in cycling && b in cycling
    }
}
