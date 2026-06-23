package com.notyet.terraria.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notyet.terraria.core.network.ServerNetwork
import com.notyet.terraria.core.server.ServerState
import com.notyet.terraria.ui.MainViewModel

private val ColorGreen = Color(0xFF4CAF50)
private val ColorAmber = Color(0xFFFFC107)

@Composable
fun HomeScreen(
    serverState: ServerState,
    networks: List<ServerNetwork>,
    port: Int,
    setupState: MainViewModel.SetupState,
    onToggleServer: () -> Unit,
    onCopyAddress: () -> Unit,
    onNavigateToLogs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {

        // ── Title ─────────────────────────────────────────────────────────────

        Text(
            text = "Notyet Terraria Host",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            text = "v0.1",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp)
        )

        // ── Setup banner ──────────────────────────────────────────────────────

        when (setupState) {
            is MainViewModel.SetupState.Checking ->
                SetupBanner(text = "Checking installation…")

            is MainViewModel.SetupState.Installing ->
                SetupBanner(text = setupState.progress)

            is MainViewModel.SetupState.Error ->
                SetupBanner(text = "⚠ ${setupState.message}", isError = true)

            else -> { /* Ready — no banner */ }
        }

        // ── Addresses card ────────────────────────────────────────────────────

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = "Connection Addresses",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                if (networks.isEmpty()) {
                    Text(
                        text = "No active network interfaces",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                } else {
                    networks.forEachIndexed { index, network ->
                        NetworkRow(network = network, port = port)
                        if (index < networks.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onCopyAddress,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = networks.isNotEmpty()
                ) {
                    val label = networks.firstOrNull()
                        ?.let { "${it.ipAddress}:$port" }
                        ?: "—:$port"
                    Text(text = "Copy  $label")
                }
            }
        }

        // ── Status row ────────────────────────────────────────────────────────

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Port",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$port",
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Status",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = serverState.name,
                        fontWeight = FontWeight.Bold,
                        color = when (serverState) {
                            ServerState.RUNNING  -> ColorGreen
                            ServerState.STARTING -> ColorAmber
                            ServerState.ERROR    -> MaterialTheme.colorScheme.error
                            ServerState.STOPPED  -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // ── Start / Stop ──────────────────────────────────────────────────────

        Button(
            onClick = onToggleServer,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = setupState is MainViewModel.SetupState.Ready,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (serverState == ServerState.RUNNING) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        ) {
            Text(
                text = when (serverState) {
                    ServerState.RUNNING  -> "STOP SERVER"
                    ServerState.STARTING -> "STARTING…"
                    ServerState.ERROR    -> "RETRY"
                    ServerState.STOPPED  -> "START SERVER"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Logs ──────────────────────────────────────────────────────────────

        OutlinedButton(
            onClick = onNavigateToLogs,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(text = "View Logs")
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun NetworkRow(network: ServerNetwork, port: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,   // NOT justifyContent
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = network.type.name,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = network.interfaceName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "${network.ipAddress}:$port",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SetupBanner(text: String, isError: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            fontSize = 13.sp,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
        )
    }
}
