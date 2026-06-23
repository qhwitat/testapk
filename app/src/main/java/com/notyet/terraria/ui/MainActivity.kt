package com.notyet.terraria.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.notyet.terraria.ui.screens.HomeScreen
import com.notyet.terraria.ui.screens.LogsScreen
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen { HOME, LOGS }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask the user to exempt us from battery optimisation on first launch.
        // Without this, Android may kill proot mid-game when the screen is off.
        // The permission is declared in AndroidManifest — no runtime grant needed,
        // we just open the system dialog once.
        requestBatteryOptimisationExemption()

        setContent {
            val serverState by viewModel.serverState.collectAsState()
            val networks    by viewModel.networks.collectAsState()
            val setupState  by viewModel.setupState.collectAsState()
            val logs        by viewModel.logs.collectAsState()

            var currentScreen by remember { mutableStateOf(Screen.HOME) }

            when (currentScreen) {
                Screen.HOME -> HomeScreen(
                    serverState      = serverState,
                    networks         = networks,
                    port             = 7777,
                    setupState       = setupState,
                    onToggleServer   = { viewModel.toggleServer() },
                    onCopyAddress    = { viewModel.copyConnectionAddress() },
                    onNavigateToLogs = { currentScreen = Screen.LOGS }
                )
                Screen.LOGS -> LogsScreen(
                    logs   = logs,
                    onBack = { currentScreen = Screen.HOME }
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Opens the system "ignore battery optimisations" dialog if we're not
     * already exempt. Android closes the dialog automatically if the user
     * taps "Allow" or "Deny" — no result handling needed.
     *
     * Only runs once per install because after the user responds the system
     * flag [PowerManager.isIgnoringBatteryOptimizations] becomes true.
     */
    private fun requestBatteryOptimisationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return  // already exempt

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }

        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is always resolvable on
        // API 23+ (it opens a system dialog, not a third-party app).
        startActivity(intent)
    }
}
