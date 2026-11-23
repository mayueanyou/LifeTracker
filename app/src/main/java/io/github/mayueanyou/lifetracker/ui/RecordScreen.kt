package io.github.mayueanyou.lifetracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.lifetracker.engine.DayBuilder
import io.github.mayueanyou.lifetracker.engine.DayRecord
import io.github.mayueanyou.lifetracker.engine.Entry
import io.github.mayueanyou.lifetracker.engine.Mode
import io.github.mayueanyou.lifetracker.data.LifeDb
import io.github.mayueanyou.lifetracker.engine.Fix
import io.github.mayueanyou.lifetracker.location.Recording
import io.github.mayueanyou.lifetracker.places.Lookups
import io.github.mayueanyou.lifetracker.money.EntryMoney
import io.github.mayueanyou.lifetracker.screentime.AppSession
import io.github.mayueanyou.lifetracker.money.Money
import java.time.YearMonth
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The timeline's scale: an hour is this tall.
private val HOUR: Dp = 132.dp
/** Below the now line on today's timeline: enough to see the entry under way, not the empty hours to come. */
private val NOW_ROOM: Dp = 72.dp
private val MIN_BLOCK: Dp = 26.dp     // the least an entry is drawn at, however brief
private val AXIS: Dp = 52.dp
private val ORANGE = Color(0xFFF97316)

/**
 * The day, as the Record web app shows it: the map on top, and below it the
 * day as a calendar -- a 24-hour axis, each stay and journey placed at its
 * time and as tall as it lasted. Tapping either side selects the same entry
 * on the other.
 */
