package io.github.mayueanyou.lifetracker.ui

import android.content.Context
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mayueanyou.lifetracker.R

private const val VIDEO_W = 720f
private const val VIDEO_H = 1750f

/** Whether the tour has been seen: it plays by itself once, on the first open. */
object Intro {
    private const val PREFS = "intro"
    private const val KEY_SEEN = "seen"

    fun seen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SEEN, true).apply()
}

/**
 * The tour: a minute of the app at work -- the map and timeline, costs, the
 * month, turning money on and a purchase arriving, the summary, screen time.
 * Played full screen; Skip leaves at any time, and at the end it can be
 * watched again. [last] names the button that leaves once it has played.
 */
@Composable
fun IntroVideo(last: String, onDone: () -> Unit) {
    var ended by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    BackHandler { onDone() }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // As large as the screen allows in the video's own shape, centred, black either side --
        // and clear of the status bar, which would sit on its captions.
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        AndroidView(modifier = Modifier.aspectRatio(VIDEO_W / VIDEO_H), factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.intro}"))
                setOnPreparedListener { start() }
                setOnCompletionListener { ended = true }
                // A phone that cannot play it still gets past it.
                setOnErrorListener { _, _, _ -> onDone(); true }
                player = this
            }
        })
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ended) {
                Button(onClick = onDone, Modifier.fillMaxWidth()) { Text(last, fontSize = 17.sp) }
                TextButton(onClick = { ended = false; player?.seekTo(0); player?.start() }) {
                    Text("Watch again", color = Color.White)
                }
            } else {
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.align(Alignment.End).background(Color(0x99000000), RoundedCornerShape(20.dp)),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text("Skip  ›", fontSize = 16.sp) }
            }
        }
    }
}
