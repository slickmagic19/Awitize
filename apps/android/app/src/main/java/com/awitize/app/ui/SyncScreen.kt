package com.awitize.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.awitize.app.data.SyncOutcome
import com.awitize.app.data.SyncStats
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

sealed interface SyncPhase {
    data object Idle : SyncPhase
    data object Syncing : SyncPhase
    data class Done(val outcome: SyncOutcome) : SyncPhase
    data class Failed(val message: String) : SyncPhase
}

@Composable
fun SyncFlow(ui: UiRefs, onClose: () -> Unit) {
    var phase by remember { mutableStateOf<SyncPhase>(SyncPhase.Idle) }

    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) {
            phase = SyncPhase.Syncing
            ui.scope.launch {
                try {
                    val (merged, outcome) = ui.container.syncClient.run(
                        text,
                        ui.container.store.snapshot(),
                        ui.container.deviceName,
                    )
                    ui.container.store.apply(merged)
                    phase = SyncPhase.Done(outcome)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    phase = SyncPhase.Failed(e.message ?: "Sync failed")
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(AwBg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = AwText)
                }
                Text("Sync with PC", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }

            when (val p = phase) {
                SyncPhase.Idle -> IdleContent(onScan = {
                    launcher.launch(
                        ScanOptions()
                            .setOrientationLocked(false)
                            .setPrompt("Scan the QR code from Awitize on your PC"),
                    )
                })

                SyncPhase.Syncing -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = AwBrandSoft)
                    Spacer(Modifier.height(18.dp))
                    Text("Syncing…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Merging your PC and phone libraries. This only ever adds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AwMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                is SyncPhase.Done -> DoneContent(
                    outcome = p.outcome,
                    onDone = onClose,
                    onAgain = { phase = SyncPhase.Idle },
                )

                is SyncPhase.Failed -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = AwDanger, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Sync didn't finish", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        p.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AwMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Check that both devices are on the same Wi-Fi and that the PC modal is still open (codes expire after 15 minutes).",
                        style = MaterialTheme.typography.bodySmall,
                        color = AwMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { phase = SyncPhase.Idle },
                        colors = ButtonDefaults.buttonColors(containerColor = AwBrand),
                    ) { Text("Try again") }
                    TextButton(onClick = onClose) { Text("Close", color = AwMuted) }
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onScan: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(28.dp))
        Icon(Icons.Rounded.Sync, null, tint = AwBrandSoft, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text("Bring your library together", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "On your PC, open Awitize and click your avatar → \"Sync with phone\". Then scan the QR code with this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = AwMuted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Sync only adds — nothing on either device is ever deleted. Downloads stay on the device that made them.",
            style = MaterialTheme.typography.bodyMedium,
            color = AwMuted,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AwBrand, contentColor = AwText),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, null)
            Spacer(Modifier.size(10.dp))
            Text("Scan QR code", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Keep the PC's sync window open while you scan.",
            style = MaterialTheme.typography.labelMedium,
            color = AwMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DoneContent(outcome: SyncOutcome, onDone: () -> Unit, onAgain: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(28.dp))
        Text("Synced with ${outcome.pcName}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        StatsBlock("Added to this phone", outcome.received)
        Spacer(Modifier.height(12.dp))
        StatsBlock("Added to the PC", outcome.sent)
        Spacer(Modifier.weight(1f))
        Text(
            "Add-only, always: nothing was deleted on either side.",
            style = MaterialTheme.typography.labelMedium,
            color = AwMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AwBrand, contentColor = AwText),
        ) { Text("Done") }
        TextButton(onClick = onAgain) { Text("Sync again", color = AwMuted) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StatsBlock(label: String, stats: SyncStats) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AwSurfaceHi)
            .padding(16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (stats.total == 0) {
            Text("Nothing new — already in sync.", style = MaterialTheme.typography.bodySmall, color = AwMuted)
            return@Column
        }
        StatRow("liked songs", stats.likesAdded)
        StatRow("playlists", stats.playlistsAdded)
        StatRow("playlists updated", stats.playlistsMerged)
        StatRow("tracks", stats.tracksAdded)
        StatRow("listens", stats.playsAdded)
    }
}

@Composable
private fun StatRow(label: String, value: Int) {
    if (value <= 0) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("+$value $label", style = MaterialTheme.typography.bodyMedium, color = AwBrandSoft, modifier = Modifier.weight(1f))
    }
}
