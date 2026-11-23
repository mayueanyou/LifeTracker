package io.github.mayueanyou.lifetracker.ui

import android.content.Intent
import android.os.Bundle
import io.github.mayueanyou.lifetracker.location.LocationService
import io.github.mayueanyou.lifetracker.location.Recording
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.lifetracker.screentime.AppTime
import io.github.mayueanyou.lifetracker.screentime.DayScreenTime
import io.github.mayueanyou.lifetracker.screentime.ScreenTimeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Android may refuse to restart a stopped recorder from the background,
        // but never while the app is open -- so opening it always resumes recording.
        if (Recording.wanted(this) && Recording.hasForeground(this) && !LocationService.running) {
            Recording.start(this)
        }
        // Location just allowed, and recording never turned off by you: it starts by itself.
        if (!Recording.wanted(this) && !Recording.turnedOffByYou(this) && Recording.hasForeground(this)) {
            Recording.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Accent) else lightColorScheme(primary = Accent)
            MaterialTheme(colorScheme = scheme) { Home() }
        }
    }
}

private val Accent = Color(0xFF4F46E5)

/** Record, Summary, Screen time, and Settings -- where everything that needs setting up lives. */
@Composable
private fun Home() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    // The tour, once, on the first open -- and from Settings whenever asked for.
    var tour by remember { mutableStateOf(!Intro.seen(context)) }
    // The setup guide, until everything the app needs is allowed -- and again if any of it is taken away.
    var setup by remember { mutableStateOf(Setup.needed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (Setup.needed(context)) setup = true }
    if (tour) {
        IntroVideo(last = if (setup) "Get started" else "Close") { Intro.markSeen(context); tour = false }
        return
    }
    if (setup) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            SetupGuide(onDone = { setup = false; tab = 0 })
        }
        return
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> RecordScreen(onOpenSettings = { if (Setup.needed(context)) setup = true else tab = 3 })
                1 -> SummaryScreen()
                2 -> ScreenTimeScreen()
                else -> SettingsScreen(onTour = { tour = true })
            }
        }
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                icon = { Text("🗺", fontSize = 20.sp) }, label = { Text("Record") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                icon = { Text("📊", fontSize = 20.sp) }, label = { Text("Summary") })
            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                icon = { Text("⏱", fontSize = 20.sp) }, label = { Text("Screen time") })
            NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                icon = { Text("⚙", fontSize = 20.sp) }, label = { Text("Settings") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTimeScreen() {
    val context = LocalContext.current
    val store = remember { ScreenTimeStore(context) }
    var hasAccess by remember { mutableStateOf(store.hasAccess()) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var refresh by remember { mutableStateOf(0) }

    // Coming back from Settings, or back to the app later: look again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = store.hasAccess()
        refresh++
    }

    val data by produceState<DayScreenTime?>(null, day, hasAccess, refresh) {
        value = withContext(Dispatchers.IO) { store.day(day) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Life Tracker") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            if (!hasAccess) {
                AccessCard()
                return@Column
            }
            DayBar(day, onDay = { day = it })
            Summary(data)
            Spacer(Modifier.height(8.dp))
            val apps = data?.apps.orEmpty()
            if (data != null && apps.isEmpty()) {
                Text("No screen time recorded for this day.", Modifier.padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val longest = apps.maxOfOrNull { it.millis } ?: 1L
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(apps, key = { it.packageName }) { AppRow(it, longest) }
            }
        }
    }
}

/** Screen time is asked for here, where it is used: explained, then straight to Life Tracker's switch. */
@Composable
private fun AccessCard() {
    val context = LocalContext.current
    val disclose = rememberDisclosure()
    Card(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("See your screen time", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("How long you use each app, each day and during each place you stayed. It stays on this phone.")
            Spacer(Modifier.height(8.dp))
            Text("Android opens its Usage access page at Life Tracker's switch — turn it on, then come back.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { disclose(Disclosure.USAGE) { Access.openUsage(context) } }) { Text("Turn on") }
        }
    }
}

@Composable
internal fun DayBar(day: LocalDate, onDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val name = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onDay(day.minusDays(1)) }) { Text("‹", fontSize = 24.sp) }
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { onDay(day.plusDays(1)) }, enabled = day < today) { Text("›", fontSize = 24.sp) }
    }
}

@Composable
private fun Summary(data: DayScreenTime?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (data == null) "—" else spell(data.totalMillis), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (data == null) "" else "${data.unlocks} unlock${if (data.unlocks == 1) "" else "s"} · ${data.apps.size} apps",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppRow(app: AppTime, longest: Long) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(app.label, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                Text(spell(app.millis), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((app.millis.toFloat() / longest).coerceIn(0.02f, 1f)).fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

private fun spell(millis: Long): String {
    val minutes = millis / 60_000
    if (minutes < 1) return "${millis / 1000}s"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
