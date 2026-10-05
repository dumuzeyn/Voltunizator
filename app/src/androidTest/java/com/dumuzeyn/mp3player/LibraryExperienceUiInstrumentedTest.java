package com.dumuzeyn.mp3player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager2.widget.ViewPager2;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LibraryExperienceUiInstrumentedTest {
    private Instrumentation instrumentation;
    private Activity activity;

    @After
    public void tearDown() {
        if (activity != null) {
            InstrumentedTestSupport.finishActivity(instrumentation, activity);
        }
    }

    @Test
    public void homeHierarchySurvivesPlaybackChangesAndBothNavigationPaths() {
        MainActivityCore host = launchWithLibrary();
        View coldHomeContent = host.list.getChildAt(0);
        Track first = host.libraryState.tracks.get(0);
        Track second = host.libraryState.tracks.get(1);

        applyPlaybackState(host, first, false);
        openTabByClick(host, LibraryTabs.SONGS);
        openTabByClick(host, LibraryTabs.HOME);
        assertSame("Paused playback rebuilt the cached Home hierarchy",
                coldHomeContent, host.list.getChildAt(0));

        applyPlaybackState(host, first, true);
        openTabByClick(host, LibraryTabs.ALBUMS);
        openTabByClick(host, LibraryTabs.HOME);
        assertSame("Active playback rebuilt the warm Home hierarchy",
                coldHomeContent, host.list.getChildAt(0));

        applyPlaybackState(host, second, true);
        openTabByClick(host, LibraryTabs.SONGS);
        openTabByClick(host, LibraryTabs.HOME);
        assertSame("Changing the current track rebuilt static Home content",
                coldHomeContent, host.list.getChildAt(0));

        openTabByClick(host, LibraryTabs.SONGS);
        swipeToPreviousTab(host);
        InstrumentedTestSupport.waitFor("Swipe did not return to Home", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.HOME
                        && !host.navigationState.tabAnimating);
        assertSame("Swipe transition rebuilt the cached Home hierarchy",
                coldHomeContent, host.list.getChildAt(0));
    }

    @Test
    public void homeSearchQueueLyricsMetadataFavoritesPlaylistsAndSettingsOpen() {
        MainActivityCore host = launchWithLibrary();
        int cardInset = host.responsiveLayoutController.contentScrollbarClearance();
        assertEquals(cardInset, host.list.getPaddingLeft());
        assertEquals(cardInset, host.list.getPaddingRight());
        assertEquals(LibraryTabs.HOME, host.navigationState.tabIndex);
        Track track = host.libraryState.tracks.get(0);
        View homeSongCard = host.list.findViewById(R.id.song_card);
        assertNotNull(homeSongCard);
        int libraryCardWidth = homeSongCard.getWidth();
        int libraryCardHeight = host.uiFactory.libraryCardHeight();
        assertLibraryCardSize(homeSongCard, libraryCardWidth, libraryCardHeight);

        Button randomQueue = host.list.findViewById(R.id.random_queue_button);
        RandomQueueCountView randomCount = host.list.findViewById(R.id.random_queue_count);
        assertNotNull(randomQueue);
        assertNotNull(randomCount);
        assertEquals(host.libraryState.tracks.size(), randomCount.getValue());
        instrumentation.runOnMainSync(randomCount::performClick);
        assertEquals(1, randomCount.getValue());
        instrumentation.runOnMainSync(randomQueue::performClick);
        InstrumentedTestSupport.waitFor("Random queue has the wrong size", 5000L,
                () -> host.playbackUiState.queue.size() == randomCount.getValue());

        Button similarQueue = host.list.findViewById(R.id.similar_queue_button);
        RandomQueueCountView similarCount = host.list.findViewById(R.id.similar_queue_count);
        assertNotNull(similarQueue);
        assertNotNull(similarCount);
        instrumentation.runOnMainSync(similarCount::performClick);
        instrumentation.runOnMainSync(similarQueue::performClick);
        InstrumentedTestSupport.waitFor("Similar queue has the wrong size", 5000L,
                () -> host.playbackUiState.queue.size() == similarCount.getValue());

        assertOverlayOpens(host, host.overlayController::openSearch);
        assertOverlayOpens(host, host.overlayController::openQueue);
        assertFullPlayerPages(host, track);
        assertOverlayOpens(host, () -> host.metadataEditorController.open(track));

        instrumentation.runOnMainSync(() -> {
            host.toggleFavorite(track);
            Playlist playlist = host.playlistController.createPlaylist("UI smoke");
            host.playlistController.addTrackToPlaylist(playlist, track);
            host.navigationState.tabIndex = LibraryTabs.HOME;
            host.librarySnapshotApplier.rebuildDerivedAndRender();
        });
        InstrumentedTestSupport.waitFor("Home collections did not render", 5000L,
                () -> findText(host.list, Button.class, "UI smoke") != null
                        && findText(host.list, Button.class, "UI album") != null);
        assertTrue(findText(host.list, Button.class, "UI smoke").getBackground()
                instanceof GradientDrawable);
        assertTrue(findText(host.list, Button.class, "UI album").getBackground()
                instanceof GradientDrawable);

        instrumentation.runOnMainSync(() -> {
            host.switchTabAnimated(LibraryTabs.SONGS, 1);
        });
        InstrumentedTestSupport.waitFor("Songs tab did not open", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.SONGS
                        && !host.navigationState.tabAnimating
                        && host.songsView.findViewById(R.id.song_card) != null);
        assertLibraryCardSize(host.songsView.findViewById(R.id.song_card),
                libraryCardWidth, libraryCardHeight);
        assertTrue(host.libraryState.favorites.contains(track.uri));
        assertFalse(host.libraryState.playlists.isEmpty());

        instrumentation.runOnMainSync(() ->
                host.switchTabAnimated(LibraryTabs.FAVORITES, 1));
        InstrumentedTestSupport.waitFor("Favorite track did not render", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.FAVORITES
                        && findText(host.list, TextView.class, track.title) != null);
        assertLibraryCardSize(host.list.findViewById(R.id.song_card),
                libraryCardWidth, libraryCardHeight);

        instrumentation.runOnMainSync(() -> {
            host.switchTabAnimated(LibraryTabs.PLAYLISTS, 1);
            assertNotNull("Playlist preview was empty during the first transition frame",
                    findText(host.contentHost, TextView.class, "UI smoke"));
        });
        InstrumentedTestSupport.waitFor("Playlists tab did not open", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.PLAYLISTS
                        && findText(host.list, TextView.class, "UI smoke") != null);
        InstrumentedTestSupport.waitFor("Compact playlist card was not laid out", 5000L,
                () -> host.list.findViewById(R.id.playlist_card) != null
                        && host.list.findViewById(R.id.playlist_card).getHeight() > 0);
        ImageView playlistCover = findPlaylistCover(host.list);
        assertNotNull(playlistCover);
        View playlistCard = host.list.findViewById(R.id.playlist_card);
        assertNotNull(playlistCard);
        assertLibraryCardSize(playlistCard, libraryCardWidth, libraryCardHeight);
        assertEquals(cardInset, host.list.getPaddingLeft());
        assertEquals(cardInset, host.list.getPaddingRight());
        assertVisibleOutline(playlistCard);
        assertEquals(host.getResources().getDimensionPixelSize(R.dimen.playlist_cover_size),
                playlistCover.getHeight());
        assertTrue("Playlist cover must support circular rotation",
                playlistCover instanceof RotatingCoverImageView);
        assertTrue("Playlist card must not contain a moving ticker",
                !containsViewClassName(host.list, "SmoothPlaylistTicker"));
        SystemClock.sleep(500L);
        Drawable[] initialPlaylistArtwork = new Drawable[1];
        instrumentation.runOnMainSync(() ->
                initialPlaylistArtwork[0] = playlistCover.getDrawable());
        SystemClock.sleep(15050L);
        instrumentation.runOnMainSync(() -> assertSame(
                "Playlist artwork changed after the removed ticker interval",
                initialPlaylistArtwork[0], playlistCover.getDrawable()));
        capture(host, "playlist-cards.png");

        assertGroupCardSize(host, LibraryTabs.GENRES, libraryCardWidth, libraryCardHeight);
        assertGroupCardSize(host, LibraryTabs.ARTISTS, libraryCardWidth, libraryCardHeight);
        assertGroupCardSize(host, LibraryTabs.ALBUMS, libraryCardWidth, libraryCardHeight);
        View groupCard = host.list.findViewById(R.id.group_card);
        assertVisibleOutline(groupCard);
        int[] groupLocation = new int[2];
        int[] contentLocation = new int[2];
        instrumentation.runOnMainSync(() -> {
            groupCard.getLocationInWindow(groupLocation);
            host.contentHost.getLocationInWindow(contentLocation);
        });
        assertTrue("Group card touches the tab wheel",
                groupLocation[1] - contentLocation[1] >= host.dp(8));
        capture(host, "collection-cards.png");

        instrumentation.runOnMainSync(() -> host.switchTabAnimated(LibraryTabs.FOLDERS, 1));
        InstrumentedTestSupport.waitFor("Folder cards did not open", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.FOLDERS
                        && host.list.findViewById(R.id.folder_card) != null
                        && !host.navigationState.tabAnimating);
        assertLibraryCardSize(host.list.findViewById(R.id.folder_card),
                libraryCardWidth, libraryCardHeight);
        assertEquals(cardInset, host.list.getPaddingLeft());
        assertEquals(cardInset, host.list.getPaddingRight());

        instrumentation.runOnMainSync(() ->
                host.switchTabAnimated(LibraryTabs.SETTINGS, 1));
        InstrumentedTestSupport.waitFor("Settings tab did not open", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.SETTINGS);
    }

    @Test
    public void homeSongsRemainSelectableAfterGeneratedQueues() {
        MainActivityCore host = launchWithLibrary(true);
        Button randomQueue = host.list.findViewById(R.id.random_queue_button);
        Button similarQueue = host.list.findViewById(R.id.similar_queue_button);
        assertNotNull(randomQueue);
        assertNotNull(similarQueue);
        assertEquals("Home needs no mini-player clearance before playback", null,
                host.list.findViewWithTag("mini-player-spacer"));

        for (Button create : new Button[] {randomQueue, similarQueue}) {
            instrumentation.runOnMainSync(create::performClick);
            InstrumentedTestSupport.waitFor("Generated queue did not start", 5000L,
                    () -> !host.playbackUiState.queue.isEmpty());
            InstrumentedTestSupport.waitFor("Home did not reserve space for the mini-player", 5000L,
                    () -> host.miniPlayer.getVisibility() == View.VISIBLE
                            && host.list.findViewWithTag("mini-player-spacer") != null);
            instrumentation.runOnMainSync(() -> host.contentScroll.scrollTo(0,
                    host.contentScroll.getChildAt(0).getHeight() - host.contentScroll.getHeight()));
            instrumentation.waitForIdleSync();
            int[] viewportLocation = new int[2];
            int[] miniLocation = new int[2];
            instrumentation.runOnMainSync(() -> {
                host.contentScroll.getLocationInWindow(viewportLocation);
                host.miniPlayer.getLocationInWindow(miniLocation);
            });
            Rect viewport = new Rect(viewportLocation[0], viewportLocation[1],
                    viewportLocation[0] + host.contentScroll.getWidth(),
                    Math.min(viewportLocation[1] + host.contentScroll.getHeight(),
                            miniLocation[1]));
            Track current = host.playbackStateProvider.currentTrack();
            Track other = null;
            Rect tapArea = null;
            for (Track candidate : host.libraryState.tracks) {
                if (current != null && candidate.uri.equals(current.uri)) continue;
                View candidateRow = findDescription(host.list,
                        "Открыть или включить песню " + candidate.title);
                if (candidateRow == null) continue;
                int[] rowLocation = new int[2];
                candidateRow.getLocationInWindow(rowLocation);
                Rect visible = new Rect(rowLocation[0], rowLocation[1],
                        rowLocation[0] + candidateRow.getWidth(),
                        rowLocation[1] + candidateRow.getHeight());
                if (visible.intersect(viewport) && visible.height() >= candidateRow.getHeight() / 2) {
                    other = candidate;
                    tapArea = visible;
                }
            }
            assertNotNull("No other Home song is visible above the mini-player", other);
            float x = tapArea.centerX();
            float y = tapArea.centerY();
            MotionEvent probe = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
            try {
                assertFalse("Test tap landed on the mini-player",
                        host.playerUiController.isInsideMiniPlayer(probe));
            } finally {
                probe.recycle();
            }
            long down = SystemClock.uptimeMillis();
            dispatchActivityTouch(host, MotionEvent.obtain(
                    down, down, MotionEvent.ACTION_DOWN, x, y, 0));
            dispatchActivityTouch(host, MotionEvent.obtain(
                    down, down + 50L, MotionEvent.ACTION_UP, x, y, 0));
            Track selected = other;
            InstrumentedTestSupport.waitFor("The on-screen tap did not select the Home song",
                    5000L, () -> host.isCurrent(selected));
            assertEquals("The song tap opened an overlay instead of selecting the song",
                    0, host.overlayHost.getChildCount());
        }
    }

    @Test
    public void scrollingContentCannotDrawBehindTabWheel() {
        MainActivityCore host = launchWithLibrary();
        instrumentation.runOnMainSync(() -> {
            assertTrue("Content host must clip children at the tab boundary",
                    host.contentHost.getClipChildren());
            assertTrue("Content host must clip drawing to its bounds",
                    host.contentHost.getClipToPadding());
            assertEquals("Vertical stretch can expose content behind the tab wheel",
                    View.OVER_SCROLL_NEVER, host.contentScroll.getOverScrollMode());
        });

        openTabByClick(host, LibraryTabs.SONGS);
        instrumentation.runOnMainSync(() -> assertEquals(
                "Songs list must not stretch behind the tab wheel",
                View.OVER_SCROLL_NEVER,
                findRecyclerView(host.songsView).getOverScrollMode()));
    }

    @Test
    public void songPropertiesRequireDeliberateStationaryHold() {
        MainActivityCore host = launchWithLibrary();
        openTabByClick(host, LibraryTabs.SONGS);
        InstrumentedTestSupport.waitFor("Song row was not laid out", 5000L,
                () -> findDescription(host.songsView,
                        "Открыть или включить песню UI song 0") != null);
        View song = findDescription(host.songsView,
                "Открыть или включить песню UI song 0");
        assertNotNull(song);
        instrumentation.runOnMainSync(host.overlayHost::removeAllViews);

        long down = SystemClock.uptimeMillis();
        dispatchTouch(song, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 20, 20, 0));
        SystemClock.sleep(650L);
        assertEquals("A normal touch must not open properties", 0, host.overlayHost.getChildCount());
        dispatchTouch(song, MotionEvent.obtain(down, down + 660L,
                MotionEvent.ACTION_MOVE, 80, 20, 0));
        SystemClock.sleep(600L);
        assertEquals("A swipe must cancel property opening", 0, host.overlayHost.getChildCount());
        dispatchTouch(song, MotionEvent.obtain(down, down + 1270L,
                MotionEvent.ACTION_UP, 80, 20, 0));

        down = SystemClock.uptimeMillis();
        dispatchTouch(song, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 20, 20, 0));
        SystemClock.sleep(SafeLongPress.HOLD_MS + 120L);
        InstrumentedTestSupport.waitFor("A deliberate hold did not open properties", 3000L,
                () -> host.overlayHost.getChildCount() > 0);
        dispatchTouch(song, MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, 20, 20, 0));
    }

    @Test
    public void collectionsExposePlaybackStateRotationAndFolderQueueAction() {
        MainActivityCore host = launchWithLibrary();
        instrumentation.runOnMainSync(() -> {
            host.appearanceState.circularCovers = true;
            host.appearanceState.rotateCovers = true;
            host.playbackQueueController.clear();
        });
        openTabByClick(host, LibraryTabs.FOLDERS);
        View addFolder = findDescription(host.list, "Добавить папку в очередь");
        assertNotNull(addFolder);
        instrumentation.runOnMainSync(addFolder::performClick);
        InstrumentedTestSupport.waitFor("Folder was not added to the queue", 5000L,
                () -> host.playbackUiState.queue.size() == host.libraryState.tracks.size());

        openTabByClick(host, LibraryTabs.GENRES);
        applyCollectionPlaybackState(host, host.libraryState.tracks, true);
        View groupCard = host.list.findViewById(R.id.group_card);
        assertNotNull(groupCard);
        ViewGroup groupContainer = (ViewGroup) groupCard.getParent();
        View groupMarker = groupContainer.getChildAt(1);
        assertEquals(View.VISIBLE, groupMarker.getVisibility());
        assertEquals(255, groupMarker.getBackground().getAlpha());
        assertNotNull(findStrictIcon(groupContainer, StrictIcon.PAUSE));
        RotatingCoverImageView groupCover = find(groupContainer, RotatingCoverImageView.class);
        assertNotNull(groupCover);
        assertPlayingCoverRotates("Playing group cover did not rotate", groupCover);

        instrumentation.runOnMainSync(() -> {
            Playlist playlist = new Playlist("Playback playlist");
            for (Track track : host.libraryState.tracks) playlist.uris.add(track.uri);
            host.libraryState.playlists.add(playlist);
        });
        openTabByClick(host, LibraryTabs.PLAYLISTS);
        applyCollectionPlaybackState(host, host.libraryState.tracks, true);
        View playlistCard = host.list.findViewById(R.id.playlist_card);
        assertNotNull(playlistCard);
        ViewGroup playlistContainer = (ViewGroup) playlistCard.getParent();
        assertEquals(View.VISIBLE, playlistContainer.getChildAt(1).getVisibility());
        assertNotNull(findStrictIcon(playlistContainer, StrictIcon.PAUSE));
        RotatingCoverImageView playlistCover = find(
                playlistContainer, RotatingCoverImageView.class);
        assertNotNull(playlistCover);
        assertPlayingCoverRotates("Playing playlist cover did not rotate", playlistCover);
    }

    @Test
    public void tabSwipeCancelsPendingSongProperties() {
        MainActivityCore host = launchWithLibrary();
        openTabByClick(host, LibraryTabs.SONGS);
        InstrumentedTestSupport.waitFor("Song row did not render before swipe", 5000L,
                () -> findDescription(host.songsView,
                        "Открыть или включить песню UI song 0") != null);
        View song = findDescription(host.songsView,
                "Открыть или включить песню UI song 0");
        assertNotNull(song);
        instrumentation.runOnMainSync(host.overlayHost::removeAllViews);

        int[] location = new int[2];
        instrumentation.runOnMainSync(() -> song.getLocationInWindow(location));
        float startX = location[0] + song.getWidth() * 0.35f;
        float y = location[1] + song.getHeight() * 0.5f;
        float endX = startX + host.dp(84);
        long down = SystemClock.uptimeMillis();
        dispatchActivityTouch(host, MotionEvent.obtain(
                down, down, MotionEvent.ACTION_DOWN, startX, y, 0));
        dispatchActivityTouch(host, MotionEvent.obtain(
                down, down + 40L, MotionEvent.ACTION_MOVE, endX, y, 0));

        SystemClock.sleep(SafeLongPress.HOLD_MS + 150L);
        assertEquals("A tab swipe must cancel pending song properties",
                0, host.overlayHost.getChildCount());

        dispatchActivityTouch(host, MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, y, 0));
        InstrumentedTestSupport.waitFor("Swipe did not return to Home", 5000L,
                () -> host.navigationState.tabIndex == LibraryTabs.HOME
                        && !host.navigationState.tabAnimating);
        assertEquals("Song properties appeared after the tab transition",
                0, host.overlayHost.getChildCount());
    }

    private void assertFullPlayerPages(MainActivityCore host, Track track) {
        instrumentation.runOnMainSync(() -> {
            host.overlayHost.removeAllViews();
            host.playbackUiState.queue.clear();
            host.playbackUiState.queue.add(track);
            String mediaId = MediaItemMapper.stableHash(track.uri);
            host.updatePlaybackSnapshot(new PlaybackSnapshot(
                    Collections.singletonList(mediaId), mediaId, 0, 0L, track.durationMs,
                    false, Player.STATE_READY, Player.REPEAT_MODE_OFF, false,
                    PlaybackPhase.READY, PauseReason.NONE, StopReason.NONE,
                    null, System.currentTimeMillis()));
            host.playerUiController.openFullPlayer();
        });
        InstrumentedTestSupport.waitFor("Full player did not open", 5000L,
                () -> find(host.overlayHost, ViewPager2.class) != null);
        ViewPager2 pager = find(host.overlayHost, ViewPager2.class);
        assertEquals(FullPlayerPageOrder.PLAYER, pager.getCurrentItem());
        View lyricsTile = findDescription(host.overlayHost, "Открыть текст");
        assertNotNull(lyricsTile);
        instrumentation.runOnMainSync(lyricsTile::performClick);
        InstrumentedTestSupport.waitFor("Missing lyrics state did not render", 5000L,
                () -> pager.getCurrentItem() == FullPlayerPageOrder.LYRICS
                        && containsText(host.overlayHost, "Текст не определён"));
        View queueTile = findDescription(host.overlayHost, "Открыть очередь");
        assertNotNull(queueTile);
        instrumentation.runOnMainSync(queueTile::performClick);
        InstrumentedTestSupport.waitFor("Queue tile did not open the queue", 5000L,
                () -> pager.getCurrentItem() == FullPlayerPageOrder.QUEUE
                        && pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE);
        RecyclerView queueList = findQueueList(host.overlayHost);
        assertNotNull(queueList);
        InstrumentedTestSupport.waitFor("Queue row did not render", 5000L,
                () -> queueList.getChildCount() > 0);
        int queueSize = host.playbackUiState.queue.size();
        swipeRight(queueList, queueList.getChildAt(0));
        InstrumentedTestSupport.waitFor("Right swipe did not leave the queue", 5000L,
                () -> pager.getCurrentItem() == FullPlayerPageOrder.LYRICS);
        assertEquals("Right swipe must navigate without removing a queue item",
                queueSize, host.playbackUiState.queue.size());
        View playerTile = findDescription(host.overlayHost, "Открыть плеер");
        assertNotNull(playerTile);
        instrumentation.runOnMainSync(playerTile::performClick);
        InstrumentedTestSupport.waitFor("Player tile did not return to the player", 5000L,
                () -> pager.getCurrentItem() == FullPlayerPageOrder.PLAYER);
        instrumentation.runOnMainSync(host.overlayHost::removeAllViews);
    }

    private static View findDescription(View view, String expected) {
        CharSequence description = view.getContentDescription();
        if (description != null && expected.contentEquals(description)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findDescription(group.getChildAt(index), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static RecyclerView findQueueList(View view) {
        if (view instanceof RecyclerView
                && ((RecyclerView) view).getAdapter() instanceof QueueAdapter) {
            return (RecyclerView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                RecyclerView found = findQueueList(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static RecyclerView findRecyclerView(View view) {
        if (view instanceof RecyclerView) return (RecyclerView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                RecyclerView found = findRecyclerView(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        throw new AssertionError("RecyclerView not found");
    }

    private void swipeRight(RecyclerView target, View row) {
        float startX = row.getLeft() + row.getWidth() * 0.25f;
        float endX = row.getLeft() + row.getWidth() * 0.85f;
        float y = row.getTop() + row.getHeight() * 0.5f;
        long downTime = SystemClock.uptimeMillis();
        dispatchTouch(target, MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, startX, y, 0));
        for (int step = 1; step <= 8; step++) {
            long eventTime = downTime + step * 18L;
            float x = startX + ((endX - startX) * step / 8.0f);
            dispatchTouch(target, MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0));
        }
        dispatchTouch(target, MotionEvent.obtain(
                downTime, downTime + 180L, MotionEvent.ACTION_UP, endX, y, 0));
    }

    private void swipeToPreviousTab(MainActivityCore host) {
        float startX = host.contentHost.getWidth() * 0.25f;
        float endX = host.contentHost.getWidth() * 0.85f;
        float y = host.contentHost.getHeight() * 0.5f;
        long downTime = SystemClock.uptimeMillis();
        dispatchSwipeEvent(host, MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, startX, y, 0));
        for (int step = 1; step <= 8; step++) {
            long eventTime = downTime + step * 18L;
            float x = startX + ((endX - startX) * step / 8.0f);
            dispatchSwipeEvent(host, MotionEvent.obtain(
                    downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0));
        }
        dispatchSwipeEvent(host, MotionEvent.obtain(
                downTime, downTime + 180L, MotionEvent.ACTION_UP, endX, y, 0));
    }

    private void dispatchSwipeEvent(MainActivityCore host, MotionEvent event) {
        instrumentation.runOnMainSync(() -> host.swipeController.handle(event));
        event.recycle();
    }

    private void openTabByClick(MainActivityCore host, int targetIndex) {
        instrumentation.runOnMainSync(() -> {
            for (int index = 0; index < host.tabRow.getChildCount(); index++) {
                View tab = host.tabRow.getChildAt(index);
                if (Integer.valueOf(targetIndex).equals(tab.getTag())) {
                    tab.performClick();
                    return;
                }
            }
            throw new AssertionError("Tab button not found: " + targetIndex);
        });
        InstrumentedTestSupport.waitFor("Tab did not open: " + targetIndex, 5000L,
                () -> host.navigationState.tabIndex == targetIndex
                        && !host.navigationState.tabAnimating);
    }

    private void applyPlaybackState(MainActivityCore host, Track track, boolean playing) {
        instrumentation.runOnMainSync(() -> {
            String mediaId = MediaItemMapper.stableHash(track.uri);
            host.updatePlaybackSnapshot(new PlaybackSnapshot(
                    Collections.singletonList(mediaId), mediaId, 0, 1000L,
                    track.durationMs, playing, Player.STATE_READY, Player.REPEAT_MODE_OFF,
                    false, PlaybackPhase.READY, PauseReason.NONE, StopReason.NONE,
                    null, System.currentTimeMillis()));
            host.refreshAfterTrackChange();
        });
    }

    private void dispatchTouch(View target, MotionEvent event) {
        instrumentation.runOnMainSync(() -> target.dispatchTouchEvent(event));
        event.recycle();
    }

    private void applyCollectionPlaybackState(
            MainActivityCore host, ArrayList<Track> tracks, boolean playing) {
        instrumentation.runOnMainSync(() -> {
            host.playbackUiState.queue.clear();
            host.playbackUiState.queue.addAll(tracks);
            Track current = tracks.get(0);
            ArrayList<String> mediaIds = new ArrayList<>();
            for (Track track : tracks) mediaIds.add(MediaItemMapper.stableHash(track.uri));
            String mediaId = mediaIds.get(0);
            host.updatePlaybackSnapshot(new PlaybackSnapshot(
                    mediaIds, mediaId, 0, 1000L, current.durationMs, playing,
                    Player.STATE_READY, Player.REPEAT_MODE_OFF, false,
                    PlaybackPhase.READY, PauseReason.NONE, StopReason.NONE,
                    null, System.currentTimeMillis()));
            host.refreshAfterTrackChange();
        });
    }

    private void assertPlayingCoverRotates(String message, RotatingCoverImageView cover) {
        assertTrue("Playing cover must remain attached", cover.isAttachedToWindow());
        float initialRotation = cover.getRotation();
        if (ValueAnimator.areAnimatorsEnabled()) {
            InstrumentedTestSupport.waitFor(message, 2000L,
                    () -> Math.abs(cover.getRotation() - initialRotation) > 0.1f);
            return;
        }

        instrumentation.runOnMainSync(() -> {
            cover.beginSeekSpin(0);
            cover.updateSeekSpin(1000);
            assertTrue(message, Math.abs(cover.getRotation() - initialRotation) > 0.1f);
            cover.endSeekSpin(1000, false);
        });
    }

    private void dispatchActivityTouch(MainActivityCore host, MotionEvent event) {
        instrumentation.runOnMainSync(() -> host.dispatchTouchEvent(event));
        event.recycle();
    }

    private static void assertVisibleOutline(View card) {
        Drawable background = card.getBackground();
        assertNotNull(background);
        Rect previous = background.copyBounds();
        Bitmap bitmap = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888);
        background.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        background.draw(new Canvas(bitmap));
        int edge = bitmap.getPixel(bitmap.getWidth() / 2, 0);
        int center = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
        int difference = Math.abs(Color.red(edge) - Color.red(center))
                + Math.abs(Color.green(edge) - Color.green(center))
                + Math.abs(Color.blue(edge) - Color.blue(center));
        bitmap.recycle();
        background.setBounds(previous);
        assertTrue("Card outline is not visible", difference >= 12);
    }

    private void assertGroupCardSize(MainActivityCore host, int tab, int width, int height) {
        instrumentation.runOnMainSync(() -> host.switchTabAnimated(tab, 1));
        InstrumentedTestSupport.waitFor("Group cards did not open: " + tab, 5000L,
                () -> host.navigationState.tabIndex == tab
                        && host.list.findViewById(R.id.group_card) != null
                        && host.list.findViewById(R.id.group_card).getWidth() > 0
                        && host.list.findViewById(R.id.group_card).getHeight() > 0
                        && !host.navigationState.tabAnimating);
        assertLibraryCardSize(host.list.findViewById(R.id.group_card), width, height);
        int inset = host.responsiveLayoutController.contentScrollbarClearance();
        assertEquals(inset, host.list.getPaddingLeft());
        assertEquals(inset, host.list.getPaddingRight());
    }

    private static void assertLibraryCardSize(View card, int width, int height) {
        assertNotNull(card);
        InstrumentedTestSupport.waitFor("Library card was not laid out", 5000L,
                () -> card.getWidth() > 0 && card.getHeight() > 0);
        assertEquals("Library cards must share one width", width, card.getWidth());
        assertEquals("Library cards must share one height", height, card.getHeight());
    }

    private void capture(MainActivityCore host, String name) {
        Bitmap bitmap = Bitmap.createBitmap(
                host.root.getWidth(), host.root.getHeight(), Bitmap.Config.ARGB_8888);
        instrumentation.runOnMainSync(() -> host.root.draw(new Canvas(bitmap)));
        File output = new File(host.getExternalFilesDir(null), name);
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (Exception error) {
            throw new AssertionError("Could not capture " + name, error);
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), expected)) return true;
            }
        }
        return false;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button findStrictIcon(View view, StrictIcon icon) {
        if (view instanceof Button && icon == view.getTag(R.id.strict_button_icon)) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                Button found = findStrictIcon(group.getChildAt(index), icon);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ImageView findPlaylistCover(View view) {
        if (view instanceof RotatingCoverImageView) {
            return (ImageView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                ImageView found = findPlaylistCover(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsViewClassName(View view, String simpleName) {
        if (simpleName.equals(view.getClass().getSimpleName())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsViewClassName(group.getChildAt(index), simpleName)) return true;
            }
        }
        return false;
    }

    private static <T extends TextView> T findText(View view, Class<T> type,
            String expected) {
        if (type.isInstance(view) && expected.contentEquals(((TextView) view).getText())) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findText(group.getChildAt(index), type, expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private MainActivityCore launchWithLibrary() {
        return launchWithLibrary(false);
    }

    private MainActivityCore launchWithLibrary(boolean playable) {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(LibraryDatabase.DB_NAME);
        context.getSharedPreferences("mp3_player_store", Context.MODE_PRIVATE).edit()
                .putBoolean("sqlite_migrated", true)
                .commit();
        context.getSharedPreferences("mp3_player_ui", Context.MODE_PRIVATE).edit()
                .putString("language", "ru")
                .putBoolean("animations", true)
                .putBoolean("particlesEnabled", false)
                .putInt("playlistTickerSpeed", 200)
                .commit();
        ArrayList<Track> tracks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String uri = "content://voltune.ui/track/" + index;
            if (playable) {
                File file = new File(context.getCacheDir(), "ui-song-" + index + ".mp3");
                try (InputStream source = instrumentation.getContext().getAssets()
                        .open("audio-formats/tone.mp3");
                        FileOutputStream destination = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = source.read(buffer)) != -1) destination.write(buffer, 0, read);
                } catch (Exception error) {
                    throw new AssertionError("Could not prepare playable UI fixture", error);
                }
                uri = Uri.fromFile(file).toString();
            }
            tracks.add(new Track(uri,
                    "UI song " + index,
                    playable ? "Unknown artist" : "UI artist",
                    playable ? "Unknown album" : "UI album",
                    "UI genre", 180000));
        }
        TrackStore.save(context, tracks);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(BenchmarkLibrarySeeder.EXTRA_TRACK_COUNT, tracks.size())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        activity = monitor.waitForActivityWithTimeout(15000L);
        instrumentation.removeMonitor(monitor);
        assertNotNull(activity);
        MainActivityCore host = (MainActivityCore) activity;
        InstrumentedTestSupport.waitFor("Test library did not load", 10000L,
                () -> host.libraryState.tracks.size() >= 10 && host.root != null
                        && host.librarySnapshotApplier.hasAppliedInitialSnapshot()
                        && host.list != null && host.list.getChildCount() > 0
                        && host.list.getChildAt(0).getHeight() > 0);
        return host;
    }

    private void assertOverlayOpens(MainActivityCore host, Runnable action) {
        instrumentation.runOnMainSync(() -> {
            host.overlayHost.removeAllViews();
            action.run();
        });
        InstrumentedTestSupport.waitFor("Overlay did not open", 5000L,
                () -> host.overlayHost.getChildCount() > 0);
        instrumentation.runOnMainSync(host.overlayHost::removeAllViews);
    }
}
