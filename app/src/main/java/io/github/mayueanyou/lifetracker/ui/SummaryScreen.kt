package io.github.mayueanyou.lifetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.lifetracker.engine.DayBuilder
import io.github.mayueanyou.lifetracker.engine.DaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val STAY_COLOUR = Color(0xFF6366F1)
private val JOURNEY_COLOUR = Color(0xFF0EA5E9)

/**
 * The day added up: how long at each place -- every visit to it summed --
 * and how long travelling, by each way of getting about.
 */
@Composable
fun SummaryScreen() {
    val context = LocalContext.current
    var day by remember { mutableStateOf(LocalDate.now()) }
    var refresh by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<DaySummary?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LaunchedEffect(day, refresh) {
        summary = null
        while (true) {
            summary = withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val record = DayBuilder.build(context, day, zone)
                DaySummary.of(record.entries, start, minOf(end, System.currentTimeMillis()))
            }
            if (day != LocalDate.now()) break
            delay(60_000)      // today keeps adding up
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        DayBar(day, onDay = { day = it })
        val s = summary
        if (s == null) return@Column
        if (s.stays.isEmpty() && s.journeys.isEmpty()) {
            Text("Nothing recorded on this day.", Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Totals(s)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.stays.isNotEmpty()) {
                item { Heading("STAYS", s.staysMs, STAY_COLOUR, null) }
                items(s.stays) { ItemRow(it, s.staysMs, STAY_COLOUR) }
            }
            if (s.journeys.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Heading("JOURNEYS", s.journeysMs, JOURNEY_COLOUR, s.journeysM)
                }
                items(s.journeys) { ItemRow(it, s.journeysMs, JOURNEY_COLOUR) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** Stays, journeys and what was not recorded, as numbers and as one bar across the day. */
@Composable
private fun Totals(s: DaySummary) {
    val whole = (s.staysMs + s.journeysMs + s.unrecordedMs).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Total("Stays", s.staysMs, STAY_COLOUR)
        Total("Journeys", s.journeysMs, JOURNEY_COLOUR)
        if (s.unrecordedMs >= 60_000) Total("Not recorded", s.unrecordedMs, MaterialTheme.colorScheme.outlineVariant)
    }
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (s.staysMs > 0) Box(Modifier.weight(s.staysMs.toFloat() / whole).fillMaxHeight().background(STAY_COLOUR))
        if (s.journeysMs > 0) Box(Modifier.weight(s.journeysMs.toFloat() / whole).fillMaxHeight().background(JOURNEY_COLOUR))
        if (s.unrecordedMs > 0) Box(Modifier.weight(s.unrecordedMs.toFloat() / whole).fillMaxHeight())
    }
}

@Composable
private fun Total(label: String, ms: Long, colour: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(spellMs(ms), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Heading(title: String, ms: Long, colour: Color, metres: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, letterSpacing = .5.sp, color = colour, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(spellMs(ms) + (metres?.takeIf { it > 0 }?.let { " · " + km(it) } ?: ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One place or way of travelling: its total, a bar against the others, and each time it happened. */
@Composable
private fun ItemRow(item: DaySummary.Item, of: Long, colour: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((item.mode?.icon?.plus(" ") ?: "") + item.name, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(spellMs(item.totalMs), fontWeight = FontWeight.SemiBold)
        }
        val times = item.spans.size
        Text(
            (if (item.mode == null) "$times visit${if (times == 1) "" else "s"}" else "$times trip${if (times == 1) "" else "s"}") +
                (item.distanceM.takeIf { it > 0 }?.let { " · " + km(it) } ?: ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((item.totalMs.toFloat() / of.coerceAtLeast(1)).coerceIn(0.02f, 1f))
                .fillMaxHeight().background(colour))
        }
        Spacer(Modifier.height(4.dp))
        // Each time, so the total can be checked against the day: "12:00 AM – 10:00 AM · 10h".
        item.spans.forEach { span ->
            Row {
                Text("${timeOfDay(span.start)} – ${timeOfDay(span.end)}" +
                    (if (span.what.isNotEmpty()) "  ${span.what}" else ""),
                    Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(spellMs(span.end - span.start), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** To the nearest minute, so a day's parts add up to its whole. */
private fun spellMs(ms: Long) = if (ms < 30_000) "<1m" else spellMinutes((ms + 30_000) / 60_000)

private fun km(metres: Double) = String.format(Locale.getDefault(), "%.1f km", metres / 1000)
