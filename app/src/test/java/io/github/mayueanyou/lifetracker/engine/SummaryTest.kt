package io.github.mayueanyou.lifetracker.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryTest {
    private val h = 3600_000L
    private val m = 60_000L

    private fun stay(from: Long, to: Long, place: String?, lat: Double = 43.0, lon: Double = -76.1) =
        Entry.StayEntry(Stay(from, to, lat, lon, 10).also { it.place = place })

    private fun drive(from: Long, to: Long, km: Double, mode: Mode = Mode.DRIVING) = Entry.JourneyEntry(
        Journey(from, to, 43.0, -76.1, 43.1, -76.2, mode, km * 1000, emptyList(), fromPlace = "Home", toPlace = "School"))

    @Test fun aPlaceVisitedTwiceIsOneLineWithBothSpansAdded() {
        // Home from midnight to 10 AM, and again from 9 PM to midnight.
        val entries = listOf(
            stay(0, 10 * h, "Home"),
            drive(10 * h, 10 * h + 20 * m, 12.0),
            stay(10 * h + 20 * m, 20 * h + 40 * m, "School", 43.1, -76.2),
            drive(20 * h + 40 * m, 21 * h, 12.0),
            stay(21 * h, 24 * h, "Home"),
        )
        val sum = DaySummary.of(entries, 0, 24 * h)
        assertEquals(listOf("Home", "School"), sum.stays.map { it.name })
        assertEquals(13 * h, sum.stays[0].totalMs)
        assertEquals(2, sum.stays[0].spans.size)
        assertEquals(10 * h + 20 * m, sum.stays[1].totalMs)
        assertEquals(listOf("Driving"), sum.journeys.map { it.name })
        assertEquals(40 * m, sum.journeysMs)
        assertEquals(24_000.0, sum.journeysM, 0.1)
        assertEquals(0L, sum.unrecordedMs)
    }

    @Test fun unnamedSpotsApartStayApartAndGapsAreUnrecorded() {
        val entries = listOf(
            stay(8 * h, 9 * h, null, 43.0, -76.1),
            stay(10 * h, 11 * h, null, 43.05, -76.1),     // about 5 km away
            stay(12 * h, 12 * h + 30 * m, null, 43.0003, -76.1001),   // back at the first spot
            drive(9 * h, 9 * h + 10 * m, 3.0, Mode.WALKING),
        )
        val sum = DaySummary.of(entries, 0, 13 * h)      // today, until 1 PM
        assertEquals(2, sum.stays.size)
        assertEquals(90 * m, sum.stays[0].totalMs)
        assertEquals(listOf("Walking"), sum.journeys.map { it.name })
        assertEquals(13 * h - (2 * h + 30 * m) - 10 * m, sum.unrecordedMs)
    }
}
