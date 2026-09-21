package com.patgrady64.picroulette

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
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
    private val externalImageRequest = mutableStateOf<ExternalImageRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = rouletteYellow,
                    surface = Color(0xFF0A0A0A),
                    background = Color(0xFF0A0A0A)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0A)
                ) {
                    PicRouletteApp(
                        themeColor = rouletteYellow,
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
