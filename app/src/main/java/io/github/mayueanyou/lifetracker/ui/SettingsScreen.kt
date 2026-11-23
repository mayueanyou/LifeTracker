package io.github.mayueanyou.lifetracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.mayueanyou.lifetracker.Diagnostics
import io.github.mayueanyou.lifetracker.data.LifeDb
import io.github.mayueanyou.lifetracker.data.PlaceRow
import io.github.mayueanyou.lifetracker.location.Recording
import io.github.mayueanyou.lifetracker.data.MoneyApp
import io.github.mayueanyou.lifetracker.money.MoneyApps
import io.github.mayueanyou.lifetracker.backup.Backup
import io.github.mayueanyou.lifetracker.backup.BackupWorker
import io.github.mayueanyou.lifetracker.backup.Drive
import io.github.mayueanyou.lifetracker.backup.GoogleAccount
import android.app.Activity
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.unit.sp
import io.github.mayueanyou.lifetracker.places.Lookups
import io.github.mayueanyou.lifetracker.screentime.UsageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/** Everything that is set up once: what Android allows, recording, money, and your places. */
@Composable
fun SettingsScreen(onTour: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableIntStateOf(0) }
    // Read again on every return: granting location starts recording by itself.
    var recording by remember(checks) { mutableStateOf(Recording.wanted(context)) }
    var lookups by remember { mutableStateOf(Lookups.allowed(context)) }
    var naming by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val askMany = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks++ }
    val askOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    // A recorder started before notifications were allowed has its notice hidden;
    // starting it again once they are allowed puts "Recording your day" on show.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checks++
        if (granted && Recording.wanted(context)) Recording.start(context)
    }
    // Each sensitive access is explained in full first; Android is asked only after "Continue".
    val disclose = rememberDisclosure()

    val usage = remember(checks) { UsageReader(context).hasAccess() }
    val foreground = remember(checks) { Recording.hasForeground(context) }
    val background = remember(checks) { Recording.hasBackground(context) }
    val notifications = remember(checks) { Recording.hasNotifications(context) }
    val unrestricted = remember(checks) { batteryUnrestricted(context) }
    val reading = remember(checks) { MoneyApps.hasAccess(context) }

    // Google account: sign in, back up, restore, sign out (Google.kt).
    val google = rememberGoogle()
    val email = google.email
    val busy = google.busy
    val backupStatus = remember(google.version, checks) { Backup.status(context) }
    var confirmRestore by remember { mutableStateOf(false) }
    fun withGoogle(action: String) = google.run(action)

    val status by produceState(Triple(0, null as Long?, null as Float?), checks) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val dao = LifeDb.get(context).fixes()
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val last = dao.latest(1).firstOrNull()
                Triple(dao.countBetween(start, start + DAY_MILLIS), last?.time, last?.accuracy)
            }
            delay(5_000)
        }
    }
    var placesVersion by remember { mutableIntStateOf(0) }
    val places by produceState(emptyList<PlaceRow>(), placesVersion) {
        value = withContext(Dispatchers.IO) { LifeDb.get(context).places().places() }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
        item {
            Section("Tour", "A minute on how each part works — the map, costs, turning money on, the summary.") {
                OutlinedButton(onClick = onTour) { Text("▶  Watch the tour") }
            }
        }
        item {
            Section("Permissions", "What Android asks for, one step at a time, each explained first.") {
                Step("Usage access — for screen time", usage) {
                    disclose(Disclosure.USAGE) { Access.openUsage(context) }
                }
                Step("Location while using the app", foreground) {
                    disclose(Disclosure.LOCATION) {
                        askMany.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                Step("\"Allow all the time\" — records with the app closed", background, enabled = foreground) {
                    disclose(Disclosure.BACKGROUND_LOCATION) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askOne.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
                Step("Notifications — the small \"recording\" notice", notifications) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                Step("Battery: Unrestricted — so Android doesn't stop recording", unrestricted) {
                    Access.openBattery(context)
                }
                if (!unrestricted) {
                    BatterySteps()
                    BatteryHelp(tried = false)
                }
                Step("Notification access — to read card purchases from your bank's notifications", reading) {
                    disclose(Disclosure.NOTIFICATIONS) { Access.openNotificationReading(context) }
                }
                if (!reading) RestrictedHint()
            }
        }
        item {
            Section("Google account", "Back up your days to your own Google Drive, as plain files you can open anywhere. " +
                "Life Tracker can see only the files it makes there — nothing else in your Drive.") {
                val who = email
                if (who == null) {
                    Button(onClick = { withGoogle("signin") }, enabled = busy == null) { Text("Sign in with Google") }
                } else {
                    Text("Signed in as $who", style = MaterialTheme.typography.titleSmall)
                    Text("Backs up each night to My Drive › Life Tracker.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    backupStatus?.let { st ->
                        val ago = (System.currentTimeMillis() - st.at) / 60_000
                        Text(
                            if (st.error != null) "⚠ Last backup failed: ${st.error}"
                            else "Last backup ${if (ago < 1) "just now" else spellMinutes(ago) + " ago"} · ${st.days} day${if (st.days == 1) "" else "s"} written",
                            color = if (st.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { withGoogle("backup") }, enabled = busy == null) { Text("Back up now") }
                        OutlinedButton(onClick = { confirmRestore = true }, enabled = busy == null) { Text("Restore") }
                    }
                    TextButton(onClick = { withGoogle("signout") }, enabled = busy == null) { Text("Sign out") }
                }
                busy?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            Section("Money", "Purchases come only from notifications: each one goes to where you were when it arrived. " +
                "Your bank counts by itself; any other app is asked about once. The same purchase from several apps counts once.") {
                MoneySection(reading, checks)
                if (!reading) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { disclose(Disclosure.NOTIFICATIONS) { Access.openNotificationReading(context) } }) {
                        Text("Turn on")
                    }
                    RestrictedHint()
                }
            }
        }
        item {
            Section("Recording", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Record my location", style = MaterialTheme.typography.titleSmall)
                        Text(if (recording) "On — a position every 10 m you move" else "Off",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = recording, enabled = foreground, onCheckedChange = { on ->
                        if (on) Recording.start(context) else Recording.stop(context)
                        recording = on
                    })
                }
                if (recording && !unrestricted) {
                    Spacer(Modifier.height(8.dp))
                    Text("Battery is not Unrestricted. If recording stops by itself, allow it above.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                status.second?.let { last ->
                    val minutes = (System.currentTimeMillis() - last) / 60_000
                    Spacer(Modifier.height(8.dp))
                    Text(if (recording && minutes >= 30)
                        "⚠ Nothing recorded for ${spellMinutes(minutes)} — fine if you have been still; if you have moved, recording has stopped. Turn it off and on."
                    else "Last position ${if (minutes < 1) "just now" else "${spellMinutes(minutes)} ago"}",
                        color = if (recording && minutes >= 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text("Today: ${status.first} positions" + (status.second?.let { " · last ${clock(it)}" } ?: "") +
                    (status.third?.let { " · ±${it.toInt()} m" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { exportToday(context) } }) { Text("Export today") }
            }
        }
        item {
            Section("Places", "Your own names always win. Otherwise names come from OpenStreetMap.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Look up place names online", style = MaterialTheme.typography.titleSmall)
                        Text("Asks OpenStreetMap what a spot is called, once per spot. Off keeps the phone fully offline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = lookups, onCheckedChange = { Lookups.setAllowed(context, it); lookups = it })
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { naming = true }, enabled = status.second != null) { Text("Name this place") }
            }
        }
        items(places, key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text("within ${p.radius.toInt()} m", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { LifeDb.get(context).places().delete(p.id) }
                        placesVersion++
                    }
                }) { Text("Remove") }
            }
        }
        item {
            Section("Diagnostics", "If something is wrong: a short report with no locations in it, to send to whoever helps you.") {
                OutlinedButton(onClick = {
                    scope.launch {
                        val report = withContext(Dispatchers.IO) {
                            diagnosticsReport(context, usage, foreground, background, notifications, unrestricted, recording, reading)
                        }
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report),
                            "Share diagnostics"))
                    }
                }) { Text("Share diagnostics") }
            }
        }
        item {
            // Kept for good -- nothing is ever deleted -- so say how much room that takes.
            val size = remember(checks) {
                listOf("life.db", "life.db-wal", "life.db-shm").sumOf { context.getDatabasePath(it).length() }
            }
            Text("Life Tracker · everything is kept on this phone, for good · " +
                String.format(java.util.Locale.getDefault(), "%.1f MB", size / 1_048_576.0) + " used",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        }
    }

    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Restore from Google Drive?") },
        text = { Text("Adds whatever is in your backup and missing from this phone. Nothing here is replaced or removed.") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; withGoogle("restore") }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
    )

    if (naming) NameDialog(onDismiss = { naming = false }) { name, radius ->
        scope.launch {
            withContext(Dispatchers.IO) {
                val last = LifeDb.get(context).fixes().latest(1).firstOrNull() ?: return@withContext
                LifeDb.get(context).places().upsert(PlaceRow(name, last.latitude, last.longitude, radius))
            }
            placesVersion++
            naming = false
        }
    }
}

