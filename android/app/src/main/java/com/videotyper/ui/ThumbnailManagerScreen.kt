package com.videotyper.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videotyper.data.PosterSearch
import com.videotyper.data.RecentVideo
import com.videotyper.data.SettingsStore
import com.videotyper.data.ThumbnailChoice
import com.videotyper.data.ThumbnailStore
import com.videotyper.data.TitleParser

private val PanelGray = Color(0xFF2A2A2A)

/**
 * Lets the user fix a video's thumbnail when the auto-match is wrong: search online posters by an
 * editable title, tap one to use it, force the decoded video frame, or return to automatic. The
 * chosen override is persisted; [onDone] returns to the menu and triggers a refresh.
 */
@Composable
fun ThumbnailManagerScreen(video: RecentVideo, onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ThumbnailStore(context) }

    var query by remember { mutableStateOf(TitleParser.parse(video.name).title) }
    var searchTerm by remember { mutableStateOf(query) } // what we actually searched for
    val preferTv = remember(video.name) { TitleParser.parse(video.name).isTv }

    val tmdbKey = remember { SettingsStore(context).effectiveTmdbKey() }
    val candidates by produceState<List<PosterSearch.Candidate>?>(initialValue = null, key1 = searchTerm) {
        value = null
        value = PosterSearch.search(searchTerm, preferTv = preferTv, tmdbKey = tmdbKey)
    }

    // Frame chooser: total duration + the scrubbed position + a live (debounced) preview of it.
    val currentChoice = remember { store.choiceFor(video.uri) }
    val durationMs by produceState(0L, video.uri) { value = ThumbnailLoader.durationMs(context, video.uri) }
    var frameMs by remember {
        mutableLongStateOf((currentChoice as? ThumbnailChoice.Frame)?.timeMs ?: ThumbnailChoice.DEFAULT_FRAME_MS)
    }
    var cropBias by remember {
        mutableFloatStateOf((currentChoice as? ThumbnailChoice.Frame)?.cropBias ?: 0.5f)
    }
    val framePreview by produceState<Bitmap?>(initialValue = null, key1 = frameMs) {
        delay(220) // debounce: only decode once the slider settles (rapid scrubbing cancels this)
        value = ThumbnailLoader.decodeFrameAt(context, video.uri, frameMs)
    }

    fun choose(choice: ThumbnailChoice) {
        store.setChoice(video.uri, choice)
        if (choice is ThumbnailChoice.Auto) store.clearAutoArt(video.uri)
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDone) { Text("‹ Back") }
            Spacer(Modifier.width(12.dp))
            Text(
                "Thumbnail",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(video.name, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { searchTerm = query.trim() }) { Text("Search") }
        }

        Spacer(Modifier.height(12.dp))

        // Frame chooser: scrub to any moment (the first frame is often a bad poster), then drag the
        // highlighted box to choose which part of the wide frame becomes the 2:3 poster.
        Text("Video frame — pick the moment", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        FrameCropSelector(framePreview, cropBias) { cropBias = it }
        Text(
            "Drag the box to choose the part of the frame to keep.",
            color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
        )
        Slider(
            value = frameMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { frameMs = it.toLong() },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(frameMs), color = Color.Gray, fontSize = 12.sp)
            Text(if (durationMs > 0L) formatMs(durationMs) else "…", color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { choose(ThumbnailChoice.Frame(frameMs, cropBias)) }) { Text("Use this frame") }
            OutlinedButton(onClick = { choose(ThumbnailChoice.Auto) }) { Text("Automatic") }
        }

        Spacer(Modifier.height(16.dp))

        when (val list = candidates) {
            null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> if (list.isEmpty()) {
                Text(
                    "No online posters found. Try a different title, or use the video frame.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(list) { candidate ->
                        PosterOption(candidate, onClick = { choose(ThumbnailChoice.Poster(candidate.artUrl)) })
                    }
                }
            }
        }
    }
}

/**
 * Shows the full decoded frame with a draggable 2:3 crop window (the rest dimmed), so the user can
 * pan which slice of a wide frame becomes the portrait poster. [cropBias] 0..1 is the window's
 * position along the pannable axis; dragging horizontally reports a new bias.
 */
@Composable
private fun FrameCropSelector(bmp: Bitmap?, cropBias: Float, onBiasChange: (Float) -> Unit) {
    if (bmp == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(PanelGray),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        return
    }
    val frameAspect = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceAtLeast(0.1f)
    val windowWFrac = ((2f / 3f) / frameAspect).coerceIn(0.1f, 1f) // 2:3 window width as a fraction of the frame width
    val panRange = 1f - windowWFrac
    val biasState = rememberUpdatedState(cropBias)
    var boxW by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect)
            .clip(RoundedCornerShape(8.dp))
            .background(PanelGray)
            .onSizeChanged { boxW = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(panRange) {
                if (panRange > 0f) detectDragGestures { _, drag ->
                    onBiasChange((biasState.value + drag.x / (boxW * panRange)).coerceIn(0f, 1f))
                }
            }
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Selected frame",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(Modifier.fillMaxSize()) {
            val leftPx = size.width * (biasState.value.coerceIn(0f, 1f) * panRange)
            val wPx = size.width * windowWFrac
            val dim = Color(0x99000000)
            drawRect(dim, size = Size(leftPx, size.height))
            drawRect(dim, topLeft = Offset(leftPx + wPx, 0f), size = Size((size.width - leftPx - wPx).coerceAtLeast(0f), size.height))
            drawRect(Color(0xFFFFD54F), topLeft = Offset(leftPx, 0f), size = Size(wPx, size.height), style = Stroke(width = 3.dp.toPx()))
        }
    }
}

/** mm:ss (or h:mm:ss for long videos) for the scrub position/duration labels. */
private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun PosterOption(candidate: PosterSearch.Candidate, onClick: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(initialValue = null, key1 = candidate.artUrl) {
        value = ThumbnailLoader.poster(context, candidate.artUrl)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(PanelGray),
            contentAlignment = Alignment.Center
        ) {
            val b = bmp
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = candidate.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(22.dp).height(22.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = candidate.year?.let { "${candidate.title} ($it)" } ?: candidate.title,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
