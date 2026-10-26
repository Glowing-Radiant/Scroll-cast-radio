package com.scrollcast.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scrollcast.radio.data.AudioPrefs
import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.Mix
import com.scrollcast.radio.data.RegionDetector
import com.scrollcast.radio.update.UpdateState
import java.text.NumberFormat

/** The settings categories; each opens a page of its own so no page gets crowded. */
private enum class SettingsSection(val title: String) {
    Sources("Stations from"),
    Feed("Feed"),
    Audio("Audio"),
    App("App"),
}

@Composable
fun SettingsScreen(vm: FeedViewModel, onClose: () -> Unit) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val detectedLanguage by vm.detectedLanguage.collectAsStateWithLifecycle()
    val userSources by vm.userSources.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var picking by rememberSaveable { mutableStateOf<OptionKind?>(null) }
    var pickingEqualizer by rememberSaveable { mutableStateOf(false) }
    var addingSource by rememberSaveable { mutableStateOf(false) }
    val audio by vm.audio.collectAsStateWithLifecycle()
    val audioCaps by vm.audioCapabilities.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    BackHandler(onBack = onClose)

    if (pickingEqualizer) {
        ListPicker(
            title = "Choose equalizer",
            options = listOf(AudioPrefs.EQ_OFF to "Off", AudioPrefs.EQ_VOICE to "Voice clarity") +
                audioCaps.presets.map { it to it },
            selected = audio.equalizer,
            onPick = { vm.setEqualizer(it); pickingEqualizer = false },
            onClose = { pickingEqualizer = false },
        )
        return
    }

    if (addingSource) {
        AddSourceScreen(vm, onClose = { addingSource = false })
        return
    }

    picking?.let { kind ->
        OptionPicker(
            vm = vm,
            kind = kind,
            selectedKey = when (kind) {
                OptionKind.Region -> draft.countryCode
                OptionKind.Language -> draft.language
                OptionKind.Genre -> draft.genre
            },
            automaticLabel = when (kind) {
                OptionKind.Region -> "Automatic (${vm.detectedCountry?.let(RegionDetector::countryName) ?: "not detected"})"
                OptionKind.Language -> "Automatic (${detectedLanguage?.capitalized() ?: "phone language"})"
                OptionKind.Genre -> "Any genre"
            },
            onPick = { key ->
                vm.editDraft { d ->
                    when (kind) {
                        // Choosing a specific place or language implies wanting it in the feed.
                        OptionKind.Region -> d.copy(
                            countryCode = key,
                            regionMix = if (key != null && d.regionMix == Mix.Mixed) Mix.Bias else d.regionMix,
                        )
                        OptionKind.Language -> d.copy(
                            language = key,
                            languageMix = if (key != null && d.languageMix == Mix.Mixed) Mix.Bias else d.languageMix,
                        )
                        OptionKind.Genre -> d.copy(genre = key)
                    }
                }
                picking = null
            },
            onClose = { picking = null },
        )
        return
    }

    val regionName = (draft.countryCode ?: vm.detectedCountry)?.let(RegionDetector::countryName)
    val languageName = (draft.language ?: detectedLanguage)?.capitalized()
    val hasAudio = audioCaps.evenLoudness || audioCaps.bassBoost || audioCaps.equalizer
    val equalizerName = when (audio.equalizer) {
        AudioPrefs.EQ_OFF -> "Off"
        AudioPrefs.EQ_VOICE -> "Voice clarity"
        else -> audio.equalizer
    }

    when (section) {
        SettingsSection.Sources -> SettingsPage(SettingsSection.Sources.title, onBack = { section = null }) {
            SourcesSection(vm, draft.sources, userSources, onAdd = { vm.resetAddSource(); addingSource = true })
        }

        SettingsSection.Feed -> SettingsPage(SettingsSection.Feed.title, onBack = { section = null }) {
            SectionHeading("Region", first = true)
            ChoiceButton(
                label = "Region",
                value = if (draft.countryCode == null) "Automatic · ${regionName ?: "not detected"}" else regionName.orEmpty(),
                onClick = { picking = OptionKind.Region },
            )
            val place = regionName ?: "your region"
            MixGroup(
                selected = draft.regionMix,
                options = listOf(
                    MixOption(Mix.Only, "Only $place", "Every station is from $place."),
                    MixOption(Mix.Bias, "Mostly $place", "Most stations from $place, some from around the world."),
                    MixOption(Mix.Mixed, "Worldwide mix", "Stations from anywhere."),
                ),
                onSelect = { mix -> vm.editDraft { it.copy(regionMix = mix) } },
            )

            SectionHeading("Language")
            ChoiceButton(
                label = "Language",
                value = if (draft.language == null) "Automatic · ${languageName ?: "phone language"}" else languageName.orEmpty(),
                onClick = { picking = OptionKind.Language },
            )
            val tongue = languageName ?: "your language"
            MixGroup(
                selected = draft.languageMix,
                options = listOf(
                    MixOption(Mix.Only, "Only $tongue", "Every station is in $tongue."),
                    MixOption(Mix.Bias, "Mostly $tongue", "Most stations in $tongue, some in other languages."),
                    MixOption(Mix.Mixed, "Any language", "Language doesn't matter."),
                ),
                onSelect = { mix -> vm.editDraft { it.copy(languageMix = mix) } },
            )

            SectionHeading("Genre")
            ChoiceButton(
                label = "Genre",
                value = draft.genre?.capitalized() ?: "Any genre",
                onClick = { picking = OptionKind.Genre },
            )
        }

        SettingsSection.Audio -> SettingsPage(SettingsSection.Audio.title, onBack = { section = null }) {
            Text(
                "Audio changes apply right away.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (audioCaps.evenLoudness) {
                SwitchRow(
                    title = "Even out loudness",
                    description = "Keeps every station at a similar volume.",
                    checked = audio.evenLoudness,
                    onChange = vm::setEvenLoudness,
                )
            }
            if (audioCaps.bassBoost) {
                SwitchRow(
                    title = "Bass boost",
                    description = "Fuller low end, best with headphones.",
                    checked = audio.bassBoost,
                    onChange = vm::setBassBoost,
                )
            }
            if (audioCaps.equalizer) {
                Spacer(Modifier.height(8.dp))
                ChoiceButton(label = "Equalizer", value = equalizerName, onClick = { pickingEqualizer = true })
            }
        }

        SettingsSection.App -> SettingsPage(SettingsSection.App.title, onBack = { section = null }) {
            UpdateSection(vm)
        }

        null -> SettingsPage("Settings", onBack = onClose) {
            Text(
                "Feed changes apply when you leave Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Sources first: they decide which regions, languages and genres there are to pick.
                ChoiceButton(
                    label = SettingsSection.Sources.title,
                    value = vm.sourceNames(draft.sources),
                    clickLabel = "Open",
                    onClick = { section = SettingsSection.Sources },
                )
                ChoiceButton(
                    label = SettingsSection.Feed.title,
                    value = listOf(
                        when (draft.regionMix) {
                            Mix.Only -> "Only ${regionName ?: "your region"}"
                            Mix.Bias -> "Mostly ${regionName ?: "your region"}"
                            Mix.Mixed -> "Worldwide"
                        },
                        when (draft.languageMix) {
                            Mix.Only -> "only ${languageName ?: "your language"}"
                            Mix.Bias -> "mostly ${languageName ?: "your language"}"
                            Mix.Mixed -> "any language"
                        },
                        draft.genre?.capitalized() ?: "any genre",
                    ).joinToString(" · "),
                    clickLabel = "Open",
                    onClick = { section = SettingsSection.Feed },
                )
                if (hasAudio) {
                    val on = listOfNotNull(
                        "Even loudness".takeIf { audioCaps.evenLoudness && audio.evenLoudness },
                        "Bass boost".takeIf { audioCaps.bassBoost && audio.bassBoost },
                        "Equalizer: $equalizerName".takeIf { audioCaps.equalizer && audio.equalizer != AudioPrefs.EQ_OFF },
                    )
                    ChoiceButton(
                        label = SettingsSection.Audio.title,
                        value = on.joinToString(" · ").ifEmpty { "All off" },
                        clickLabel = "Open",
                        onClick = { section = SettingsSection.Audio },
                    )
                }
                ChoiceButton(
                    label = SettingsSection.App.title,
                    value = when (val u = update) {
                        is UpdateState.Available -> "Version ${vm.versionName} · update to ${u.info.version} available"
                        else -> "Version ${vm.versionName} · updates"
                    },
                    clickLabel = "Open",
                    onClick = { section = SettingsSection.App },
                )
            }
        }
    }
}

