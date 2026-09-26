package dev.lh.volsched.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.lh.volsched.ui.editor.EditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 is edge-to-edge regardless; this also makes the system
        // bar icons follow light/dark mode so they stay visible.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Follow the system's dark mode and wallpaper colours. A fixed light
            // scheme would put white status bar icons on a white background
            // whenever the phone is in dark mode.
            val context = LocalContext.current
            val colors =
                if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) {
                    // Two screens don't justify a navigation library.
                    var editing by rememberSaveable { mutableStateOf(false) }
                    if (editing) {
                        EditorScreen(onClose = { editing = false })
                    } else {
                        MainScreen(onEditSchedule = { editing = true })
                    }
                }
            }
        }
    }
}
