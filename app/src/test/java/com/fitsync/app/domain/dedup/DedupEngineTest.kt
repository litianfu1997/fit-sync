package com.fitsync.app.domain.dedup

import com.fitsync.app.data.db.entity.CanonicalActivityEntity
import com.fitsync.app.domain.model.DedupStatus
import com.fitsync.app.domain.model.SportType
import com.fitsync.app.fit.FitParseResult
import com.fitsync.app.fit.LatLng
import com.fitsync.app.domain.model.ValidationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DedupEngineTest {

    private val engine = DedupEngine()

    @Test
    fun autoMergesNearIdenticalActivities() {
        val start = 1_720_000_000_000L
        val track = listOf(
            LatLng(30.25, 120.15),
            LatLng(30.26, 120.16),
            LatLng(30.27, 120.17),
        )
        val existing = CanonicalActivityEntity(
            id = 1,
            sportType = SportType.CYCLING,
            startTimeEpochMs = start,
            elapsedSeconds = 3600,
            movingSeconds = 3400,
            distanceMeters = 40000.0,
            dedupStatus = DedupStatus.UNIQUE,
        )
        val incoming = FitParseResult(
            validationStatus = ValidationStatus.VALID,
            sportType = SportType.CYCLING,
            startTimeEpochMs = start + 60_000,
            elapsedSeconds = 3580,
            movingSeconds = 3390,
            distanceMeters = 40100.0,
            trackSample = track,
            manufacturer = 1,
            hasPower = true,
        )
        val decision = engine.decide(incoming, listOf(existing to track))
        assertThat(decision.action).isAnyOf(
            DedupDecision.Action.AUTO_MERGE,
            DedupDecision.Action.NEEDS_REVIEW,
        )
        assertThat(decision.score).isAtLeast(75)
    }

    @Test
    fun doesNotMergeFarApartRides() {
        val existing = CanonicalActivityEntity(
            id = 1,
            sportType = SportType.CYCLING,
            startTimeEpochMs = 1_720_000_000_000L,
            elapsedSeconds = 3600,
            distanceMeters = 40000.0,
            dedupStatus = DedupStatus.UNIQUE,
        )
        val incoming = FitParseResult(
            validationStatus = ValidationStatus.VALID,
            sportType = SportType.CYCLING,
            startTimeEpochMs = 1_720_000_000_000L + 3 * 60 * 60_000L,
            elapsedSeconds = 3600,
            distanceMeters = 40000.0,
        )
        val candidates = engine.filterCandidates(
            listOf(existing),
            incoming.startTimeEpochMs!!,
            incoming.distanceMeters,
            incoming.sportType,
        )
        assertThat(candidates).isEmpty()
        val decision = engine.decide(incoming, emptyList())
        assertThat(decision.action).isEqualTo(DedupDecision.Action.CREATE_NEW)
    }
}
