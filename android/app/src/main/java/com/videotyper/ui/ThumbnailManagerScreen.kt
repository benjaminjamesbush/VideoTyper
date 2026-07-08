package com.videotyper.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choose(ThumbnailChoice.Frame) }) { Text("Use video frame") }
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
