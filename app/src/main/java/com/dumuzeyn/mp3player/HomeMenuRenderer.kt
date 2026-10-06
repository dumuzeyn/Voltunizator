package com.dumuzeyn.mp3player

import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout

internal class HomeMenuRenderer(private val host: MainActivityCore) : MenuRenderer {
    private var playbackSection: HomePlaybackSection? = null

    override fun render() {
        if (host.libraryState.tracks.isEmpty()) {
            val empty = host.uiFactory.text(
                host.tr(
                    "Your library is empty. Add songs or a folder to begin.",
                    "Библиотека пуста. Добавьте песни или папку.",
                ),
                17,
                false,
            ).apply { setPadding(0, host.dp(24), 0, host.dp(24)) }
            host.list.addView(empty)
            return
        }

        val shownTracks = HashSet<String>()
        val section = HomePlaybackSection(host)
        playbackSection = section
        host.list.addView(section)
        val content = host.libraryState.homeContent
        addTracks(host.tr("Recently played", "Недавно слушали"), content.recentlyPlayed, shownTracks)
        host.list.addView(RandomQueueSection(host))
        addTracks(host.tr("Recently added", "Недавно добавленные"), content.recentlyAdded, shownTracks)
        addTracks(host.tr("Most played", "Часто слушаемые"), content.mostPlayed, shownTracks)
        addTracks(host.tr("Favorites", "Избранное"), content.favorites, shownTracks)
        addPlaylists(content.playlists)
        addGroups(host.tr("Artists", "Исполнители"), content.artists, true)
        addGroups(host.tr("Albums", "Альбомы"), content.albums, false)
        section.setStaticTrackKeys(shownTracks)
    }

    fun refreshPlaybackSection() {
        playbackSection?.refresh()
    }

    fun setPlaybackTransitionPaused(paused: Boolean) {
        playbackSection?.setTransitionPaused(paused)
    }

    override fun needsMiniSpacer(): Boolean = true

    private fun addTracks(title: String, tracks: List<Track>, shownTracks: MutableSet<String>) {
        val unique = HomeTrackVisibility.takeUnseen(tracks, shownTracks)
        if (unique.isEmpty()) return
        addHeading(title)
        unique.forEach { host.list.addView(host.songsRenderer.songRow(it, true, false)) }
    }

    private fun addPlaylists(playlists: List<Playlist>) {
        if (playlists.isEmpty()) return
        addHeading(host.tr("Recent playlists", "Последние плейлисты"))
        playlists.forEach { playlist ->
            addButton(playlist.name, host.appearanceState.playlistCardOpacity) {
                host.overlayController.openPlaylist(playlist)
            }
        }
    }

    private fun addGroups(title: String, values: List<String>, artist: Boolean) {
        if (values.isEmpty()) return
        addHeading(title)
        val row = host.uiFactory.row()
        values.take(3).forEach { value ->
            val button = host.uiFactory.button(value).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                host.uiFactory.applySecondaryButtonStyle(
                    this,
                    if (artist) {
                        host.appearanceState.artistCardOpacity
                    } else {
                        host.appearanceState.albumCardOpacity
                    },
                )
                setOnClickListener {
                    host.overlayController.openGroup(value, matchingTracks(value, artist))
                }
            }
            row.addView(
                button,
                LinearLayout.LayoutParams(0, host.uiFactory.libraryCardHeight(), 1f).apply {
                    setMargins(host.dp(2), 0, host.dp(2), 0)
                },
            )
        }
        host.list.addView(row)
    }

    private fun matchingTracks(value: String, artist: Boolean): ArrayList<Track> {
        val groups = if (artist) {
            host.libraryState.homeContent.artistTracks
        } else {
            host.libraryState.homeContent.albumTracks
        }
        return groups[value]?.let(::ArrayList) ?: ArrayList()
    }

    private fun addHeading(value: String) {
        val heading = host.uiFactory.text(value, 18, true).apply {
            setPadding(0, host.dp(14), 0, host.dp(4))
        }
        host.list.addView(heading, LinearLayout.LayoutParams(-1, host.dp(50)))
    }

    private fun addButton(label: String, opacity: Int, action: () -> Unit) {
        val button = host.uiFactory.button(label).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            host.uiFactory.applySecondaryButtonStyle(this, opacity)
            setOnClickListener { action() }
        }
        host.list.addView(
            button,
            LinearLayout.LayoutParams(-1, host.uiFactory.libraryCardHeight()).apply {
                setMargins(0, host.dp(2), 0, host.dp(2))
            },
        )
    }
}
