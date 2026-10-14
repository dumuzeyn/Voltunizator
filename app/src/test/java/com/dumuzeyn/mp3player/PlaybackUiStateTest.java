package com.dumuzeyn.mp3player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class PlaybackUiStateTest {
    @Test
    public void projectsCurrentTrackAndPlaybackWithoutOwningMedia3() {
        LibraryState library = new LibraryState();
        Track track = new Track("content://song/1", "Song", "Artist");
        library.tracks.add(track);
        PlaybackUiState state = new PlaybackUiState();
        String mediaId = new MediaItemMapper().mediaId(track);

        state.updateSnapshot(new PlaybackSnapshot(
                Collections.singletonList(mediaId), mediaId, 0,
                12_000L, 60_000L, true, Player.STATE_READY,
                Player.REPEAT_MODE_ALL, false, PlaybackPhase.READY,
                PauseReason.NONE, StopReason.NONE, null, 1L));

        assertEquals(0, state.currentTrackIndex(library));
        assertTrue(state.isPlaying());
        assertEquals(2, state.repeatMode());

        state.updateSnapshot(PlaybackSnapshot.empty());
        assertFalse(state.isPlaying());
        assertSame(track, library.tracks.get(0));
    }

    @Test
    public void stalePlayIntentOnIdleOrEmptyQueueDoesNotReportActivePlayback() {
        PlaybackUiState state = new PlaybackUiState();
        state.updateSnapshot(snapshot(Collections.singletonList("track"), PlaybackPhase.IDLE));
        assertFalse(state.isPlaying());
        state.updateSnapshot(snapshot(Collections.emptyList(), PlaybackPhase.READY));
        assertFalse(state.isPlaying());
        state.updateSnapshot(snapshot(Collections.singletonList("track"), PlaybackPhase.BUFFERING));
        assertTrue(state.isPlaying());
        state.updateSnapshot(snapshot(Collections.singletonList("track"), PlaybackPhase.READY));
        assertTrue(state.isPlaying());
    }

    private static PlaybackSnapshot snapshot(java.util.List<String> ids, PlaybackPhase phase) {
        return new PlaybackSnapshot(ids, ids.isEmpty() ? "" : ids.get(0), ids.isEmpty() ? -1 : 0,
                0L, 1000L, true, phase == PlaybackPhase.IDLE ? Player.STATE_IDLE : Player.STATE_READY,
                Player.REPEAT_MODE_OFF, false, phase, PauseReason.NONE, StopReason.NONE, null, 1L);
    }

    @Test
    public void cachedCurrentIndexIsValidatedAfterLibraryReorder() {
        LibraryState library = new LibraryState();
        Track current = new Track("track-current", "content://song/current", "Current",
                "Artist", "Album", "Genre", 1000, 10L, 20L, "fingerprint");
        Track other = new Track("content://song/other", "Other", "Artist");
        library.tracks.addAll(Arrays.asList(current, other));
        PlaybackUiState state = new PlaybackUiState();
        state.updateSnapshot(new PlaybackSnapshot(
                Collections.singletonList(current.trackId), current.trackId, 0,
                0L, 1000L, false, Player.STATE_READY, Player.REPEAT_MODE_OFF,
                false, PlaybackPhase.READY, PauseReason.NONE, StopReason.NONE, null, 1L));

        assertEquals(0, state.currentTrackIndex(library));
        Collections.swap(library.tracks, 0, 1);

        assertEquals(1, state.currentTrackIndex(library));
    }
}
