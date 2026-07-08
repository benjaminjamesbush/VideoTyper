package com.videotyper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videotyper.data.SmbBrowser
import com.videotyper.data.SmbEntry
import com.videotyper.data.SmbListing
import com.videotyper.data.SmbServer
import com.videotyper.player.SmbSupport

/**
 * Browses an SMB server's shares/folders and lets the child (or parent) tap a video file to play.
 * Folder navigation is an internal path stack; hardware Back and the on-screen Back button both go
 * up a level, then exit to the menu at the root.
 */
@Composable
fun NetworkBrowserScreen(
    server: SmbServer,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
) {
    // Stack of credential-free directory URLs, starting at the server root.
    var stack by remember { mutableStateOf(listOf(server.rootUrl())) }
    val currentUrl = stack.last()
    var listing by remember { mutableStateOf<SmbListing?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(currentUrl, reloadKey) {
        listing = null
        listing = SmbBrowser.list(server, currentUrl)
    }

    val goUp: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }
    BackHandler(onBack = goUp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = goUp) { Text(if (stack.size > 1) "‹ Up" else "‹ Back") }
            Spacer(Modifier.width(12.dp))
            Text(
                text = server.label,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = relativePath(server.rootUrl(), currentUrl),
            color = Color.Gray,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))

        when (val l = listing) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting…", color = Color.Gray, fontSize = 14.sp)
                }
            }

            is SmbListing.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("Couldn't open this location", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(l.message, color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { reloadKey++ }) { Text("Retry") }
                }
            }

            is SmbListing.Ok -> {
                if (l.entries.isEmpty()) {
                    Text("No folders or videos here.", color = Color.Gray, fontSize = 14.sp)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(l.entries) { entry ->
                            EntryRow(entry, onClick = {
                                if (entry.isDirectory) {
                                    val dir = if (entry.url.endsWith("/")) entry.url else entry.url + "/"
                                    stack = stack + dir
                                } else {
                                    onPlay(
                                        SmbSupport.urlWithCredentials(
                                            entry.url, server.username, server.password, server.domain
                                        )
                                    )
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: SmbEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (entry.isDirectory) "📁" else "▶",
            fontSize = 18.sp,
            color = if (entry.isDirectory) Color(0xFFFFCC66) else Color(0xFF9FD4FF)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = entry.name,
            color = Color.White,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "share/folder/…" shown under the title, derived from how deep we are past the server root. */
private fun relativePath(rootUrl: String, currentUrl: String): String {
    val rel = currentUrl.removePrefix(rootUrl).trim('/')
    return if (rel.isEmpty()) "Shares" else rel
}
