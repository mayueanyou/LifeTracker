package io.github.mayueanyou.lifetracker.engine

import kotlin.math.abs

/**
 * A day's time added up: every stay at one place counted together (home
 * from midnight to 10 AM and again from 9 PM is one line, its two spans
 * summed), every journey by how you travelled, and what was not recorded.
 */
class DaySummary(
    val stays: List<Item>,
    val journeys: List<Item>,
    /** The part of the day so far -- all of it, for a day gone by -- that holds no entry. */
    val unrecordedMs: Long,
) {
    /** One place, or one way of travelling, and each time it happened. */
    class Item(val name: String, val mode: Mode?, val spans: List<Span>, val distanceM: Double) {
        val totalMs get() = spans.sumOf { it.end - it.start }
    }

    /** [what] names a journey's route; empty for a stay. */
    class Span(val start: Long, val end: Long, val what: String = "")

    val staysMs get() = stays.sumOf { it.totalMs }
    val journeysMs get() = journeys.sumOf { it.totalMs }
    val journeysM get() = journeys.sumOf { it.distanceM }

    companion object {
        /** Stays at unnamed spots this close together are the same spot. */
        private const val SAME_SPOT_DEG = 0.0009      // about 100 m

        /** [dayStart]..[until]: the whole day, or for today, up to now. */
        fun of(entries: List<Entry>, dayStart: Long, until: Long): DaySummary {
            val stays = mutableListOf<Triple<String, Pair<Double, Double>?, MutableList<Span>>>()
            entries.filterIsInstance<Entry.StayEntry>().forEach { e ->
                val s = e.stay
                val span = Span(s.start, s.end)
                // A named place is one line, wherever in it you were; an unnamed one is
                // its spot, so two different unknown places are not added together.
                val found = if (s.place != null) stays.firstOrNull { it.first == s.place }
                else stays.firstOrNull { it.first == UNKNOWN && it.second != null &&
                    abs(it.second!!.first - s.lat) < SAME_SPOT_DEG && abs(it.second!!.second - s.lon) < SAME_SPOT_DEG }
                if (found != null) found.third += span
                else stays += Triple(s.place ?: UNKNOWN, if (s.place == null) s.lat to s.lon else null, mutableListOf(span))
            }
            val journeys = entries.filterIsInstance<Entry.JourneyEntry>().groupBy { it.journey.mode }.map { (mode, list) ->
                Item(MODE_NAMES.getValue(mode), mode,
                    list.map { Span(it.start, it.end, "${it.journey.fromPlace ?: "?"} → ${it.journey.toPlace ?: "?"}") },
                    list.sumOf { it.journey.distanceM })
            }
            val recorded = entries.sumOf { (minOf(it.end, until) - maxOf(it.start, dayStart)).coerceAtLeast(0) }
            return DaySummary(
                stays.map { Item(it.first, null, it.third, 0.0) }.sortedByDescending { it.totalMs },
                journeys.sortedByDescending { it.totalMs },
                (until - dayStart - recorded).coerceAtLeast(0),
            )
        }

        const val UNKNOWN = "Unknown place"
        private val MODE_NAMES = mapOf(Mode.DRIVING to "Driving", Mode.WALKING to "Walking",
            Mode.CYCLING to "Cycling", Mode.MOVING to "Moving")
    }
}
