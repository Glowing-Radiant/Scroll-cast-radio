package com.scrollcast.radio.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scrollcast.radio.data.Station

@Composable
fun FeedScreen(
    vm: FeedViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by vm.player.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val loading by vm.feedLoading.collectAsStateWithLifecycle()
    val error by vm.feedError.collectAsStateWithLifecycle()
    val mood by vm.mood.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shareTarget by remember { mutableStateOf<Station?>(null) }
    var moodDialogOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().semantics { paneTitle = "Station feed" }) {
        if (state.stations.isEmpty()) {
            EmptyFeed(
                loading = loading,
                error = error,
                onRetry = vm::retry,
                onClearMood = if (mood != null) ({ vm.setMood(null) }) else null,
            )
        } else {
            val pagerState = rememberPagerState(initialPage = state.currentIndex) { state.stations.size }

            // Swipes drive the player...
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collect(vm::onPageSettled)
            }
            // ...and the player (headset buttons, auto-skip, favorites) drives the pager.
            LaunchedEffect(state.currentIndex, state.stations.size) {
                if (pagerState.currentPage != state.currentIndex && !pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage(state.currentIndex)
                }
            }

            VerticalPager(
                state = pagerState,
                key = { page -> state.stations.getOrNull(page)?.uuid ?: page },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val station = state.stations[page]
                val isCurrent = page == state.currentIndex
                StationPage(
                    station = station,
                    status = when {
                        !isCurrent -> PlayStatus.Paused
                        state.hasError -> PlayStatus.Failed
                        state.isBuffering -> PlayStatus.Loading
                        state.isPlaying -> PlayStatus.Playing
                        else -> PlayStatus.Paused
                    },
                    isFavorite = station.uuid in favoriteIds,
                    onTogglePlay = vm::togglePlay,
                    onNext = vm::next,
                    onPrevious = vm::previous,
                    onToggleFavorite = { vm.toggleFavorite(station) },
                    onShare = { shareTarget = station },
                    onShareApp = { shareText(context, vm.appShareText(station)) },
                    onShareDirect = { shareText(context, vm.directShareText(station)) },
                )
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { moodDialogOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        mood?.let { "Mood: $it" } ?: "Tell us your mood",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }

    if (moodDialogOpen) {
        MoodDialog(
            current = mood,
            onPlay = { moodDialogOpen = false; vm.setMood(it) },
            onClear = { moodDialogOpen = false; vm.setMood(null) },
            onDismiss = { moodDialogOpen = false },
        )
    }

    shareTarget?.let { station ->
        ShareDialog(
            station = station,
            onDismiss = { shareTarget = null },
            onShareApp = { shareTarget = null; shareText(context, vm.appShareText(station)) },
            onShareDirect = { shareTarget = null; shareText(context, vm.directShareText(station)) },
        )
    }
}

enum class PlayStatus(val spoken: String) {
    Playing("Playing"), Paused("Paused"), Loading("Loading"), Failed("Not responding")
}

@Composable
private fun StationPage(
    station: Station,
    status: PlayStatus,
    isFavorite: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onShareApp: () -> Unit,
    onShareDirect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))

        // The station itself: one TalkBack stop that reads the station, its state and offers
        // every action, so the feed is usable without hunting for buttons.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (status == PlayStatus.Playing) "Pause" else "Play",
                    role = Role.Button,
                    onClick = onTogglePlay,
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = status.spoken
                    customActions = listOf(
                        CustomAccessibilityAction("Next station") { onNext(); true },
                        CustomAccessibilityAction("Previous station") { onPrevious(); true },
                        CustomAccessibilityAction(if (isFavorite) "Remove from favorites" else "Add to favorites") {
                            onToggleFavorite(); true
                        },
                        CustomAccessibilityAction("Share app link") { onShareApp(); true },
                        CustomAccessibilityAction("Share stream link") { onShareDirect(); true },
                    )
                },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                station.displayName,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (station.placeLine.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(station.placeLine, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            }
            if (station.genreLine.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    station.genreLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    PlayStatus.Loading -> CircularProgressIndicator(color = colors.primary)
                    PlayStatus.Playing -> Icon(PauseIcon, null, Modifier.size(64.dp), tint = colors.onBackground)
                    PlayStatus.Paused -> Icon(Icons.Filled.PlayArrow, null, Modifier.size(64.dp), tint = colors.onBackground)
                    PlayStatus.Failed -> Text("Not responding", color = colors.error, textAlign = TextAlign.Center)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) colors.primary else colors.onBackground,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** Free-text mood: a language, a country, a genre or any mix, e.g. "romantic hindi". */
@Composable
private fun MoodDialog(
    current: String?,
    onPlay: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = { if (text.isNotBlank()) onPlay(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tell us your mood") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Mood") },
                    supportingText = { Text("For example: hindi, romantic, hip hop") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (current != null) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear mood")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = text.isNotBlank()) { Text("Play") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyFeed(loading: Boolean, error: String?, onRetry: () -> Unit, onClearMood: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null && !loading) {
            Text(
                error,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Try again") }
            if (onClearMood != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onClearMood) { Text("Clear mood") }
            }
        } else {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(
                "Tuning in…",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun ShareDialog(
    station: Station,
    onDismiss: () -> Unit,
    onShareApp: () -> Unit,
    onShareDirect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share ${station.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onShareApp, modifier = Modifier.fillMaxWidth()) {
                    Text("App link")
                }
                Text(
                    "Opens this station in Scroll Cast Radio, or plays it in the browser.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onShareDirect, modifier = Modifier.fillMaxWidth()) {
                    Text("Stream link")
                }
                Text(
                    "The station's direct stream address, for any player.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Invisible live region: whenever the view model announces something (a new station, a
 * favorite toggled, a skipped dead stream) TalkBack speaks it without moving focus.
 */
@Composable
fun AnnouncementRegion(vm: FeedViewModel, modifier: Modifier = Modifier) {
    val announcement by vm.announcement.collectAsStateWithLifecycle()
    // Alternate a zero-width space so identical consecutive messages still count as a change.
    val text = announcement.text + if (announcement.id % 2 == 0L) "" else "​"
    Box(
        modifier
            .requiredSize(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            }
    )
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share station"))
}
