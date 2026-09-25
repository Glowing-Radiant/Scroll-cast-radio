package com.scrollcast.radio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The Favorites tab. Choosing a station plays it and returns to the feed. */
@Composable
fun FavoritesScreen(vm: FeedViewModel, onPlayed: () -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val playingId = player.current?.uuid?.takeIf { player.isPlaying || player.isBuffering }

    Column(Modifier.fillMaxSize().semantics { paneTitle = "Favorites" }) {
        Text(
            "Favorites",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp).semantics { heading() },
        )
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No favorites yet. Tap the heart on a station to keep it here.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(favorites, key = { it.uuid }) { station ->
                val nowPlaying = station.uuid == playingId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable(onClickLabel = "Play") {
                            vm.play(station)
                            onPlayed()
                        }
                        .semantics {
                            if (nowPlaying) stateDescription = "Now playing"
                            customActions = listOf(
                                CustomAccessibilityAction("Remove from favorites") { vm.removeFavorite(station); true },
                            )
                        }
                        .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            station.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (nowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = listOfNotNull(
                            "Now playing".takeIf { nowPlaying },
                            station.placeLine.ifEmpty { null },
                        ).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { vm.removeFavorite(station) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${station.displayName} from favorites")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
        )
    }
}
