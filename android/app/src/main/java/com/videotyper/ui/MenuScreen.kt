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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.videotyper.data.SmbServer
import com.videotyper.player.SmbSupport
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val PanelGray = Color(0xFF2A2A2A)

/**
 * Full-screen video chooser (no game keyboard). Recents show as a horizontally scrollable ribbon
 * of thumbnails; below are a local-file picker and a network section that lists saved SMB servers
 * (tap to browse their folders) plus a direct http(s) stream field.
 */
@Composable
fun MenuScreen(
    recents: List<RecentVideo>,
    servers: List<SmbServer>,
    thumbRefresh: Int,
    onPlay: (String) -> Unit,
    onManageThumbnail: (RecentVideo) -> Unit,
    onBrowseServer: (SmbServer) -> Unit,
    onAddServer: (SmbServer) -> Unit,
    onDeleteServer: (SmbServer) -> Unit,
    onBack: () -> Unit,
) {
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPlay(uri.toString()) }

    var showAddServer by remember { mutableStateOf(false) }

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

        SectionLabel("Recent")
        // SMB recents are shown only if their server answers right now (checked when this screen
        // opens, with short timeouts); local/http recents always show. Non-network ones appear
        // immediately, network ones pop in once confirmed reachable.
        val visibleRecents by produceState(
            initialValue = recents.filterNot { it.uri.startsWith("smb://") },
            key1 = recents
        ) {
            val reachable = coroutineScope {
                recents.filter { it.uri.startsWith("smb://") }
                    .map { r -> async { if (SmbSupport.isReachable(r.uri)) r.uri else null } }
                    .awaitAll()
                    .filterNotNull()
                    .toSet()
            }
            value = recents.filter { !it.uri.startsWith("smb://") || it.uri in reachable }
        }
        if (visibleRecents.isEmpty()) {
            Text("Nothing yet — open a local or network video below.", color = Color.Gray, fontSize = 13.sp)
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(visibleRecents) { video ->
                    RecentCard(
                        video = video,
                        thumbRefresh = thumbRefresh,
                        onClick = { onPlay(video.uri) },
                        onManage = { onManageThumbnail(video) },
                    )
                }
            }
            Text("Tip: tap ✎ on a poster to fix its thumbnail.", color = Color.Gray, fontSize = 11.sp)
        }

        Spacer(Modifier.height(28.dp))

        SectionLabel("Local")
        Button(
            onClick = { filePicker.launch(arrayOf("video/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open local file…") }

        Spacer(Modifier.height(28.dp))

        SectionLabel("Network")
        if (servers.isEmpty()) {
            Text("Add an SMB server (NAS / shared folder), then tap it to browse.", color = Color.Gray, fontSize = 13.sp)
        } else {
            servers.forEach { server ->
                ServerRow(server, onOpen = { onBrowseServer(server) }, onDelete = { onDeleteServer(server) })
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showAddServer = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Add SMB server")
        }

        Spacer(Modifier.height(20.dp))

        Text("Web stream (http/https)", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        var streamUrl by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = streamUrl,
                onValueChange = { streamUrl = it },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val t = streamUrl.trim()
                if (t.startsWith("http")) onPlay(t)
            }) { Text("Play") }
        }
    }

    if (showAddServer) {
        AddServerDialog(
            onDismiss = { showAddServer = false },
            onSave = { server ->
                showAddServer = false
                onAddServer(server)
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ServerRow(server: SmbServer, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🖥", fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(server.label, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.host, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onDelete) { Text("Remove", color = Color(0xFFE58A8A)) }
    }
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onSave: (SmbServer) -> Unit) {
    var host by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add SMB server") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Server name or IP (e.g. 192.168.0.10 or MYNAS)", color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(host, { host = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(label, { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Label (optional)") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(username, { username = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Username (blank = guest)") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Password") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(domain, { domain = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Domain (optional)") })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val h = host.trim()
                    if (h.isNotEmpty()) {
                        onSave(
                            SmbServer(
                                id = UUID.randomUUID().toString(),
                                label = label.trim().ifBlank { h },
                                host = h,
                                username = username.trim(),
                                password = password,
                                domain = domain.trim(),
                            )
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RecentCard(video: RecentVideo, thumbRefresh: Int, onClick: () -> Unit, onManage: () -> Unit) {
    val context = LocalContext.current
    // Portrait poster shape (2:3) like Plex/Emby; frame-grab fallbacks are center-cropped into it.
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = video.uri, key2 = thumbRefresh) {
        value = ThumbnailLoader.load(context, video.uri, video.name)
    }

    Column(modifier = Modifier.width(120.dp)) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PanelGray)
                .clickable(onClick = onClick),
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
                Text("▶", color = Color.Gray, fontSize = 28.sp)
            }
            // Edit affordance to fix the thumbnail.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .clickable(onClick = onManage)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("✎", color = Color.White, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = video.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp)
        )
    }
}
