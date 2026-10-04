package com.dumuzeyn.mp3player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Reads audio metadata and persists tracks through the SQLite library boundary. */
object TrackStore {
    private const val MAX_TEXT_LENGTH = 160

    @JvmStatic
    fun load(context: Context): ArrayList<Track> {
        LibraryDatabase.migrateLegacyIfNeeded(context)
        return LibraryDatabase(context).use { it.loadTracks() }
    }

    @JvmStatic
    fun loadFromJson(raw: String?): ArrayList<Track> {
        val tracks = ArrayList<Track>()
        try {
            val array = JSONArray(raw ?: "[]")
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                tracks += Track(
                    item.getString("uri"),
                    item.optString("title", "Song"),
                    item.optString("artist", "Unknown artist"),
                    item.optString("album", "Unknown album"),
                    item.optString("genre", "Unknown genre"),
                    item.optInt("durationMs", 0),
                )
            }
        } catch (error: Exception) {
            VoltuneLog.failure("track_load_failed", error)
            tracks.clear()
        }
        sort(tracks)
        return tracks
    }

    @JvmStatic
    fun save(context: Context, tracks: List<Track>) {
        LibraryDatabase(context).use { it.saveTracks(tracks) }
    }

    @JvmStatic
    fun upsert(context: Context, track: Track) {
        LibraryDatabase(context).use { it.upsertTrack(track) }
    }

    @JvmStatic
    fun delete(context: Context, track: Track?) {
        if (track == null) return
        LibraryDatabase(context).use { it.deleteTrack(track.trackId) }
    }

    @JvmStatic
    fun updateMetadata(context: Context, track: Track) {
        LibraryDatabase(context).use { it.updateTrackMetadata(track) }
    }

    @JvmStatic
    fun updateMetadata(context: Context, tracks: List<Track>) {
        LibraryDatabase(context).use { it.updateTrackMetadata(tracks) }
    }

    @JvmStatic
    fun loadMetadataRefreshCandidates(context: Context, revision: Int): ArrayList<Track> =
        LibraryDatabase(context).use { it.loadTracksNeedingMetadataRefresh(revision) }

    @JvmStatic
    fun applyMaintenance(
        context: Context,
        refreshed: List<Track>,
        checkedTrackIds: Set<String>,
        unavailable: List<Track>,
        revision: Int,
    ) {
        LibraryDatabase(context).use {
            it.applyMaintenance(refreshed, checkedTrackIds, unavailable, revision)
        }
    }

    @JvmStatic
    fun fromUri(context: Context, uri: Uri): Track? {
        if (!canOpenForRead(context, uri)) {
            VoltuneLog.warning("add_track_failed reason=unreadable")
            return null
        }
        val metadata = readMetadata(context, uri)
        val title = metadata.title?.takeIf { it.isNotBlank() }
            ?: displayTitle(context, uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Song"
        val artist = metadata.artist.takeUnless(::isBlank) ?: "Unknown artist"
        val album = metadata.album.takeUnless(::isBlank) ?: "Unknown album"
        val albumArtist = metadata.albumArtist.takeUnless(::isBlank) ?: artist
        val genre = GenreNormalizer.normalize(metadata.genre)
        val file = readFileIdentity(context, uri)
        val track = Track(
            TrackIdentity.create(),
            uri.toString(),
            cleanText(title),
            cleanText(artist),
            cleanText(album),
            cleanText(albumArtist),
            cleanText(genre),
            metadata.year,
            metadata.trackNumber,
            metadata.discNumber,
            metadata.durationMs,
            file.size,
            file.lastModified,
            file.fingerprint,
            0,
            0,
            System.currentTimeMillis(),
            0L,
            0L,
        )
        VoltuneLog.info("add_track_success duration_known=${track.durationMs > 0}")
        return track
    }

    @JvmStatic
    fun refreshMetadata(context: Context, oldTrack: Track): Track {
        val fresh = fromUri(context, Uri.parse(oldTrack.uri)) ?: return oldTrack
        val artist = fresh.artist.takeUnless(::isBlank) ?: oldTrack.artist
        val album = fresh.album.takeUnless(::isBlank) ?: oldTrack.album
        val genre = fresh.genre.takeUnless(GenreNormalizer::isUnknown) ?: oldTrack.genre
        val albumArtist = fresh.albumArtist.takeUnless(::isBlank) ?: oldTrack.albumArtist
        return Track(
            oldTrack.trackId,
            oldTrack.uri,
            oldTrack.title,
            artist,
            album,
            albumArtist,
            genre,
            fresh.year.takeIf { it > 0 } ?: oldTrack.year,
            fresh.trackNumber.takeIf { it > 0 } ?: oldTrack.trackNumber,
            fresh.discNumber.takeIf { it > 0 } ?: oldTrack.discNumber,
            fresh.durationMs.takeIf { it > 0 } ?: oldTrack.durationMs,
            fresh.fileSize,
            fresh.lastModified,
            fresh.fingerprint,
            oldTrack.playCount,
            oldTrack.skipCount,
            oldTrack.dateAdded,
            oldTrack.lastPlayedAt,
            oldTrack.lastCompletedAt,
        )
    }

    @JvmStatic
    fun refreshFolderAlbumMetadata(context: Context, oldTrack: Track): Track {
        val refreshed = refreshMetadata(context, oldTrack)
        return refreshed.withMetadata(
            oldTrack.title,
            keepExplicit(oldTrack.artist, "Unknown artist", refreshed.artist),
            refreshed.album,
            keepExplicit(oldTrack.albumArtist, "Unknown artist", refreshed.albumArtist),
            if (GenreNormalizer.isUnknown(oldTrack.genre)) refreshed.genre else oldTrack.genre,
            oldTrack.year.takeIf { it > 0 } ?: refreshed.year,
            oldTrack.trackNumber.takeIf { it > 0 } ?: refreshed.trackNumber,
            oldTrack.discNumber.takeIf { it > 0 } ?: refreshed.discNumber,
        )
    }

    @JvmStatic
    fun canOpenForRead(context: Context, uri: Uri): Boolean {
        var descriptor: AssetFileDescriptor? = null
        return try {
            descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
            descriptor != null
        } catch (error: Exception) {
            VoltuneLog.failure("read_check_failed", error)
            false
        } finally {
            closeQuietly(descriptor)
        }
    }

    private fun displayTitle(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() } else null
            }
    }.getOrNull()

    @JvmStatic
    fun updateDuration(context: Context, uri: String?, durationMs: Int) {
        if (durationMs <= 0 || uri.isNullOrEmpty()) return
        LibraryDatabase(context).use { it.updateDuration(uri, durationMs) }
        VoltuneLog.info("duration_updated")
    }

    @JvmStatic
    fun updateLocation(context: Context, track: Track) {
        LibraryDatabase(context).use { it.updateTrackLocation(track) }
    }

    @JvmStatic
    fun updateAvailability(context: Context, trackId: String, reason: String?) {
        LibraryDatabase(context).use { it.updateAvailability(trackId, reason) }
    }

    @JvmStatic
    fun sort(tracks: List<Track>) {
        if (tracks is MutableList<Track>) {
            tracks.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }

    private fun readMetadata(context: Context, uri: Uri): Metadata {
        val metadata = readMetadataDirect(context, uri)
        if (metadata.durationMs <= 0) metadata.mergeMissing(readMetadataWithFileDescriptor(context, uri))
        if (metadata.durationMs <= 0) VoltuneLog.warning("duration_missing")
        return metadata
    }

    @JvmStatic
    fun readFileIdentity(context: Context, uri: Uri): FileIdentity {
        var size = -1L
        var lastModified = 0L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val modifiedColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                        lastModified = cursor.getLong(modifiedColumn)
                    }
                }
            }
        } catch (_: Exception) {
            // Some providers expose neither size nor modification time.
        }
        return FileIdentity(size, lastModified, fingerprint(context, uri))
    }

    private fun fingerprint(context: Context, uri: Uri): String {
        var input: InputStream? = null
        return try {
            input = context.contentResolver.openInputStream(uri) ?: return ""
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var remaining = 64 * 1024
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                digest.update(buffer, 0, count)
                remaining -= count
            }
            buildString {
                digest.digest().forEach { value ->
                    append(String.format(Locale.ROOT, "%02x", value))
                }
            }
        } catch (error: Exception) {
            VoltuneLog.failure("fingerprint_failed", error)
            ""
        } finally {
            try {
                input?.close()
            } catch (_: Exception) {
                // Ignore provider close failures.
            }
        }
    }

    private fun readMetadataDirect(context: Context, uri: Uri): Metadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Metadata.from(retriever)
        } catch (error: Throwable) {
            VoltuneLog.failure("metadata_direct_failed", error)
            Metadata()
        } finally {
            releaseQuietly(retriever)
        }
    }

    private fun readMetadataWithFileDescriptor(context: Context, uri: Uri): Metadata {
        val retriever = MediaMetadataRetriever()
        var descriptor: AssetFileDescriptor? = null
        return try {
            descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: return Metadata()
            if (descriptor.declaredLength >= 0) {
                retriever.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            } else {
                retriever.setDataSource(descriptor.fileDescriptor)
            }
            Metadata.from(retriever)
        } catch (error: Throwable) {
            VoltuneLog.failure("metadata_fd_failed", error)
            Metadata()
        } finally {
            releaseQuietly(retriever)
            closeQuietly(descriptor)
        }
    }

    private fun cleanText(value: String?): String {
        val cleaned = value.orEmpty().replace('\u0000', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        return cleaned.take(MAX_TEXT_LENGTH).trim()
    }

    private fun isBlank(value: String?): Boolean = value.isNullOrBlank()

    private fun keepExplicit(current: String, placeholder: String, refreshed: String): String =
        current.takeUnless {
            it.isBlank() ||
                it.equals(placeholder, ignoreCase = true) ||
                it.equals("<unknown>", ignoreCase = true)
        } ?: refreshed

    private fun parseDurationMs(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return try {
            raw.trim().toLong().takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        } catch (error: Exception) {
            VoltuneLog.failure("duration_parse_failed", error)
            0
        }
    }

    private fun releaseQuietly(retriever: MediaMetadataRetriever) {
        try {
            retriever.release()
        } catch (_: Exception) {
            // Ignore platform release failures.
        }
    }

    private fun closeQuietly(descriptor: AssetFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (_: Exception) {
            // Ignore provider close failures.
        }
    }

    private class Metadata {
        var album: String? = null
        var albumArtist: String? = null
        var artist: String? = null
        var discNumber = 0
        var durationMs = 0
        var genre: String? = null
        var title: String? = null
        var trackNumber = 0
        var year = 0

        fun mergeMissing(fallback: Metadata?) {
            if (fallback == null) return
            if (title.isNullOrBlank()) title = fallback.title
            if (artist.isNullOrBlank()) artist = fallback.artist
            if (album.isNullOrBlank()) album = fallback.album
            if (albumArtist.isNullOrBlank()) albumArtist = fallback.albumArtist
            if (genre.isNullOrBlank()) genre = fallback.genre
            if (durationMs <= 0) durationMs = fallback.durationMs
            if (year <= 0) year = fallback.year
            if (trackNumber <= 0) trackNumber = fallback.trackNumber
            if (discNumber <= 0) discNumber = fallback.discNumber
        }

        companion object {
            fun from(retriever: MediaMetadataRetriever): Metadata = Metadata().apply {
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                year = MetadataValidator.year(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                )
                trackNumber = MetadataValidator.trackNumber(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                )
                discNumber = MetadataValidator.trackNumber(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                )
                durationMs = parseDurationMs(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                )
            }
        }
    }

    class FileIdentity(
        @JvmField val size: Long,
        @JvmField val lastModified: Long,
        fingerprint: String?,
    ) {
        @JvmField val fingerprint: String = fingerprint.orEmpty()
    }
}
