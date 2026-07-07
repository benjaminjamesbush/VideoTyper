package com.videotyper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.videotyper.game.GameController
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
        var lastOpened by mutableStateOf(prefs.getString("last_uri", null))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    androidx.compose.foundation.layout.Box(Modifier.systemBarsPadding()) {
                        PlayerScreen(
                            controller = controller,
                            lastOpened = lastOpened,
                            onOpened = { opened ->
                                persistUriPermissionIfPossible(opened)
                                prefs.edit().putString("last_uri", opened).apply()
                                lastOpened = opened
                            }
                        )
                    }
                }
            }
        }
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
