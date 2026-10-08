package com.dumuzeyn.mp3player

import android.os.Bundle
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.concurrent.Executors

/** Media3 browse tree shared by Android Auto and other external media browsers. */
@UnstableApi
internal class VoltuneMediaLibraryCallback(
    private val database: LibraryDatabase,
    private val mapper: MediaItemMapper,
    private val commands: CommandDelegate,
    private val controllerAccess: Media3ControllerAccess,
    private val artworkAuthority: String,
) : MediaLibrarySession.Callback, AutoCloseable {

    interface CommandDelegate {
        fun handle(
            controller: MediaSession.ControllerInfo,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult>

        fun onCommand(action: String)
        fun preview(controller: MediaSession.ControllerInfo, args: Bundle): SessionResult =
            SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        fun beforePlayerCommand(command: Int) = Unit
        fun disconnected(controller: MediaSession.ControllerInfo) = Unit
    }

    private val smartResolver = SmartPlaylistResolver()
    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "media-library").apply { isDaemon = true }
        },
    )

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(controllerAccess.sessionCommands(controller))
            .setAvailablePlayerCommands(controllerAccess.playerCommands(controller))
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = handleCustomCommand(controller, customCommand, args)

    fun handleCustomCommand(
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val action = customCommand.customAction
        if (action !in Media3Commands.internalActions) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        if (!controllerAccess.canUseInternalCommand(controller, action)) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        }
        if (action == Media3Commands.EDITOR_PREVIEW) {
            return Futures.immediateFuture(commands.preview(controller, args))
        }
        commands.onCommand(action)
        return commands.handle(controller, customCommand, args)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = resolveMediaItems(controller, mediaItems)

    fun resolveMediaItems(
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = executor.submit<List<MediaItem>> {
        val tracks = database.loadTracks()
        mediaItems.mapNotNullTo(ArrayList()) { item ->
            if (controllerAccess.isOwn(controller) && item.localConfiguration != null) {
                item
            } else {
                val track = tracks.firstOrNull { MediaItemMapper.matchesMediaId(it, item.mediaId) }
                    ?: item.requestMetadata.searchQuery
                        ?.let(Track::normalizeSearchText)
                        ?.takeIf(String::isNotEmpty)
                        ?.let { query -> tracks.firstOrNull { query in it.normalizedSearchText } }
                track?.let(mapper::toMediaItem)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo,
        playerCommand: Int): Int {
        commands.beforePlayerCommand(playerCommand)
        return SessionResult.RESULT_SUCCESS
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        commands.disconnected(controller)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = browseRoot(params)

    fun browseRoot(params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
        LibraryResult.ofItem(folder(ROOT, "Voltune", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params),
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return browseChildren(parentId, page, pageSize, params)
    }

    fun browseChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (page < 0 || pageSize < 1) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params))
        }
        return executor.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val children = children(parentId)
            if (children == null) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            } else {
                LibraryResult.ofItemList(page(children, page, pageSize), params)
            }
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = browseItem(mediaId)

    fun browseItem(mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        executor.submit<LibraryResult<MediaItem>> {
        findItem(mediaId)?.let { LibraryResult.ofItem(it, null) }
            ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = executor.submit<LibraryResult<Void>> {
        val count = search(query).size
        session.notifySearchResultChanged(browser, query, count, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return browseSearchResult(query, page, pageSize, params)
    }

    fun browseSearchResult(
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (page < 0 || pageSize < 1) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params))
        }
        return executor.submit<LibraryResult<ImmutableList<MediaItem>>> {
            LibraryResult.ofItemList(page(search(query), page, pageSize), params)
        }
    }

    override fun close() {
        executor.shutdownNow()
        database.close()
    }

    private fun children(parentId: String): List<MediaItem>? {
        if (parentId == ROOT) {
            return listOf(
                folder(SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(SMART, "Smart playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            )
        }
        val tracks = database.loadTracks()
        return when {
            parentId == SONGS -> mediaItems(tracks)
            parentId == ARTISTS -> folders(
                uniqueValues(tracks, artist = true),
                ARTIST_PREFIX,
                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            )
            parentId == ALBUMS -> folders(
                uniqueValues(tracks, artist = false),
                ALBUM_PREFIX,
                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            )
            parentId == PLAYLISTS -> database.loadPlaylists().mapTo(ArrayList()) { playlist ->
                folder(
                    PLAYLIST_PREFIX + encode(playlist.name),
                    playlist.name,
                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                )
            }
            parentId == SMART -> SmartPlaylistDefinition.entries.mapTo(ArrayList()) { definition ->
                folder(
                    SMART_PREFIX + definition.name,
                    definition.englishName,
                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                )
            }
            parentId.startsWith(ARTIST_PREFIX) -> mediaItems(
                filter(tracks, decode(parentId.removePrefix(ARTIST_PREFIX)), artist = true),
            )
            parentId.startsWith(ALBUM_PREFIX) -> mediaItems(
                filter(tracks, decode(parentId.removePrefix(ALBUM_PREFIX)), artist = false),
            )
            parentId.startsWith(PLAYLIST_PREFIX) -> playlistItems(
                tracks,
                decode(parentId.removePrefix(PLAYLIST_PREFIX)),
            )
            parentId.startsWith(SMART_PREFIX) -> smartItems(
                tracks,
                parentId.removePrefix(SMART_PREFIX),
            )
            else -> null
        }
    }

    private fun findItem(mediaId: String): MediaItem? {
        if (mediaId == ROOT) {
            return folder(ROOT, "Voltune", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        }
        children(ROOT)?.firstOrNull { it.mediaId == mediaId }?.let { return it }
        database.loadTracks().firstOrNull { MediaItemMapper.matchesMediaId(it, mediaId) }
            ?.let { return mapper.toLibraryItem(it, artworkAuthority) }
        for (category in listOf(ARTISTS, ALBUMS, PLAYLISTS, SMART)) {
            children(category)?.firstOrNull { it.mediaId == mediaId }?.let { return it }
        }
        return null
    }

    private fun search(rawQuery: String): List<MediaItem> {
        val query = Track.normalizeSearchText(rawQuery)
        if (query.isEmpty()) return emptyList()
        return database.loadTracks()
            .filter { query in it.normalizedSearchText }
            .mapTo(ArrayList()) { mapper.toLibraryItem(it, artworkAuthority) }
    }

    private fun smartItems(tracks: List<Track>, name: String): List<MediaItem> {
        val definition = runCatching { SmartPlaylistDefinition.valueOf(name) }.getOrNull()
            ?: return emptyList()
        return mediaItems(
            smartResolver.resolve(
                definition,
                tracks,
                database.loadFavorites(),
                System.currentTimeMillis(),
                0,
            ),
        )
    }

    private fun playlistItems(tracks: List<Track>, name: String): List<MediaItem> {
        val playlist = database.loadPlaylists().firstOrNull { it.name == name } ?: return emptyList()
        return mediaItems(
            playlist.uris.mapNotNull { uri -> tracks.firstOrNull { it.uri == uri } },
        )
    }

    private fun mediaItems(tracks: List<Track>): List<MediaItem> =
        tracks.mapTo(ArrayList()) { mapper.toLibraryItem(it, artworkAuthority) }

    companion object {
        private const val ROOT = "voltune.root"
        private const val SONGS = "voltune.songs"
        private const val ARTISTS = "voltune.artists"
        private const val ALBUMS = "voltune.albums"
        private const val PLAYLISTS = "voltune.playlists"
        private const val SMART = "voltune.smart"
        private const val ARTIST_PREFIX = "voltune.artist."
        private const val ALBUM_PREFIX = "voltune.album."
        private const val PLAYLIST_PREFIX = "voltune.playlist."
        private const val SMART_PREFIX = "voltune.smart."

        private fun filter(tracks: List<Track>, value: String, artist: Boolean): List<Track> =
            tracks.filterTo(ArrayList()) { value == if (artist) it.artist else it.album }

        private fun uniqueValues(tracks: List<Track>, artist: Boolean): Set<String> {
            val values = tracks.mapNotNullTo(ArrayList()) { track ->
                (if (artist) track.artist else track.album).takeIf { it.isNotBlank() }
            }
            values.sortWith(String.CASE_INSENSITIVE_ORDER)
            return LinkedHashSet(values)
        }

        private fun folders(values: Set<String>, prefix: String, mediaType: Int): List<MediaItem> =
            values.mapTo(ArrayList()) { value -> folder(prefix + encode(value), value, mediaType) }

        private fun folder(id: String, title: String, mediaType: Int): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(mediaType)
                .build()
            return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata).build()
        }

        private fun page(source: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
            val startLong = page.toLong() * pageSize
            if (startLong >= source.size) return emptyList()
            val start = startLong.toInt()
            return ArrayList(source.subList(start, minOf(source.size, start + pageSize)))
        }

        private fun encode(value: String): String = Base64.encodeToString(
            value.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        private fun decode(value: String): String = try {
            String(
                Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP),
                StandardCharsets.UTF_8,
            )
        } catch (_: IllegalArgumentException) {
            ""
        }
    }
}
