package com.videotyper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.videotyper.data.RecentVideo
import com.videotyper.data.RecentsStore
import com.videotyper.game.GameController
import com.videotyper.ui.MenuScreen
import com.videotyper.ui.PlayerScreen

@UnstableApi
class MainActivity : ComponentActivity() {

    private lateinit var controller: GameController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        controller = GameController(this, lifecycleScope)

        val prefs = getSharedPreferences("videotyper", MODE_PRIVATE)
        val recentsStore = RecentsStore(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // No media loaded yet? Start on the menu so there's always a way to pick a video.
                var showMenu by remember { mutableStateOf(!controller.hasMedia) }
                var recents by remember { mutableStateOf(recentsStore.recents()) }
                var lastOpened by remember { mutableStateOf(prefs.getString("last_uri", null)) }

                val open: (String) -> Unit = { uriString ->
                    persistUriPermissionIfPossible(uriString)
                    val name = resolveDisplayName(uriString)
                    recentsStore.add(uriString, name)
                    recents = recentsStore.recents()
                    prefs.edit().putString("last_uri", uriString).apply()
                    lastOpened = uriString
                    controller.openUri(uriString.toUri())
                    showMenu = false
                }

                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.systemBarsPadding()) {
                        if (showMenu) {
                            MenuScreen(
                                recents = recents,
                                lastNetworkUrl = lastOpened,
                                onPlay = open,
                                onBack = { showMenu = false },
                            )
                        } else {
                            PlayerScreen(
                                controller = controller,
                                onMenuClick = {
                                    controller.player.pause()
                                    showMenu = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Human-readable label for a video URI: the document display name, or the last path segment. */
    private fun resolveDisplayName(uriString: String): String {
        val uri = uriString.toUri()
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx)?.let { if (it.isNotBlank()) return it }
                        }
                    }
            } catch (_: Exception) {
                // fall through to path-based name
            }
        }
        // lastPathSegment keeps credentials out of the label for smb://user:pass@host/… URLs.
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: uriString
    }

    /** Keep read access to SAF-picked files across app restarts. */
    private fun persistUriPermissionIfPossible(uriString: String) {
        if (!uriString.startsWith("content://")) return
        try {
            contentResolver.takePersistableUriPermission(
                uriString.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Not persistable; playback still works for this session.
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause rather than keep the movie (and TTS hints) running invisibly.
        if (!controller.isTyping) controller.player.pause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }
}
