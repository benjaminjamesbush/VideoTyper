package com.videotyper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videotyper.data.Http
import com.videotyper.data.SettingsStore
import com.videotyper.data.ThumbnailStore
import kotlinx.coroutines.launch

private val LinkBlue = Color(0xFF9FD4FF)

/**
 * App settings. Currently the TMDB API key used for poster art, with on-screen instructions for
 * where to get one (free) and a Test button that validates it.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(store.tmdbKey() ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val hasBundledKey = remember { store.bundledTmdbKey() != null }

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
            Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        Text("Movie & TV posters (TMDB)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasBundledKey) {
                "Recent videos are matched to official posters via The Movie Database (TMDB). " +
                    "A key is already built in, so this works out of the box — you only need your " +
                    "own key if you'd rather use your own quota. To get one (free):"
            } else {
                "Recent videos are matched to official posters via The Movie Database (TMDB). " +
                    "This build has no key, so enter your own (free) to enable it:"
            },
            color = Color.Gray,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "1. Create a free account at themoviedb.org.\n" +
                "2. Open Settings → API (themoviedb.org/settings/api).\n" +
                "3. Request an API key (choose Developer; any app URL is fine).\n" +
                "4. Copy the \"API Key (v3 auth)\" value — a 32-character code — and paste it below.",
            color = Color.Gray,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tip: TMDB's signup pages aren't mobile-friendly — easiest on a computer.",
            color = Color.Gray,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Open themoviedb.org/settings/api",
            color = LinkBlue,
            fontSize = 13.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it; status = null },
            singleLine = true,
            label = { Text("TMDB API key") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                store.setTmdbKey(key)
                // Forget "no poster found" caches so videos re-search with the new key.
                ThumbnailStore(context).clearAllAutoArt()
                status = if (key.isBlank()) "Cleared — using free fallback sources." else "Saved."
            }) { Text("Save") }
            OutlinedButton(
                enabled = key.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    status = "Testing…"
                    scope.launch {
                        val ok = Http.getString(
                            "https://api.themoviedb.org/3/configuration?api_key=${key.trim()}"
                        ) != null
                        status = if (ok) "Key works ✓" else "Key rejected — check it and try again."
                        testing = false
                    }
                }
            ) { Text("Test key") }
        }

        status?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color.White, fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "When a poster isn't on TMDB, the app falls back to the free iTunes and TVmaze " +
                "sources, then a video frame.",
            color = Color.Gray,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            color = Color.Gray,
            fontSize = 11.sp
        )
    }
}
