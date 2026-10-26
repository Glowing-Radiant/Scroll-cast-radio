package com.scrollcast.radio.data.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A folder in a GitHub repository; [ref] null means the default branch. */
data class GitHubFolder(val owner: String, val repo: String, val ref: String?, val path: String) {
    val title: String get() = listOf("$owner/$repo", path).filter { it.isNotEmpty() }.joinToString("/")

    fun parent(): GitHubFolder? = if (path.isEmpty()) null else copy(path = path.substringBeforeLast('/', ""))
}

/** A playlist file or sub-folder shown while browsing a repository. */
data class GitHubEntry(
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val downloadUrl: String?,
    /** What to call it: the file name, or e.g. "India" for in.m3u in a folder of country lists. */
    val title: String = name,
)

/**
 * Understands GitHub links: a repository or folder is browsed one folder at a time (big
 * playlist repositories hold tens of thousands of files, too many to list at once), and a file
 * page is turned into its raw download address.
 */
object GitHubLinks {
    sealed interface Target {
        data class Folder(val folder: GitHubFolder) : Target
        data class File(val rawUrl: String, val name: String) : Target
    }

    /** Null when [link] isn't a GitHub repository, folder or file page. */
    fun parse(link: String): Target? {
        val url = link.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        return when (url.host.lowercase()) {
            "github.com", "www.github.com" -> {
                if (segments.size < 2) return null
                val (owner, repo) = segments
                val repoName = repo.removeSuffix(".git")
                when (segments.getOrNull(2)) {
                    null -> Target.Folder(GitHubFolder(owner, repoName, null, ""))
                    "tree" -> if (segments.size < 4) null else Target.Folder(
                        GitHubFolder(owner, repoName, segments[3], segments.drop(4).joinToString("/")),
                    )
                    "blob", "raw" -> if (segments.size < 5) null else Target.File(
                        rawUrl(owner, repoName, segments[3], segments.drop(4).joinToString("/")),
                        sourceName(repoName, segments.last()),
                    )
                    else -> null
                }
            }
            "raw.githubusercontent.com" ->
                if (segments.size < 4) null else Target.File(link, sourceName(segments[1], segments.last()))
            else -> null
        }
    }

    fun rawUrl(owner: String, repo: String, ref: String, path: String): String =
        "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"

    /** "iptv · in" for streams/in.m3u in iptv-org/iptv. */
    fun sourceName(repo: String, fileName: String): String = "$repo · ${fileName.substringBeforeLast('.')}"

    fun isPlaylistFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS

    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls", "json")

    @Serializable
    private data class ContentDto(
        val name: String = "",
        val path: String = "",
        val type: String = "",
        val download_url: String? = null,
    )

    /** Lists [folder]'s sub-folders and playlist files, folders first. */
    suspend fun list(http: OkHttpClient, json: Json, folder: GitHubFolder): List<GitHubEntry> = withContext(Dispatchers.IO) {
        val url: HttpUrl = "https://api.github.com/repos/${folder.owner}/${folder.repo}/contents".toHttpUrl().newBuilder()
            .apply { folder.path.split('/').filter { it.isNotEmpty() }.forEach(::addPathSegment) }
            .apply { folder.ref?.let { addQueryParameter("ref", it) } }
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw IOException("GitHub couldn't find that repository or folder.")
                response.code == 403 || response.code == 429 ->
                    throw IOException("GitHub is limiting requests right now. Try again in a few minutes.")
                !response.isSuccessful -> throw IOException("GitHub answered with an error (${response.code}).")
            }
            response.body.string()
        }
        // A file path answers with one object rather than a list.
        if (json.parseToJsonElement(body) !is JsonArray) return@withContext emptyList()
        json.decodeFromString(ListSerializer(ContentDto.serializer()), body)
            .mapNotNull { item ->
                when {
                    item.name.startsWith(".") -> null
                    item.type == "dir" -> GitHubEntry(item.name, item.path, isFolder = true, downloadUrl = null)
                    item.type == "file" && isPlaylistFile(item.name) && item.download_url != null ->
                        GitHubEntry(item.name, item.path, isFolder = false, downloadUrl = item.download_url)
                    else -> null
                }
            }
            .let(::withCountryTitles)
            .sortedWith(compareBy({ !it.isFolder }, { it.title.lowercase() }))
    }

    private val COUNTRY_FILE = Regex("""^([a-z]{2})(?:_(.+))?\.[a-z0-9]+$""")

    /**
     * Folders like iptv-org's name each list by country code ("in.m3u", "at_pluto.m3u"). When
     * most files look like that, show country names; a lone "dj.m3u" elsewhere stays as it is.
     */
    fun withCountryTitles(entries: List<GitHubEntry>): List<GitHubEntry> {
        val files = entries.filter { !it.isFolder }
        val coded = files.mapNotNull { f -> COUNTRY_FILE.find(f.name.lowercase())?.let { f to it } }
        if (coded.size < 5 || coded.size * 2 < files.size) return entries
        val titles = coded.associate { (file, match) ->
            val country = Playlists.countryName(match.groupValues[1].uppercase())
            val extra = match.groupValues[2].replace('_', ' ')
            file.path to if (extra.isEmpty()) country else "$country ($extra)"
        }
        return entries.map { e -> titles[e.path]?.let { e.copy(title = it) } ?: e }
    }
}