@Composable
fun RecordScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var day by remember { mutableStateOf(LocalDate.now()) }
    var record by remember { mutableStateOf<DayRecord?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    // Bumped by a cost change: the day is worked out again without losing the selection.
    var moneyTick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var month by remember { mutableStateOf<Money.Month?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val scope = rememberCoroutineScope()
    // The "where am I" button: a fresh position, centred on the map.
    var located by remember { mutableStateOf<Fix?>(null) }
    var locateRequest by remember { mutableIntStateOf(0) }
    // Somewhere to centre an empty day: the last place you were recorded at all.
    var lastEver by remember { mutableStateOf<Fix?>(null) }
    LaunchedEffect(refresh) {
        lastEver = withContext(Dispatchers.IO) {
            LifeDb.get(context).fixes().latest(1).firstOrNull()?.let { Fix(it.time, it.latitude, it.longitude, it.accuracy) }
        }
    }

    LaunchedEffect(day) { selected = null; editing = null }
    LaunchedEffect(YearMonth.from(day), refresh, moneyTick, record?.money?.total) {
        month = withContext(Dispatchers.IO) { Money.month(context, YearMonth.from(day)) }
    }
    LaunchedEffect(day, refresh, moneyTick) {
        while (true) {
            val built = withContext(Dispatchers.IO) { DayBuilder.build(context, day) }
            record = built
            // Spots still without a name are looked up once, then the day redrawn.
            if (built.missingBuildings.isNotEmpty() || built.missingStreets.isNotEmpty()) {
                val answered = withContext(Dispatchers.IO) {
                    Lookups.fill(context, built.missingBuildings.take(10), built.missingStreets.take(10))
                }
                if (answered > 0) record = withContext(Dispatchers.IO) { DayBuilder.build(context, day) }
            }
            if (day != LocalDate.now()) break
            delay(60_000)      // today keeps moving
        }
    }

    editing?.let { i ->
        val entry = record?.entries?.getOrNull(i)
        if (entry == null) editing = null
        else CostEditor(entry, record?.money?.perEntry?.get(i), record?.screen?.get(i).orEmpty(),
            onChanged = { moneyTick++ }, onDismiss = { editing = null })
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        MonthBar(day, month, record?.takeIf { it.day == day }?.money, onDay = { day = it })
        DayHeader(day, record, onDay = { day = it }, onLocate = {
            scope.launch {
                val fix = currentPosition(context) ?: lastEver
                if (fix == null) {
                    Toast.makeText(context, "No position yet — allow location in Settings", Toast.LENGTH_SHORT).show()
                } else {
                    located = fix
                    locateRequest++
                }
            }
        })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val mapHeight = maxHeight * 0.38f
            Column {
                // Clipped: the map view draws past its own edges otherwise,
                // over the date bar and the timeline.
                Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
                    DayMap(
                        entries = record?.entries.orEmpty(),
                        selected = selected,
                        here = if (day == LocalDate.now()) located ?: record?.lastFix ?: lastEver else null,
                        fallback = lastEver,
                        centreOn = located,
                        centreRequest = locateRequest,
                        onSelect = { selected = it },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    )
                    if (record != null && record!!.entries.isEmpty()) {
                        EmptyDay(day, Recording.wanted(context), onOpenSettings)
                    }
                }
                record?.money?.unplaced?.takeIf { it.isNotEmpty() && record?.day == day }?.let { loose ->
                    Text("Not matched to a place: " + loose.joinToString(", ") {
                        (it.payment.merchant ?: it.payment.app) + " " + Money.format(it.payment.amount, it.payment.currency)
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                // The first tap shows an event on the map; tapping it again, once
                // selected, opens its details and costs.
                Timeline(record, day, selected) { i -> if (selected == i) editing = i else selected = i }
            }
        }
    }
}

@Composable
private fun EmptyDay(day: LocalDate, recording: Boolean, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    !recording && Setup.needed(LocalContext.current) -> {
                        Text("Recording is off", style = MaterialTheme.typography.titleMedium)
                        Text("A few quick steps to start.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenSettings) { Text("Set up") }
                    }
                    !recording -> {
                        Text("Recording is off", style = MaterialTheme.typography.titleMedium)
                        Text("Turn on \"Record my location\" in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                    day == LocalDate.now() -> {
                        Text("Nothing recorded yet today", style = MaterialTheme.typography.titleMedium)
                        Text("Stays appear after 5 minutes in one place; journeys as you travel.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    else -> Text("Nothing was recorded on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The month's spending, and the day's beneath it -- the top of Portal's Record app. Tap for the calendar. */
@Composable
private fun MonthBar(day: LocalDate, month: Money.Month?, money: io.github.mayueanyou.lifetracker.money.DayMoney?, onDay: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) MonthCalendar(day, onPick = { picking = false; onDay(it) }, onDismiss = { picking = false })
    Row(
        Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day.format(DateTimeFormatter.ofPattern("MMMM yyyy")), fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(month?.total?.let { Money.format(it, month.currency) } ?: "—", fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (month?.total != null) MONEY_GREEN else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        month?.byDay?.size?.takeIf { it > 0 }?.let {
            Text("over $it day${if (it == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, record: DayRecord?, onDay: (LocalDate) -> Unit, onLocate: () -> Unit) {
    val today = LocalDate.now()
    val name = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    var picking by remember { mutableStateOf(false) }
    if (picking) MonthCalendar(day, onPick = { picking = false; onDay(it) }, onDismiss = { picking = false })
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onDay(day.minusDays(1)) }) { Text("‹", fontSize = 22.sp) }
        // Tap the name for the month, to jump to any day.
        TextButton(onClick = { picking = true }) {
            Text("$name ▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        TextButton(onClick = { onDay(day.plusDays(1)) }, enabled = day < today) { Text("›", fontSize = 22.sp) }
        TextButton(onClick = onLocate) { Text("◎", fontSize = 22.sp) }
        Spacer(Modifier.weight(1f))
        val money = record?.takeIf { it.day == day }?.money
        Text(money?.total?.let { Money.format(it, money.currency) + if (money.mixed) " (mixed)" else "" } ?: "—",
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = if (money?.total != null) MONEY_GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 14.dp))
    }
}

private data class Block(val index: Int, val top: Dp, val height: Dp)

@Composable
private fun Timeline(record: DayRecord?, day: LocalDate, selected: Int?, onTap: (Int) -> Unit) {
    val zone = ZoneId.systemDefault()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val entries = record?.entries.orEmpty()
    val minuteOf = { t: Long -> ((t - dayStart) / 60_000.0).coerceIn(0.0, 1440.0) }
    val perMinute = HOUR / 60

    // Placed by the clock. A brief entry gets a little room, and one that
    // would then overlap it is nudged down just enough -- but its end stays
    // at its true time, so the drift never accumulates.
    val blocks = remember(entries) {
        var bottom = 0.dp
        entries.mapIndexed { i, e ->
            val top = maxOf(perMinute * minuteOf(e.start).toFloat(), bottom)
            val height = maxOf(MIN_BLOCK, perMinute * minuteOf(e.end).toFloat() - top)
            bottom = top + height
            Block(i, top, height)
        }
    }
    // Where today's now line goes: the clock's place -- or, when the last entry is
    // a brief one drawn taller than it lasted, its bottom, so the line never cuts
    // through what is happening now.
    val nowAt = { perMinute * minuteOf(System.currentTimeMillis()).toFloat() }
    val nowLine = { maxOf(nowAt(), blocks.lastOrNull()?.let { it.top + it.height } ?: 0.dp) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Only once the day shown is the day asked for -- just after switching
    // day, the previous day's entries are still on screen, and scrolling to
    // them put a new day at the wrong hour.
    // Back to the app, or a new stay or journey: today's view moves on with the clock.
    var recenter by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { recenter++ }
    LaunchedEffect(record?.day, entries.size, recenter) {
        if (record?.day != day) return@LaunchedEffect
        val today = day == LocalDate.now()
        // Looking at something you picked: left where it is.
        if (today && selected != null) return@LaunchedEffect
        // Today puts now near the bottom of what is visible, with the day so far above it;
        // another day opens at its first entry after the night.
        val viewport = snapshotFlow { scroll.viewportSize }.first { it > 0 }
        val y = if (today) {
            val nowY = with(density) { nowLine().roundToPx() }
            nowY - viewport + with(density) { NOW_ROOM.roundToPx() }
        } else {
            val minute = entries.firstOrNull { minuteOf(it.start) >= 300 }?.let { minuteOf(it.start) - 30 } ?: 0.0
            with(density) { (perMinute * maxOf(0.0, minute).toFloat()).roundToPx() }
        }
        scroll.scrollTo(y.coerceAtLeast(0))
    }
    LaunchedEffect(selected) {
        val b = blocks.firstOrNull { it.index == selected } ?: return@LaunchedEffect
        scroll.animateScrollTo(with(density) { (b.top - 80.dp).coerceAtLeast(0.dp).roundToPx() })
    }

    Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 8.dp, bottom = 24.dp)) {
        val total = maxOf(HOUR * 24, blocks.lastOrNull()?.let { it.top + it.height } ?: 0.dp)
        Box(Modifier.fillMaxWidth().height(total)) {
            for (h in 0..24) {
                Box(Modifier.offset(y = HOUR * h).padding(start = AXIS).fillMaxWidth().height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)))
                Text(hourName(h), Modifier.offset(y = HOUR * h - 7.dp).width(AXIS - 8.dp),
                    textAlign = TextAlign.End, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var stayNo = 0
            blocks.forEach { b ->
                val e = entries[b.index]
                val number = if (e is Entry.StayEntry) (++stayNo).toString() else null
                EventBlock(e, number, b, b.index == selected, record?.money?.perEntry?.get(b.index),
                    record?.screen?.get(b.index).orEmpty()) { onTap(b.index) }
            }
            // Over the entries, so it shows even along the bottom edge of one.
            if (day == LocalDate.now()) {
                val y = nowLine()
                Box(Modifier.offset(x = AXIS - 6.dp, y = y - 4.dp)
                    .size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
                Box(Modifier.offset(y = y - 1.dp)
                    .padding(start = AXIS - 2.dp).fillMaxWidth().height(2.dp).background(Color(0xFFEF4444)))
            }
        }
    }
}

@Composable
private fun EventBlock(
    entry: Entry, number: String?, block: Block, on: Boolean, money: EntryMoney?,
    screen: List<AppSession>, onTap: () -> Unit,
) {
    val tiny = block.height < 40.dp
    val short = block.height < 72.dp
    val (edge, fill) = when (entry) {
        is Entry.StayEntry -> Color(0xFF6366F1) to Color(0xFF6366F1).copy(alpha = .16f)
        is Entry.JourneyEntry -> Color(modeColour(entry.journey.mode)) to Color(0xFF0EA5E9).copy(alpha = .10f)
    }
    Box(
        // Inset rather than shifted: shifting kept the full width and pushed
        // the right edge -- and the duration -- off the screen.
        Modifier.offset(y = block.top)
            .padding(start = AXIS + 4.dp, end = 12.dp)
            .fillMaxWidth()
            .height(block.height - 1.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (on) ORANGE.copy(alpha = .22f) else fill)
            .border(if (on) 1.dp else 0.dp, if (on) ORANGE.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(7.dp))
            .clickable(onClick = onTap),
    ) {
        Box(Modifier.width(3.dp).fillMaxSize().background(if (on) ORANGE else edge))
        // When the phone was in use, as marks down the right edge at their
        // time, each with its app's icon in the middle when there is room.
        val phoneBar = screen.isNotEmpty() && entry.end > entry.start
        if (phoneBar) {
            val span = (entry.end - entry.start).toFloat()
            val h = block.height - 1.dp
            Box(Modifier.align(Alignment.TopEnd).width(PHONE_BAR).fillMaxSize().background(PHONE.copy(alpha = .12f))) {
                screen.forEach { s ->
                    val top = h * ((s.start - entry.start) / span)
                    val tall = maxOf(1.5.dp, h * ((s.end - s.start) / span))
                    Box(Modifier.offset(y = top).height(tall).fillMaxWidth().background(PHONE.copy(alpha = .85f)),
                        contentAlignment = Alignment.Center) {
                        if (s.millis >= ICON_AFTER_MS) appIcon(s.packageName)?.let {
                            // Its own size even where the stretch is shorter than the icon: it
                            // sits centred on the stretch and may overlap its edges a little.
                            Image(it, contentDescription = s.label, modifier = Modifier.requiredSize(ICON).clip(RoundedCornerShape(4.dp)))
                        }
                    }
                }
            }
        }
        Row(
            Modifier.padding(start = 11.dp, end = if (phoneBar) PHONE_BAR + 6.dp else 12.dp, top = if (tiny) 0.dp else 5.dp).fillMaxWidth()
                .let { if (tiny) it.fillMaxSize() else it },
            verticalAlignment = if (tiny) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val badge = number ?: (entry as Entry.JourneyEntry).journey.mode.icon
            Box(
                Modifier.size(if (tiny) 18.dp else 24.dp).clip(CircleShape)
                    .background(if (number == null) Color.Transparent else if (on) ORANGE else Color(0xFF6366F1).copy(alpha = .25f)),
                contentAlignment = Alignment.Center,
            ) { Text(badge, fontSize = if (number == null) 14.sp else 11.sp, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f)) {
                Text(title(entry), maxLines = if (short) 1 else 2, overflow = TextOverflow.Ellipsis,
                    fontSize = if (tiny) 13.sp else if (entry is Entry.JourneyEntry) 14.sp else 16.sp,
                    color = if (entry is Entry.JourneyEntry) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (!tiny) {
                    if (!short) Text(kind(entry), fontSize = 11.sp, letterSpacing = .5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(details(entry), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    phoneSummary(screen)?.takeIf { block.height >= 92.dp }?.let {
                        Text(it, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = PHONE_TEXT)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                money?.total?.let {
                    Text(Money.format(it, money.currency), fontSize = if (tiny) 12.sp else 15.sp,
                        fontWeight = FontWeight.SemiBold, color = MONEY_GREEN)
                }
                if (!tiny || money?.total == null) {
                    Text(spellMinutes((entry.end - entry.start) / 60_000), fontSize = if (tiny) 12.sp else 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val PHONE = Color(0xFFF59E0B)
private val PHONE_BAR = 40.dp    // wide enough for an app's icon
private val ICON = 16.dp
private const val ICON_AFTER_MS = 5 * 60_000L   // five minutes or more on one app shows its icon
private val PHONE_TEXT = Color(0xFFB45309)

/** Per app, its time during an entry, longest first. */
internal fun byApp(screen: List<AppSession>): List<Pair<String, Long>> =
    screen.groupBy { it.label }.map { (label, s) -> label to s.sumOf { it.millis } }.sortedByDescending { it.second }

/** "📱 42m · X 30m, Chrome 8m" -- only apps used a minute or more are named. */
internal fun phoneSummary(screen: List<AppSession>): String? {
    val total = screen.sumOf { it.millis }
    if (total < 60_000) return null
    val named = byApp(screen).filter { it.second >= 60_000 }.take(2)
        .joinToString(", ") { "${it.first} ${spellMinutes(it.second / 60_000)}" }
    return "📱 ${spellMinutes(total / 60_000)}" + if (named.isNotEmpty()) " · $named" else ""
}

internal fun title(e: Entry) = when (e) {
    is Entry.StayEntry -> (if (e.stay.live) "▸ " else "") + (e.stay.place ?: "Unknown place")
    is Entry.JourneyEntry -> (if (e.journey.live) "▸ " else "") + "${e.journey.fromPlace} → ${e.journey.toPlace}"
}

private fun kind(e: Entry) = when (e) {
    is Entry.StayEntry -> "STAY"
    is Entry.JourneyEntry -> "JOURNEY · " + e.journey.mode.name.lowercase()
}

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())
internal fun timeOfDay(t: Long) = CLOCK.format(Instant.ofEpochMilli(t))

private fun details(e: Entry): String {
    val span = "${timeOfDay(e.start)} – ${timeOfDay(e.end)}"
    return when (e) {
        is Entry.StayEntry -> span
        is Entry.JourneyEntry -> {
            val j = e.journey
            if (j.mode == Mode.MOVING) span else "$span · ${miles(j.distanceM)} · avg ${mph(j.avgKmh)}"
        }
    }
}

private fun miles(m: Double): String {
    val mi = m / 1609.344
    return if (mi < 0.1) "${(m * 3.28084).toInt()} ft" else String.format(Locale.US, "%.1f mi", mi)
}

private fun mph(kmh: Double) = String.format(Locale.US, "%.1f mph", kmh / 1.609344)

private fun hourName(h: Int) = "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12 || h == 24) "AM" else "PM"}"

fun spellMinutes(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0L -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/**
 * Where the phone is right now: a fresh reading if one comes within a few
 * seconds, otherwise the last one Android has. Null without permission.
 */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun currentPosition(context: android.content.Context): Fix? {
    if (!Recording.hasForeground(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val fresh = withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine<android.location.Location?> { cont ->
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
    val location = fresh ?: suspendCancellableCoroutine<android.location.Location?> { cont ->
        client.lastLocation.addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
    }
    return location?.let { Fix(it.time, it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
}
