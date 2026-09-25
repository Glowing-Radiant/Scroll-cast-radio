package com.scrollcast.radio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scrollcast.radio.update.UpdateState

/** Offers a newer GitHub release and shows download progress. Shown over any screen. */
@Composable
fun UpdateDialog(vm: FeedViewModel) {
    val state by vm.update.collectAsStateWithLifecycle()
    val dismissed by vm.updatePromptDismissed.collectAsStateWithLifecycle()

    when (val s = state) {
        is UpdateState.Available -> if (!dismissed) {
            AlertDialog(
                onDismissRequest = vm::dismissUpdate,
                title = { Text("Update available") },
                text = {
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Version ${s.info.version} is ready. You have ${vm.versionName}.")
                        if (s.info.notes.isNotBlank()) {
                            Text(s.info.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = { Button(onClick = { vm.installUpdate(s.info) }) { Text("Update") } },
                dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Later") } },
            )
        }
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${s.percent}%")
                }
            },
            confirmButton = {},
        )
        else -> Unit
    }
}

/** "App" section of Settings: version, manual update check and its result. */
@Composable
fun UpdateSection(vm: FeedViewModel) {
    val state by vm.update.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Version ${vm.versionName}", style = MaterialTheme.typography.titleMedium)
        if (!vm.updatesEnabled) {
            Text(
                "Development build. Updates come from the computer, not GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val status = when (val s = state) {
            UpdateState.Checking -> "Checking for updates…"
            UpdateState.UpToDate -> "You have the latest version."
            is UpdateState.Available -> "Version ${s.info.version} is available."
            is UpdateState.Downloading -> "Downloading update… ${s.percent}%"
            UpdateState.Installing -> "Installing update…"
            is UpdateState.Failed -> s.message
            UpdateState.Idle -> null
        }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val available = state as? UpdateState.Available
        if (available != null) {
            Button(onClick = { vm.installUpdate(available.info) }, modifier = Modifier.fillMaxWidth()) {
                Text("Update to ${available.info.version}")
            }
        } else {
            OutlinedButton(
                onClick = vm::checkForUpdates,
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check for updates") }
        }
    }
}
