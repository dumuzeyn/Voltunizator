package com.dumuzeyn.mp3player

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import com.dumuzeyn.mp3player.ui.permissions.DeviceAudioPermissionController
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class AudioImportController(private val host: MainActivityCore) {
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val automaticScanStarted = AtomicBoolean()

    @Volatile private var closed = false
    @Volatile private var libraryReady = false
    private var pendingExternalAudio: Pair<Uri, Int>? = null

    fun openExternalAudio(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val type = intent.type ?: return
        if (uri.scheme != "content" ||
            !type.startsWith("audio/") && type != "application/ogg") return
        val request = uri to intent.flags
        if (libraryReady) playExternalAudio(request) else pendingExternalAudio = request
    }

    fun openFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        host.startActivityForResult(
            Intent.createChooser(intent, host.tr("Choose music", "Выберите музыку")),
            PICK_AUDIO,
        )
    }

    fun openFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        host.startActivityForResult(
            Intent.createChooser(intent, host.tr("Choose music folder", "Выберите папку с музыкой")),
            PICK_AUDIO_FOLDER,
        )
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) return false
        val selectedUris = ArrayList<Uri>()
        val selectedTree: Uri?
        when {
            requestCode == PICK_AUDIO -> {
                data.clipData?.let { clips ->
                    for (index in 0 until clips.itemCount) {
                        selectedUris.add(clips.getItemAt(index).uri)
                    }
                } ?: data.data?.let(selectedUris::add)
                selectedTree = null
            }
            requestCode == PICK_AUDIO_FOLDER && data.data != null -> selectedTree = data.data
            else -> return false
        }
        val permissionFlags = data.flags
        val existingTracks = ArrayList(host.libraryState.tracks)
        val knownUris = existingTracks.mapTo(HashSet(), Track::uri)
        return try {
            importExecutor.execute {
                processImport(
                    selectedUris,
                    selectedTree,
                    permissionFlags,
                    knownUris,
                    existingTracks,
                )
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun close() {
        closed = true
        importExecutor.shutdown()
    }

    fun importExported(uri: Uri, permissionFlags: Int) = handleActivityResult(
        PICK_AUDIO, Activity.RESULT_OK, Intent().setData(uri).setFlags(permissionFlags),
    )

    fun onLibraryReady() {
        libraryReady = true
        pendingExternalAudio?.let { request ->
            pendingExternalAudio = null
            playExternalAudio(request)
        }
        autoImportDeviceMusicIfAllowed()
    }

    private fun playExternalAudio(request: Pair<Uri, Int>) {
        val (uri, flags) = request
        host.findTrack(uri.toString())?.let {
            host.playbackQueueController.playTrack(it)
            return
        }
        val existing = ArrayList(host.libraryState.tracks)
        val known = existing.mapTo(HashSet(), Track::uri)
        try {
            importExecutor.execute {
                val result = runCatching {
                    val track = readTrack(uri, flags, true, known, existing)
                        ?: return@runCatching null
                    val imported = LibraryImportStore(host).use { store ->
                        store.commitStandalone(listOf(track), true)
                    }
                    track to imported
                }
                result.exceptionOrNull()?.let { VoltuneLog.failure("external_audio_open_failed", it) }
                host.uiHandler.post {
                    if (closed) return@post
                    val playback = result.getOrNull()
                    if (playback == null) {
                        Toast.makeText(host, host.tr("Cannot open this audio file",
                            "Не удалось открыть аудиофайл"), Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val (track, imported) = playback
                    if (imported.isNotEmpty()) {
                        for (item in imported) {
                            val index = indexOfTrackId(host.libraryState.tracks, item.trackId)
                            if (index >= 0) host.libraryState.tracks[index] = item
                            else host.libraryState.tracks.add(item)
                        }
                        TrackStore.sort(host.libraryState.tracks)
                        host.libraryRepository.reindex()
                        host.librarySnapshotApplier.rebuildDerivedAndRender()
                    }
                    host.playbackController.submitQueue(
                        listOf(imported.firstOrNull() ?: track), 0, 0, host.repeatMode(), true)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Activity is closing.
        }
    }

    fun onAudioPermissionChanged() {
        autoImportDeviceMusicIfAllowed()
    }

    private fun autoImportDeviceMusicIfAllowed() {
        if (closed || !libraryReady ||
            host.intent.getIntExtra(BenchmarkLibrarySeeder.EXTRA_TRACK_COUNT, 0) > 0 ||
            !DeviceAudioPermissionController.hasPermission(host) ||
            !automaticScanStarted.compareAndSet(false, true)
        ) {
            return
        }
        val knownUris = host.libraryState.tracks.mapTo(HashSet(), Track::uri)
        try {
            importExecutor.execute {
                val store = LibraryImportStore(host)
                val imported = try {
                    val sources = LibrarySourceStore(host)
                    val exclusions = try {
                        ExcludedTrackIndex(sources.exclusions(null))
                    } finally {
                        sources.close()
                    }
                    store.commitStandalone(DeviceMusicScanner.scan(host, knownUris, exclusions), false)
                } finally {
                    store.close()
                }
                publishImportedTracks(imported)
            }
        } catch (_: RejectedExecutionException) {
            // Activity is already closing.
        }
    }

    private fun processImport(
        selectedUris: ArrayList<Uri>,
        treeUri: Uri?,
        permissionFlags: Int,
        knownUris: HashSet<String>,
        existingTracks: ArrayList<Track>,
    ) {
        var imported = ArrayList<Track>()
        if (treeUri != null) {
            imported.addAll(importFolder(treeUri, permissionFlags, knownUris, existingTracks))
        } else {
            for (uri in selectedUris) {
                readTrack(uri, permissionFlags, true, knownUris, existingTracks)?.let(imported::add)
            }
            val store = LibraryImportStore(host)
            imported = try {
                store.commitStandalone(imported, true)
            } finally {
                store.close()
            }
        }
        publishImportedTracks(imported)
    }

    private fun publishImportedTracks(imported: ArrayList<Track>) {
        if (imported.isEmpty() || closed) return
        host.uiHandler.post {
            if (closed) return@post
            for (track in imported) {
                val existingIndex = indexOfTrackId(host.libraryState.tracks, track.trackId)
                if (existingIndex >= 0) {
                    host.libraryState.tracks[existingIndex] = track
                } else if (host.findTrack(track.uri) == null) {
                    host.libraryState.tracks.add(track)
                }
            }
            TrackStore.sort(host.libraryState.tracks)
            host.libraryRepository.reindex()
            host.librarySnapshotApplier.rebuildDerivedAndRender()
        }
    }

    private fun importFolder(
        treeUri: Uri,
        flags: Int,
        knownUris: HashSet<String>,
        existingTracks: ArrayList<Track>,
    ): ArrayList<Track> {
        val importedTracks = ArrayList<Track>()
        if (!treeUri.scheme.equals("content", ignoreCase = true)) return importedTracks
        val takeFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            host.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (error: RuntimeException) {
            VoltuneLog.failure("persist_folder_permission_failed", error)
        }
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val source = PersistedFolderStore.remember(host, treeUri, queryDisplayName(rootDocument), true)
            ?: return importedTracks
        var store = LibraryImportStore(host)
        val session = try {
            store.session(source)
        } finally {
            store.close()
        }
        val discovered = ArrayList<DiscoveredTrack>()
        val existingByUri = indexByUri(existingTracks)
        val imported = intArrayOf(0)
        try {
            scanDocumentTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
                imported,
                knownUris,
                discovered,
                existingTracks,
                existingByUri,
                session,
            )
        } catch (error: RuntimeException) {
            VoltuneLog.failure("folder_import_failed", error)
        }
        store = LibraryImportStore(host)
        try {
            importedTracks.addAll(store.commitSource(session, discovered))
        } finally {
            store.close()
        }
        return importedTracks
    }

    private fun scanDocumentTree(
        treeUri: Uri,
        documentId: String,
        imported: IntArray,
        knownUris: HashSet<String>,
        discovered: ArrayList<DiscoveredTrack>,
        existingTracks: ArrayList<Track>,
        existingByUri: Map<String, Track>,
        session: SourceScanSession,
    ) {
        if (closed || imported[0] >= MAX_FOLDER_IMPORT) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        var cursor: Cursor? = null
        try {
            cursor = host.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            while (cursor?.moveToNext() == true && imported[0] < MAX_FOLDER_IMPORT) {
                val childId = cursor.getString(0)
                val mimeType = cursor.getString(1)
                val displayName = cursor.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanDocumentTree(
                        treeUri,
                        childId,
                        imported,
                        knownUris,
                        discovered,
                        existingTracks,
                        existingByUri,
                        session,
                    )
                } else if (isAudioDocument(mimeType, displayName)) {
                    val identity = TrackOrigin.identity(session.source.sourceId, childId)
                    if (session.exclusions.containsIdentity(identity)) continue
                    val existing = existingByUri[childUri.toString()]
                    if (existing != null) {
                        discovered.add(DiscoveredTrack(existing, session.source, childId))
                        imported[0]++
                        continue
                    }
                    val track = readTrack(childUri, 0, false, knownUris, existingTracks)
                    if (track != null && !session.exclusions.contains(identity, track)) {
                        discovered.add(DiscoveredTrack(track, session.source, childId))
                        imported[0]++
                    }
                }
            }
        } catch (error: RuntimeException) {
            VoltuneLog.failure("folder_scan_failed", error)
        } finally {
            cursor?.close()
        }
    }

    private fun isAudioDocument(mimeType: String?, displayName: String?): Boolean =
        mimeType?.lowercase(Locale.ROOT)?.startsWith("audio/") == true ||
            hasAudioExtension(displayName)

    private fun readTrack(
        uri: Uri,
        permissionFlags: Int,
        persistPermission: Boolean,
        knownUris: MutableSet<String>,
        existingTracks: List<Track>,
    ): Track? {
        if (!isSafeAudioUri(uri)) {
            VoltuneLog.warning("add_track_rejected reason=unsafe_uri")
            return null
        }
        if (persistPermission) {
            var takeFlags = permissionFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                host.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (error: RuntimeException) {
                VoltuneLog.failure("persist_permission_failed", error)
            }
        }
        val value = uri.toString()
        if (value in knownUris) return null
        return try {
            val canOpen = TrackStore.canOpenForRead(host, uri)
            VoltuneLog.info("add_track_candidate readable=$canOpen")
            if (!canOpen) return null
            var track = TrackStore.fromUri(host, uri)
            if (track != null) {
                val matches = TrackRelinker.candidates(existingTracks, track)
                if (matches.size == 1 && !TrackStore.canOpenForRead(host, matches[0].asUri())) {
                    val old = matches[0]
                    track = Track(
                        old.trackId,
                        track.uri,
                        track.title,
                        track.artist,
                        track.album,
                        track.genre,
                        track.durationMs,
                        track.fileSize,
                        track.lastModified,
                        track.fingerprint,
                    )
                } else if (matches.size > 1) {
                    VoltuneLog.warning("relink_requires_confirmation candidates=${matches.size}")
                    val ambiguous = track
                    host.uiHandler.post { confirmAmbiguousImport(ambiguous, matches.size) }
                    return null
                }
                knownUris.add(value)
                VoltuneLog.info("add_track_saved duration_known=${track.durationMs > 0}")
            }
            track
        } catch (error: RuntimeException) {
            VoltuneLog.failure("add_track_failed", error)
            null
        }
    }

    fun rescanPersistedFolders() {
        val sources = PersistedFolderStore.list(host)
            .filterTo(ArrayList()) { PersistedFolderStore.hasReadPermission(host, it.asUri()) }
        if (sources.isEmpty()) {
            openFolder()
            return
        }
        val existing = ArrayList(host.libraryState.tracks)
        val knownUris = existing.mapTo(HashSet(), Track::uri)
        importExecutor.execute {
            for (source in sources) processRescan(source, knownUris, existing)
        }
    }

    private fun processRescan(
        source: LibrarySource,
        knownUris: HashSet<String>,
        existingTracks: ArrayList<Track>,
    ) {
        var store = LibraryImportStore(host)
        val session = try {
            store.session(source)
        } finally {
            store.close()
        }
        val discovered = ArrayList<DiscoveredTrack>()
        val imported = intArrayOf(0)
        try {
            scanDocumentTree(
                source.asUri(),
                DocumentsContract.getTreeDocumentId(source.asUri()),
                imported,
                knownUris,
                discovered,
                existingTracks,
                indexByUri(existingTracks),
                session,
            )
        } catch (error: RuntimeException) {
            VoltuneLog.failure("folder_rescan_failed", error)
        }
        store = LibraryImportStore(host)
        val accepted = try {
            store.commitSource(session, discovered)
        } finally {
            store.close()
        }
        publishImportedTracks(accepted)
    }

    private fun confirmAmbiguousImport(track: Track, candidateCount: Int) {
        if (closed) return
        host.showActionPanel(
            host.tr("Possible moved file", "Возможно, файл был перемещён"),
            host.tr(
                "Voltune found several similar unavailable records. Import this file " +
                    "as a separate track? Candidates: ",
                "Voltune нашёл несколько похожих недоступных записей. Импортировать " +
                    "этот файл как отдельный трек? Совпадений: ",
            ) + candidateCount,
            host.tr("Cancel", "Отмена"),
            host.tr("Import separately", "Импортировать отдельно"),
            true,
        ) {
            val store = LibraryImportStore(host)
            try {
                store.commitStandalone(listOf(track), true)
            } finally {
                store.close()
            }
            if (host.findTrack(track.uri) == null) {
                host.libraryState.tracks.add(track)
                TrackStore.sort(host.libraryState.tracks)
            }
            host.libraryRepository.reindex()
            host.librarySnapshotApplier.rebuildDerivedAndRender()
        }
    }

    private fun isSafeAudioUri(uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        return try {
            val type = host.contentResolver.getType(uri)
            val extensionMatches = hasAudioExtension(queryDisplayName(uri))
            if (type != null && !type.lowercase(Locale.ROOT).startsWith("audio/") &&
                !extensionMatches
            ) {
                return false
            }
            if (type == null && !extensionMatches) return false
            val size = querySize(uri)
            size <= 0L || size <= MAX_AUDIO_BYTES
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = host.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else uri.lastPathSegment
        } finally {
            cursor?.close()
        }
    }

    private fun querySize(uri: Uri): Long {
        var cursor: Cursor? = null
        return try {
            cursor = host.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() == true) cursor.getLong(0) else -1L
        } finally {
            cursor?.close()
        }
    }

    companion object {
        private const val PICK_AUDIO = 2001
        private const val PICK_AUDIO_FOLDER = 2002
        private const val MAX_FOLDER_IMPORT = 3_000
        private const val MAX_AUDIO_BYTES = 220L * 1024L * 1024L
        private val AUDIO_EXTENSIONS = arrayOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga", ".opus", ".flac")

        internal fun hasAudioExtension(displayName: String?): Boolean {
            val lower = displayName?.lowercase(Locale.ROOT) ?: return false
            return AUDIO_EXTENSIONS.any(lower::endsWith)
        }

        private fun indexOfTrackId(tracks: List<Track>, trackId: String): Int =
            tracks.indexOfFirst { it.trackId == trackId }

        private fun indexByUri(tracks: List<Track>): HashMap<String, Track> =
            tracks.associateByTo(HashMap(), Track::uri)
    }
}
