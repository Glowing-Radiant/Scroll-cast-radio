package com.scrollcast.radio

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.scrollcast.radio.data.ShareLinks
import com.scrollcast.radio.ui.AnnouncementRegion
import com.scrollcast.radio.ui.FavoritesScreen
import com.scrollcast.radio.ui.FeedScreen
import com.scrollcast.radio.ui.FeedViewModel
import com.scrollcast.radio.ui.ScrollCastTheme
import com.scrollcast.radio.ui.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Feed("Feed", Icons.Filled.PlayArrow),
    Favorites("Favorites", Icons.Filled.Favorite),
}

class MainActivity : ComponentActivity() {

    private val vm: FeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (savedInstanceState == null) handleLink(intent)

        setContent {
            ScrollCastTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.Feed) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                  // A plain Box: Surface stretches its direct children to full screen, which would
                  // turn the 1dp announcer into an invisible layer covering every control.
                  Box(Modifier.fillMaxSize()) {
                    if (settingsOpen) {
                        SettingsScreen(vm, onClose = {
                            vm.commitSettings()
                            settingsOpen = false
                            tab = Tab.Feed
                        })
                    } else {
                        BackHandler(enabled = tab != Tab.Feed) { tab = Tab.Feed }
                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            bottomBar = {
                                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                    Tab.entries.forEach { item ->
                                        NavigationBarItem(
                                            selected = tab == item,
                                            onClick = { tab = item },
                                            icon = { Icon(item.icon, contentDescription = null) },
                                            label = { Text(item.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = MaterialTheme.colorScheme.primary,
                                            ),
                                        )
                                    }
                                }
                            },
                        ) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                when (tab) {
                                    Tab.Feed -> FeedScreen(vm, onOpenSettings = {
                                        vm.startEditingSettings()
                                        settingsOpen = true
                                    })
                                    Tab.Favorites -> FavoritesScreen(vm, onPlayed = { tab = Tab.Feed })
                                }
                            }
                        }
                    }
                    // Announcements keep working on every screen, not just the feed.
                    AnnouncementRegion(vm, Modifier.align(Alignment.BottomStart))
                  }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        ShareLinks.stationIdFrom(intent?.dataString)?.let(vm::openSharedStation)
    }
}