/**
 * Android holds back notification access from apps not installed from Google
 * Play -- a "restricted setting". Only then is the way round it worth saying.
 */
@Composable
fun RestrictedHint() {
    val context = LocalContext.current
    if (remember { Access.fromPlay(context) }) return
    Text("If Android says it is a restricted setting: App info → ⋮ → Allow restricted settings, then try again.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { Access.openAppInfo(context) }) { Text("Open App info") }
}

@Composable
private fun Section(title: String, note: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (note != null) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Step(label: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text((if (done) "✓ " else "") + label, Modifier.weight(1f),
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (!done) Button(onClick = onClick, enabled = enabled) { Text("Allow") }
    }
}

@Composable
private fun NameDialog(onDismiss: () -> Unit, onSave: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(60.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this place") },
        text = {
            Column {
                Text("Where you are now. Stays here will use this name.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Size")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60.0 to "Small", 110.0 to "Building", 150.0 to "Large").forEach { (r, label) ->
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), radius) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private suspend fun diagnosticsReport(
    context: Context, usage: Boolean, foreground: Boolean, background: Boolean,
    notifications: Boolean, unrestricted: Boolean, recording: Boolean, reading: Boolean,
): String {
    val db = LifeDb.get(context)
    val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val fixes = db.fixes()
    val last = fixes.latest(1).firstOrNull()
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return buildString {
        appendLine("Life Tracker ${info.versionName} (${info.longVersionCode}) on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("permissions: usage=$usage location=$foreground always=$background notifications=$notifications battery-unrestricted=$unrestricted")
        appendLine("recording on: $recording")
        appendLine("google account: ${if (GoogleAccount.signedIn(context)) "signed in" else "no"}; last backup: " +
            (Backup.status(context)?.let { "${Instant.ofEpochMilli(it.at)} days=${it.days}" + (it.error?.let { e -> " error=$e" } ?: "") } ?: "never"))
        appendLine("notification access: $reading; purchases read: " +
            db.money().apps().joinToString { "${it.app}=${it.count}" + if (MoneyApps.counts(context, it.pkg)) "" else " (off)" }.ifEmpty { "none" })
        appendLine("positions today: ${fixes.countBetween(start, start + DAY_MILLIS)}, all: ${fixes.countBetween(0, Long.MAX_VALUE)}")
        appendLine("last position at: ${last?.let { Instant.ofEpochMilli(it.time) } ?: "none"} accuracy ${last?.accuracy}")
        appendLine("named places: ${db.places().places().size}, looked-up names: ${db.places().lookups().size}")
        appendLine("--- log ---")
        append(Diagnostics.read(context).takeLast(8000).ifEmpty { "(empty)" })
    }
}

private fun batteryUnrestricted(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private fun clock(time: Long) = CLOCK.format(Instant.ofEpochMilli(time))

/** Today's positions as a JSON file, handed to whichever app the user picks. */
private suspend fun exportToday(context: Context) {
    val day = LocalDate.now()
    val file = withContext(Dispatchers.IO) {
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val list = JSONArray()
        LifeDb.get(context).fixes().between(start, start + DAY_MILLIS).forEach { f ->
            list.put(JSONObject().apply {
                put("time", Instant.ofEpochMilli(f.time).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString())
                put("latitude", f.latitude); put("longitude", f.longitude)
                f.accuracy?.let { put("accuracy", it.toDouble()) }
                f.speed?.let { put("speed", it.toDouble()) }
                f.bearing?.let { put("bearing", it.toDouble()) }
                f.altitude?.let { put("altitude", it) }
                f.provider?.let { put("provider", it) }
            })
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, "life-recorder-$day.json").apply {
            writeText(JSONObject().put("date", day.toString()).put("fixes", list).toString(1))
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/json")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export today's positions"))
}
