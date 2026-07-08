package com.videotyper.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.videotyper.data.RecentVideo

private val PanelGray = Color(0xFF2A2A2A)

/**
 * Full-screen video chooser (no game keyboard). Recents show as a horizontally scrollable ribbon
 * of thumbnails; below are a local-file picker and a network (smb://, http(s)://) URL entry.
 */
@Composable
fun MenuScreen(
    recents: List<RecentVideo>,
    lastNetworkUrl: String?,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
) {
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPlay(uri.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("‹ Back") }
            Spacer(Modifier.width(12.dp))
            Text("Choose a video", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        Text("Recent", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (recents.isEmpty()) {
            Text("Nothing yet — open a local or network video below.", color = Color.Gray, fontSize = 13.sp)
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(recents) { video -> RecentCard(video, onClick = { onPlay(video.uri) }) }
            }
        }

        Spacer(Modifier.height(28.dp))

        Text("Local", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { filePicker.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open local file…") }

        Spacer(Modifier.height(28.dp))

        Text("Network", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "smb://user:pass@server/share/movie.mkv  or an http(s):// URL",
            color = Color.Gray,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        var url by remember {
            mutableStateOf(lastNetworkUrl?.takeIf { it.startsWith("smb://") || it.startsWith("http") } ?: "smb://")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val trimmed = url.trim()
                if (trimmed.isNotEmpty() && trimmed != "smb://") onPlay(trimmed)
            }) { Text("Play") }
        }
    }
}

@Composable
private fun RecentCard(video: RecentVideo, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = video.uri) {
        value = ThumbnailLoader.load(context, video.uri)
    }

    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(90.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PanelGray),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(
                    bitmap = t.asImageBitmap(),
                    contentDescription = video.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Placeholder for network sources or not-yet-generated thumbnails.
                Text("▶", color = Color.Gray, fontSize = 28.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = video.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(160.dp)
        )
    }
}
