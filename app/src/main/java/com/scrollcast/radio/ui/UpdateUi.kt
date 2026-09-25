package com.scrollcast.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scrollcast.radio.update.ReleaseNotes
import com.scrollcast.radio.update.UpdateInfo
import com.scrollcast.radio.update.UpdateState

/**
 * Full-screen updater: what's new in every version since the installed one, then Update /
 * Later. Playback is held silent while this is showing (see [FeedViewModel.updateScreenVisible]).
 */
@Composable
fun UpdateScreen(vm: FeedViewModel) {
    val visible by vm.updateScreenVisible.collectAsStateWithLifecycle()
    val state by vm.update.collectAsStateWithLifecycle()
    if (!visible) return

    val info: UpdateInfo = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    } ?: return
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing

    BackHandler(enabled = !busy, onBack = vm::dismissUpdate)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .semantics { paneTitle = "Update available" },
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Update to ${info.version}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "You have version ${vm.versionName}.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "What's new",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            if (info.changes.all { it.notes.isBlank() }) {
                Text("Improvements and fixes.", style = MaterialTheme.typography.bodyLarge)
            }
            info.changes.filter { it.notes.isNotBlank() }.forEach { ChangelogSection(it, showVersion = info.changes.size > 1) }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                is UpdateState.Downloading -> {
                    Text(
                        "Downloading… ${s.percent}%",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                UpdateState.Installing -> Text(
                    "Follow Android's prompt to install.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                else -> {
                    if (s is UpdateState.Failed) {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Button(
                        onClick = { vm.installUpdate(info) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text(if (s is UpdateState.Failed) "Try again" else "Update now") }
                    OutlinedButton(
                        onClick = vm::dismissUpdate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Later") }
                }
            }
        }
    }
}

/** Renders one version's notes: "## headings", "- bullets" and plain lines. */
@Composable
private fun ChangelogSection(release: ReleaseNotes, showVersion: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showVersion) {
            Text(
                "Version ${release.version}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
        }
        Changelog.lines(release.notes).forEach { line ->
            when (line) {
                is Changelog.Line.Heading -> Text(
                    line.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
                is Changelog.Line.Bullet -> Row(
                    // Read as one item, without the bullet character.
                    Modifier.clearAndSetSemantics { contentDescription = line.text },
                ) {
                    Text("•  ", style = MaterialTheme.typography.bodyLarge)
                    Text(line.text, style = MaterialTheme.typography.bodyLarge)
                }
                is Changelog.Line.Paragraph -> Text(line.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Minimal Markdown reading for release notes. */
object Changelog {
    sealed interface Line {
        val text: String
        data class Heading(override val text: String) : Line
        data class Bullet(override val text: String) : Line
        data class Paragraph(override val text: String) : Line
    }

    fun lines(markdown: String): List<Line> = markdown.lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> null
            line.startsWith("#") -> Line.Heading(clean(line.trimStart('#')))
            line.startsWith("- ") || line.startsWith("* ") -> Line.Bullet(clean(line.drop(2)))
            else -> Line.Paragraph(clean(line))
        }
    }.filter { it.text.isNotEmpty() }

    /** Drops Markdown markup: links keep their text, emphasis and code marks go. */
    private fun clean(text: String): String = text
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""(\*\*|__|`)"""), "")
        .trim()
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
        OutlinedButton(
            onClick = vm::checkForUpdates,
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state is UpdateState.Available) "Show update" else "Check for updates") }
    }
}
