package com.scrollcast.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scrollcast.radio.BuildConfig
import com.scrollcast.radio.data.ShareLinks
import com.scrollcast.radio.data.providers.Playlists
import com.scrollcast.radio.data.providers.UserSource

/** Settings → Stations from: the built-in directories, then the user's own sources. */
@Composable
internal fun SourcesSection(vm: FeedViewModel, enabled: Set<String>, userSources: List<UserSource>, onAdd: () -> Unit) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Text(
        "Turn on more than one to mix their stations into one feed.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SectionHeading("Directories")
    vm.builtInProviders.forEach { provider ->
        SwitchRow(
            title = provider.name,
            description = provider.description,
            checked = provider.id in enabled,
            onChange = { on -> vm.setSource(provider.id, provider.name, on) },
        )
    }

    SectionHeading("Your sources")
    if (userSources.isEmpty()) {
        Text(
            "Add a station list from a link, like an M3U playlist or a GitHub repository.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    userSources.forEach { source ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwitchRow(
                title = source.name,
                description = source.label,
                checked = source.id in enabled,
                onChange = { on -> vm.setSource(source.id, source.name, on) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { shareText(context, vm.sourceShareText(source), "Share station list") },
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Share ${source.name}")
            }
            IconButton(onClick = { removing = source.id }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${source.name}")
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text("Add a source", modifier = Modifier.padding(start = 8.dp))
    }

    userSources.firstOrNull { it.id == removing }?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${source.name}?") },
            text = { Text("Its stations leave the feed. Favorites from it stay.") },
            confirmButton = {
                Button(onClick = { removing = null; vm.removeUserSource(source) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Adds a source from a link. A playlist link is read straight away; a GitHub repository or
 * folder opens as a list to browse, one folder at a time, and picking a playlist file adds it.
 */
@Composable
internal fun AddSourceScreen(vm: FeedViewModel, onClose: () -> Unit) {
    val state by vm.addSource.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is AddSourceState.Added) onClose()
    }

    val browsing = state as? AddSourceState.Browsing
    if (browsing != null) {
        val up = browsing.folder.parent()
        val back = { if (up != null) vm.openSourceFolder(up) else vm.resetAddSource() }
        BackHandler(onBack = back)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .semantics { paneTitle = browsing.folder.title },
        ) {
            ScreenHeader(title = browsing.folder.title, onBack = back)
            if (browsing.entries.isEmpty()) {
                CenteredMessage("No playlists or folders here.") {}
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(browsing.entries, key = { it.path }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(
                                onClickLabel = if (entry.isFolder) "Open folder" else "Add as a source",
                                role = Role.Button,
                            ) { vm.pickSourceEntry(browsing.folder, entry) }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    entry.isFolder -> "Folder"
                                    entry.title != entry.name -> "Playlist · ${entry.name}"
                                    else -> "Playlist"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (entry.isFolder) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
        return
    }

    val working = state as? AddSourceState.Working
    SettingsPage("Add a source", onBack = onClose) {
        Text(
            "Paste a link to a station list: M3U or M3U8 playlists, PLS or JSON files, a single " +
                "stream, or a GitHub repository to browse. TV channels play their sound only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Link") },
            placeholder = { Text("https://github.com/iptv-org/iptv") },
            singleLine = true,
            enabled = working == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.submitSourceLink(link) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.submitSourceLink(link) },
            enabled = link.isNotBlank() && working == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Add") }
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            is AddSourceState.Working -> Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(s.message, style = MaterialTheme.typography.bodyLarge)
            }
            is AddSourceState.Failed -> Text(
                s.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            else -> Unit
        }
        // Shortcuts for trying things out; release builds leave the choice of lists to the user.
        if (BuildConfig.DEBUG) {
            SectionHeading("Examples")
            EXAMPLES.forEach { (title, url) ->
                OutlinedButton(
                    onClick = { link = url; vm.submitSourceLink(url) },
                    enabled = working == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp),
                ) { Text(title) }
            }
        }
    }
}

/** Public lists for testing in dev builds. */
private val EXAMPLES = listOf(
    "TV channels by country (iptv-org)" to "https://github.com/iptv-org/iptv/tree/master/streams",
    "Radio playlists by genre (junguler)" to "https://github.com/junguler/m3u-radio-music-playlists",
)

/** Asks before adding a station list someone shared by link. */
@Composable
internal fun SourceOfferDialog(offer: ShareLinks.Target.SourceLink, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val name = offer.name ?: Playlists.nameFromUrl(offer.url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $name?") },
        text = {
            Text("Someone shared this station list with you. Its stations will join your feed.\n\n${offer.url}")
        },
        confirmButton = { Button(onClick = onAdd) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