/** A full-screen settings page with a back button and scrolling content. */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().semantics { paneTitle = title },
    ) {
        ScreenHeader(title = title, onBack = onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** An on/off setting: the whole row is one switch for TalkBack. */
@Composable
internal fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Full-screen single-choice list for fixed options. */
@Composable
private fun ListPicker(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().semantics { paneTitle = title }) {
        ScreenHeader(title = title, onBack = onClose)
        LazyColumn(Modifier.fillMaxSize().selectableGroup()) {
            items(options.size) { i ->
                val (key, label) = options[i]
                OptionRow(title = label, subtitle = null, selected = key == selected, onClick = { onPick(key) })
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

private data class MixOption(val mix: Mix, val title: String, val description: String)

@Composable
internal fun SectionHeading(text: String, first: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (first) 8.dp else 28.dp, bottom = 12.dp).semantics { heading() },
    )
}

/** A button that shows the current choice and opens the list of options (or a settings page). */
@Composable
internal fun ChoiceButton(label: String, value: String, onClick: () -> Unit, clickLabel: String = "Change $label") {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun MixGroup(selected: Mix, options: List<MixOption>, onSelect: (Mix) -> Unit) {
    Column(Modifier.padding(top = 8.dp).selectableGroup()) {
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = option.mix == selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.mix) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option.mix == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(option.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        option.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Full-screen list of the options that actually exist, with how many stations each has. */
@Composable
private fun OptionPicker(
    vm: FeedViewModel,
    kind: OptionKind,
    selectedKey: String?,
    automaticLabel: String,
    onPick: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val allOptions by vm.options.collectAsStateWithLifecycle()
    val state = allOptions[kind]
    LaunchedEffect(kind) { vm.loadOptions(kind) }
    BackHandler(onBack = onClose)

    val title = when (kind) {
        OptionKind.Region -> "Choose region"
        OptionKind.Language -> "Choose language"
        OptionKind.Genre -> "Choose genre"
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().semantics { paneTitle = title },
    ) {
        ScreenHeader(title = title, onBack = onClose)
        when (state) {
            is OptionsState.Loaded -> {
                val rows: List<CatalogEntry?> = listOf(null) + state.entries
                val selectedIndex = rows.indexOfFirst { it?.key == selectedKey }.coerceAtLeast(0)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
                LazyColumn(Modifier.fillMaxSize().selectableGroup(), state = listState) {
                    itemsIndexed(rows, key = { _, entry -> entry?.key ?: "" }) { index, entry ->
                        OptionRow(
                            title = entry?.let { if (kind == OptionKind.Region) it.name else it.displayName } ?: automaticLabel,
                            subtitle = entry?.let { stationCount(it.stationCount) },
                            selected = index == selectedIndex,
                            onClick = { onPick(entry?.key) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
            OptionsState.Failed -> CenteredMessage("Couldn't load the list. Check your connection.") {
                Button(onClick = { vm.retryOptions(kind) }) { Text("Try again") }
            }
            else -> CenteredMessage("Loading options…") { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun CenteredMessage(text: String, action: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            action()
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

internal fun stationCount(count: Int): String =
    if (count == 1) "1 station" else "${NumberFormat.getIntegerInstance().format(count)} stations"

private fun String.capitalized(): String =
    split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
