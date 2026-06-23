package com.notyet.terraria.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notyet.terraria.core.network.NetworkDetector
import com.notyet.terraria.core.network.ServerNetwork
import com.notyet.terraria.core.proot.ProotInstaller
import com.notyet.terraria.core.server.ServerState
import com.notyet.terraria.service.TerrariaForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: ProotInstaller,
    private val networkDetector: NetworkDetector,
) : ViewModel() {

    // ── Setup state ───────────────────────────────────────────────────────────

    sealed class SetupState {
        object Checking                          : SetupState()
        data class Installing(val progress: String) : SetupState()
        object Ready                             : SetupState()
        data class Error(val message: String)   : SetupState()
    }

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Checking)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    // ── Server state ──────────────────────────────────────────────────────────
    // Derived from TerrariaForegroundService.isRunning so it always reflects
    // the real process state — even if the service was killed by the OS.

    val serverState: StateFlow<ServerState> = TerrariaForegroundService.isRunning
        .map { running -> if (running) ServerState.RUNNING else ServerState.STOPPED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerState.STOPPED)

    // ── Logs ──────────────────────────────────────────────────────────────────
    // Live reference to the service companion's rolling log buffer.

    val logs: StateFlow<List<String>> = TerrariaForegroundService.logs

    // ── Network ───────────────────────────────────────────────────────────────

    private val _networks = MutableStateFlow<List<ServerNetwork>>(emptyList())
    val networks: StateFlow<List<ServerNetwork>> = _networks.asStateFlow()

    // Convenience alias used by the HomeScreen copy button label
    val ipAddress: StateFlow<String?> = _networks
        .map { list -> list.firstOrNull()?.ipAddress }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        checkSetup()
        pollNetworks()
    }

    // ── Setup flow ────────────────────────────────────────────────────────────

    /** Checks installation flags and kicks off [runInstallation] if needed. */
    fun checkSetup() {
        viewModelScope.launch {
            _setupState.value = SetupState.Checking
            val ready = withContext(Dispatchers.IO) { installer.isFullyInstalled() }
            if (ready) {
                _setupState.value = SetupState.Ready
            } else {
                runInstallation()
            }
        }
    }

    /**
     * Runs the full ProotInstaller.setup() pipeline.
     * Progress strings emitted by the installer are forwarded to the UI via [SetupState.Installing].
     */
    fun runInstallation() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    installer.setup { progress ->
                        _setupState.value = SetupState.Installing(progress)
                    }
                }
                _setupState.value = SetupState.Ready
            } catch (e: Exception) {
                _setupState.value = SetupState.Error(e.message ?: "Installation failed")
            }
        }
    }

    // ── Server control ────────────────────────────────────────────────────────

    /**
     * Sends START or STOP intent to [TerrariaForegroundService].
     * No-op while installation is not complete.
     */
    fun toggleServer() {
        if (_setupState.value !is SetupState.Ready) return

        val action = if (TerrariaForegroundService.isRunning.value) {
            TerrariaForegroundService.ACTION_STOP
        } else {
            TerrariaForegroundService.ACTION_START
        }

        val intent = Intent(context, TerrariaForegroundService::class.java).apply {
            this.action = action
        }

        // startForegroundService gives the service 5 s to call startForeground()
        if (action == TerrariaForegroundService.ACTION_START) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    /**
     * Copies "<best-IP>:7777" to the clipboard.
     * Uses the first (highest-priority) network from the sorted list.
     */
    fun copyConnectionAddress() {
        val ip = _networks.value.firstOrNull()?.ipAddress ?: return
        val address = "$ip:7777"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terraria Server Address", address))
    }

    // ── Network polling ───────────────────────────────────────────────────────

    private fun pollNetworks() {
        viewModelScope.launch {
            while (true) {
                _networks.value = withContext(Dispatchers.IO) {
                    networkDetector.getAvailableNetworks()
                }
                delay(5_000)
            }
        }
    }
}
