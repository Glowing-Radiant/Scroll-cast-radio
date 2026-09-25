package com.scrollcast.radio.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Favorite stations, newest first, kept as a small JSON file in app storage. */
class FavoritesStore(
    context: Context,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "favorites.json")
    private val serializer = ListSerializer(Station.serializer())
    private val writeLock = Mutex()

    private val _stations = MutableStateFlow<List<Station>>(emptyList())
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
            if (saved != null) _stations.update { current -> (current + saved).distinctBy { it.uuid } }
        }
    }

    fun isFavorite(uuid: String): Boolean = _stations.value.any { it.uuid == uuid }

    /** Adds or removes [station]; returns true if it is now a favorite. */
    fun toggle(station: Station): Boolean {
        var added = false
        _stations.update { current ->
            if (current.any { it.uuid == station.uuid }) {
                current.filterNot { it.uuid == station.uuid }
            } else {
                added = true
                listOf(station) + current
            }
        }
        persist()
        return added
    }

    fun remove(uuid: String) {
        _stations.update { current -> current.filterNot { it.uuid == uuid } }
        persist()
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(serializer, _stations.value))
                tmp.renameTo(file)
            }
        }
    }
}
