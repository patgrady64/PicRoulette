package com.pgdevhouse.picroulette

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ExternalImageRequest(
    val uris: List<Uri>,
    val requestId: Long = System.nanoTime()
)

class MainActivity : ComponentActivity() {
    private var isAppReady = false
    private var selectedTheme by mutableStateOf(PicRouletteThemeMode.DARK)
    private val externalImageRequest = mutableStateOf<ExternalImageRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        selectedTheme = getPicRouletteThemeMode(this)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        captureExternalImageRequest(intent)

        splashScreen.setKeepOnScreenCondition { !isAppReady }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        lifecycleScope.launch { delay(800); isAppReady = true }
        setContent {
            val rouletteYellow = Color(0xFFFFD700)
            val darkTheme = when (selectedTheme) {
                PicRouletteThemeMode.DARK -> true
                PicRouletteThemeMode.LIGHT -> false
                PicRouletteThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val scheme = if (darkTheme) {
                darkColorScheme(
                    primary = rouletteYellow,
                    surface = Color(0xFF0A0A0A),
                    background = Color(0xFF0A0A0A)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF5E3DAA),
                    onPrimary = Color.White,
                    surface = Color(0xFFFFFFFF),
                    background = Color(0xFFF7F5FA),
                    surfaceVariant = Color(0xFFECE8F1),
                    onSurfaceVariant = Color(0xFF514D59)
                )
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = scheme.background
                ) {
                    PicRouletteApp(
                        themeColor = if (darkTheme) rouletteYellow else Color(0xFF5E3DAA),
                        selectedTheme = selectedTheme,
                        onThemeChanged = { mode ->
                            selectedTheme = mode
                            setPicRouletteThemeMode(this@MainActivity, mode)
                        },
                        externalImageRequest = externalImageRequest.value,
                        onExternalImageRequestConsumed = {
                            externalImageRequest.value = null
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureExternalImageRequest(intent)
    }

    @Suppress("DEPRECATION")
    private fun captureExternalImageRequest(intent: Intent?) {
        if (intent == null) return

        val uris = buildList {
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    intent.data?.let(::add)
                }

                Intent.ACTION_SEND -> {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                        ?.let(::addAll)
                }
            }

            // Some gallery/file apps put shared content in ClipData instead of,
            // or in addition to, EXTRA_STREAM. Supporting both makes PicRoulette
            // show up reliably across different Android gallery apps.
            intent.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index).uri?.let(::add)
                }
            }
        }.distinct()

        if (uris.isNotEmpty()) {
            externalImageRequest.value = ExternalImageRequest(uris)
        }
    }
}
