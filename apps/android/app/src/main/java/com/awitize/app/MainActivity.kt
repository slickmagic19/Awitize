package com.awitize.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.awitize.app.player.PlaybackController
import com.awitize.app.ui.AwitizeApp
import com.awitize.app.ui.AwitizeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as AwApplication).container
        val player = PlaybackController(this, container)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            // reading the state here means switching presets recomposes the app
            AwitizeTheme(container.themePreset.value) {
                AwitizeApp(container, player)
            }
        }
    }
}
