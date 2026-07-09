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
import com.videotyper.data.SmbServer
import com.videotyper.data.SmbServersStore
import com.videotyper.game.GameController
import com.videotyper.ui.MenuScreen
import com.videotyper.ui.NetworkBrowserScreen
import com.videotyper.ui.PlayerScreen
import com.videotyper.ui.SettingsScreen
import com.videotyper.ui.ThumbnailManagerScreen

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
        val serversStore = SmbServersStore(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // No media loaded yet? Start on the menu so there's always a way to pick a video.
                var showMenu by remember { mutableStateOf(!controller.hasMedia) }
                var browseServer by remember { mutableStateOf<SmbServer?>(null) }
                var manageVideo by remember { mutableStateOf<RecentVideo?>(null) }
                var showSettings by remember { mutableStateOf(false) }
                var recents by remember { mutableStateOf(recentsStore.recents()) }
                var servers by remember { mutableStateOf(serversStore.servers()) }
                var thumbRefresh by remember { mutableStateOf(0) }

                val open: (String) -> Unit = { uriString ->
                    persistUriPermissionIfPossible(uriString)
                    recentsStore.add(uriString, resolveDisplayName(uriString))
                    recents = recentsStore.recents()
                    prefs.edit().putString("last_uri", uriString).apply()
                    controller.openUri(uriString.toUri())
                    browseServer = null
                    showMenu = false
                }

                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    Box(Modifier.systemBarsPadding()) {
                        val server = browseServer
                        val manage = manageVideo
                        when {
                            showSettings -> SettingsScreen(onBack = { showSettings = false })

                            manage != null -> ThumbnailManagerScreen(
                                video = manage,
                                onDone = {
                                    manageVideo = null
                                    thumbRefresh++ // re-resolve the ribbon with the new choice
                                },
                            )

                            server != null -> NetworkBrowserScreen(
                                server = server,
                                onPlay = open,
                                onBack = { browseServer = null },
                            )

                            showMenu -> MenuScreen(
                                recents = recents,
                                servers = servers,
                                thumbRefresh = thumbRefresh,
                                onPlay = open,
                                onManageThumbnail = { manageVideo = it },
                                onBrowseServer = { browseServer = it },
                                onAddServer = {
                                    serversStore.save(it)
                                    servers = serversStore.servers()
                                },
                                onDeleteServer = {
                                    serversStore.delete(it.id)
                                    servers = serversStore.servers()
                                },
                                onOpenSettings = { showSettings = true },
                                onBack = { showMenu = false },
                            )

                            else -> PlayerScreen(
                                controller = controller,
                                onMenuClick = {
                                    controller.leaveGame()
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

    override fun onResume() {
        super.onResume()
        controller.onEnterForeground()
    }

    override fun onPause() {
        super.onPause()
        // Screen off or left the app: silence the game's spoken prompts / hint loop immediately,
        // even mid typing round (the movie is paused in onStop). Resumed in onResume.
        controller.onEnterBackground()
    }

    override fun onStop() {
        super.onStop()
        // Pause rather than keep the movie running invisibly.
        if (!controller.isTyping) controller.player.pause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }
}
