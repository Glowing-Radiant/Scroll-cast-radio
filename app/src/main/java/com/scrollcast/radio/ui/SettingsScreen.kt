package com.scrollcast.radio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.Mix
import com.scrollcast.radio.data.RegionDetector
import java.text.NumberFormat

@Composable
fun SettingsScreen(vm: FeedViewModel, onClose: () -> Unit) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val detectedLanguage by vm.detectedLanguage.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf<OptionKind?>(null) }

    BackHandler(onBack = onClose)

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

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .semantics { paneTitle = "Settings" },
    ) {
        ScreenHeader(title = "Settings", onBack = onClose)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Text(
                "Changes apply when you leave this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeading("Region")
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
            Spacer(Modifier.height(32.dp))
        }
    }
}

private data class MixOption(val mix: Mix, val title: String, val description: String)

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp).semantics { heading() },
    )
}

/** A button that shows the current choice and opens the list of options. */
@Composable
private fun ChoiceButton(label: String, value: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Change $label", role = Role.Button, onClick = onClick),
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
private fun CenteredMessage(text: String, action: @Composable () -> Unit) {
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

private fun stationCount(count: Int): String =
    if (count == 1) "1 station" else "${NumberFormat.getIntegerInstance().format(count)} stations"

private fun String.capitalized(): String =
    split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
