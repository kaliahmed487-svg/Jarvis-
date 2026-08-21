package com.jarvis.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.ui.AudioVisualizerView
import kotlinx.coroutines.launch

/**
 * Deliberately the ONLY screen in the app: a fullscreen black canvas hosting
 * the reactive visualizer. No buttons, no menus, no text — everything is
 * voice-driven via the background JarvisService.
 */
class MainActivity : ComponentActivity() {

    private lateinit var visualizer: AudioVisualizerView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            requestStorageAccessIfNeeded()
            startJarvisService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // True black, edge-to-edge, no system chrome distractions.
        window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        visualizer = AudioVisualizerView(this)
        setContentView(visualizer)

        observeState()
        ensurePermissionsThenStart()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JarvisState.mode.collect { visualizer.setMode(it) } }
                launch { JarvisState.amplitude.collect { visualizer.pushAmplitude(it) } }
            }
        }
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += android.Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            requestStorageAccessIfNeeded()
            startJarvisService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** MANAGE_EXTERNAL_STORAGE can only be granted via the system Settings screen on API 30+. */
    private fun requestStorageAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()
        ) {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
