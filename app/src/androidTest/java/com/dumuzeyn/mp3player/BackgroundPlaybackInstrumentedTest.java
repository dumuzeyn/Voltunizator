package com.dumuzeyn.mp3player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@UnstableApi
@RunWith(AndroidJUnit4.class)
public class BackgroundPlaybackInstrumentedTest {
    private static final long TRANSITION_TIMEOUT_MS = 30000L;

    private Context context;
    private Instrumentation instrumentation;
    private File waveFile;
    private File secondWaveFile;
    private Track firstTrack;
    private Track secondTrack;
    private Activity activity;
    private MediaController controller;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context.getSharedPreferences("mp3_player_ui", Context.MODE_PRIVATE).edit()
                .putBoolean("particlesEnabled", false)
                .putBoolean("animations", false)
                .putBoolean("showArtistName", true)
                .remove(InterTrackDelayPolicy.SECONDS)
                .commit();
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentedTestSupport.runShellCommand(instrumentation,
                    "pm grant " + context.getPackageName() + " "
                            + Manifest.permission.POST_NOTIFICATIONS);
        }
        controller = new MediaController.Builder(context, new SessionToken(context,
                new ComponentName(context, Media3PlayerService.class)))
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync().get(15, TimeUnit.SECONDS);
        stopPlayback();
        waveFile = InstrumentedTestSupport.createTestWave(
                context, "instrumented-playback-1.wav", 6);
        secondWaveFile = InstrumentedTestSupport.createTestWave(
                context, "instrumented-playback-2.wav", 3);
        firstTrack = new Track(Uri.fromFile(waveFile).toString(), "Instrumentation tone 1",
                "Voltune tests", "Compatibility", "Test", 6000);
        secondTrack = new Track(Uri.fromFile(secondWaveFile).toString(),
                "Instrumentation tone 2", "Voltune tests", "Compatibility", "Test", 3000);
        TrackStore.save(context, Arrays.asList(firstTrack, secondTrack));
    }

    @After
    public void tearDown() {
        stopPlayback();
        context.getSharedPreferences(InterTrackDelayPolicy.PREFS, 0).edit()
                .remove(InterTrackDelayPolicy.SECONDS).commit();
        try {
            InstrumentedTestSupport.runShellCommand(instrumentation, "input keyevent 224");
            InstrumentedTestSupport.runShellCommand(instrumentation, "wm dismiss-keyguard");
        } catch (Exception error) { throw new AssertionError(error); }
        TrackStore.save(context, Collections.<Track>emptyList());
        if (activity != null) {
            InstrumentedTestSupport.finishActivity(instrumentation, activity);
        }
        if (controller != null) {
            controllerAction(controller::release);
        }
        if (waveFile != null) {
            waveFile.delete();
        }
        if (secondWaveFile != null) {
            secondWaveFile.delete();
        }
    }

    @Test
    public void hidingArtistPreservesPlaybackAndQueue() {
        activity = launchMainActivity();
        startQueue(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_ONE);
        waitForPlayingUri("First track did not start", firstTrack.uri);
        controllerAction(() -> controller.seekTo(1000));
        long positionBefore = controllerValue(controller::getCurrentPosition);
        MainActivityCore host = (MainActivityCore) activity;

        instrumentation.runOnMainSync(() -> {
            host.appearanceState.showArtistName = false;
            host.saveUiState();
            host.playbackController.refreshArtistVisibility();
            host.playerUiController.syncPlaybackUi();
        });
        InstrumentedTestSupport.waitFor("Artist remained in active Media3 item", 5000L,
                () -> controllerValue(() -> {
                    CharSequence artist = controller.getCurrentMediaItem().mediaMetadata.artist;
                    return artist == null || artist.length() == 0;
                }));
        assertEquals(2, (int) controllerValue(controller::getMediaItemCount));
        assertEquals(Player.REPEAT_MODE_ONE, (int) controllerValue(controller::getRepeatMode));
        assertTrue(controllerValue(controller::isPlaying));
        assertTrue(controllerValue(controller::getCurrentPosition) >= positionBefore - 500);
        assertEquals(android.view.View.GONE, host.miniSub.getVisibility());
        NotificationManager notifications = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        InstrumentedTestSupport.waitFor("Notification still showed an artist", 5000L,
                () -> {
                    for (android.service.notification.StatusBarNotification entry
                            : notifications.getActiveNotifications()) {
                        if (entry.getId() != 7) continue;
                        Bundle extras = entry.getNotification().extras;
                        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                        if ("Instrumentation tone 1".contentEquals(title)
                                && (text == null || text.length() == 0)) return true;
                    }
                    return false;
                });

        instrumentation.runOnMainSync(() -> {
            host.appearanceState.showArtistName = true;
            host.saveUiState();
            host.playbackController.refreshArtistVisibility();
        });
        InstrumentedTestSupport.waitFor("Artist did not return to Media3 item", 5000L,
                () -> controllerValue(() -> "Voltune tests".contentEquals(
                        controller.getCurrentMediaItem().mediaMetadata.artist)));

        MediaItemMapper hiddenMapper = new MediaItemMapper(() -> false);
        assertEquals("", hiddenMapper.toMediaItem(firstTrack).mediaMetadata.artist);
        assertEquals("", hiddenMapper.toLibraryItem(firstTrack,
                context.getPackageName() + ".artwork").mediaMetadata.artist);
    }

    @Test
    public void playbackContinuesAfterActivityIsClosedAndCanBePaused() {
        activity = launchMainActivity();
        startQueue(Collections.singletonList(firstTrack), Player.REPEAT_MODE_ONE);
        waitForPlayingUri("Media3 did not start playback", firstTrack.uri);

        InstrumentedTestSupport.finishActivity(instrumentation, activity);
        activity = null;
        InstrumentedTestSupport.waitFor("Playback stopped when Activity closed",
                TRANSITION_TIMEOUT_MS, () -> controllerValue(controller::isPlaying));
        InstrumentedTestSupport.waitFor("Playback position did not advance",
                TRANSITION_TIMEOUT_MS, () -> controllerValue(
                        () -> controller.getCurrentPosition()) > 0);

        controllerAction(controller::pause);
        InstrumentedTestSupport.waitFor("Media3 did not pause", 5000L,
                () -> !controllerValue(controller::isPlaying));
        assertFalse(controllerValue(controller::isPlaying));
        assertTrue(controllerValue(controller::getMediaItemCount) > 0);
    }

    @Test
    public void repeatingPlaylistWithSleepTimerKeepsPlayingAfterTaskIsRemoved() {
        activity = launchMainActivity();
        startQueue(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_ALL);
        waitForPlayingUri("First playlist track did not start", firstTrack.uri);
        Bundle timer = new Bundle();
        timer.putLong(Media3Commands.ARG_TIMER_MS, 20000L);
        controllerAction(() -> controller.sendCustomCommand(
                Media3Commands.TIMER_START_COMMAND, timer));

        InstrumentedTestSupport.finishAndRemoveTask(instrumentation, activity);
        activity = null;
        waitForPlayingUri("Playlist did not advance after task removal", secondTrack.uri);
        waitForPlayingUri("Repeating playlist did not wrap", firstTrack.uri);
        assertTrue(com.dumuzeyn.mp3player.playback.service.PlaybackSleepTimer
                .readEndsAt(context) > System.currentTimeMillis());
    }

    @Test
    public void repeatAllKeepsCyclingWhileActivityIsInBackground() {
        activity = launchMainActivity();
        startQueue(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_ALL);
        waitForPlayingUri("First repeat track did not start", firstTrack.uri);
        instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
        waitForPlayingUri("Queue did not reach second track", secondTrack.uri);
        waitForPlayingUri("Queue did not wrap to first track", firstTrack.uri);
        assertTrue(controllerValue(controller::isPlaying));
        assertEquals(Player.REPEAT_MODE_ALL,
                (int) controllerValue(controller::getRepeatMode));
    }

    @Test
    public void controllerCanReconnectWithoutLosingQueueOrPosition() throws Exception {
        activity = launchMainActivity();
        startQueue(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_ALL);
        waitForPlayingUri("First reconnect track did not start", firstTrack.uri);
        InstrumentedTestSupport.waitFor("Playback position did not advance before reconnect",
                5000L, () -> controllerValue(() -> controller.getCurrentPosition()) > 250L);
        long positionBeforeReconnect = controllerValue(controller::getCurrentPosition);
        controllerAction(controller::release);
        controller = new MediaController.Builder(context, new SessionToken(context,
                new ComponentName(context, Media3PlayerService.class)))
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync().get(15, TimeUnit.SECONDS);
        assertEquals(2, (int) controllerValue(controller::getMediaItemCount));
        assertEquals(Player.REPEAT_MODE_ALL,
                (int) controllerValue(controller::getRepeatMode));
        assertTrue(controllerValue(controller::getCurrentPosition) >= positionBeforeReconnect);
        assertTrue(controllerValue(controller::isPlaying));
    }

    @Test
    public void unavailableUriDoesNotLoopForeverAndAdvancesToValidTrack() {
        Track unavailable = new Track("content://voltune.invalid/missing.mp3", "Missing",
                "Voltune tests", "Compatibility", "Test", 1000);
        startQueue(Arrays.asList(unavailable, secondTrack), Player.REPEAT_MODE_OFF);
        waitForPlayingUri("Media3 did not recover from unavailable URI", secondTrack.uri);
        assertTrue(controllerValue(controller::isPlaying));
    }

    @Test
    public void closingActivityWithFullPlayerDoesNotUseClosedCoverLoader() {
        activity = launchMainActivity();
        MainActivityCore host = (MainActivityCore) activity;
        instrumentation.runOnMainSync(() -> {
            setUiSnapshot(host, 0, false);
            host.playerUiController.openFullPlayer();
            activity.finish();
        });
        activity = null;
        SystemClock.sleep(1200L);
        assertEquals(0, (int) controllerValue(controller::getMediaItemCount));
    }

    @Test
    public void rotatingCoverResetsAndRestartsWhenTrackChanges() {
        activity = launchMainActivity();
        MainActivityCore host = (MainActivityCore) activity;
        RotatingCoverImageView[] holder = new RotatingCoverImageView[1];
        instrumentation.runOnMainSync(() -> {
            host.appearanceState.animations = true;
            host.appearanceState.circularCovers = true;
            host.appearanceState.rotateCovers = true;
            setUiSnapshot(host, 0, true);
            holder[0] = new RotatingCoverImageView(host);
            host.root.addView(holder[0], new android.widget.FrameLayout.LayoutParams(200, 200));
            holder[0].bindTrack(host.libraryState.tracks.get(0));
        });
        InstrumentedTestSupport.waitFor("First cover did not rotate", 3000L,
                () -> holder[0].getRotation() > 1.0f);
        float[] reset = new float[1];
        instrumentation.runOnMainSync(() -> {
            setUiSnapshot(host, 1, true);
            holder[0].bindTrack(host.libraryState.tracks.get(1));
            reset[0] = holder[0].getRotation();
        });
        assertTrue(Math.abs(reset[0]) < 1.0f);
        InstrumentedTestSupport.waitFor("Second cover did not rotate", 3000L,
                () -> holder[0].getRotation() > 1.0f);
    }

    private void startQueue(List<Track> tracks, int repeatMode) {
        MediaItemMapper mapper = new MediaItemMapper();
        ArrayList<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            items.add(mapper.toMediaItem(track));
        }
        controllerAction(() -> {
            controller.setMediaItems(items);
            controller.setShuffleModeEnabled(false);
            controller.setRepeatMode(repeatMode);
            controller.prepare();
            controller.play();
        });
    }

    @Test
    public void gapSettingDefaultsToZeroAndAcceptsFiveMinutes() {
        activity = launchMainActivity();
        MainActivityCore host = (MainActivityCore) activity;
        instrumentation.runOnMainSync(() -> {
            InterTrackDelaySettingsController settings = new InterTrackDelaySettingsController(host);
            settings.openDialog();
            android.widget.SeekBar slider = findGapSlider(host.overlayHost);
            assertNotNull(slider);
            assertEquals(0, slider.getProgress());
            assertEquals(300, slider.getMax());
            Bundle arguments = new Bundle();
            arguments.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 300);
            assertTrue(slider.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments));
            assertEquals(300, context.getSharedPreferences(InterTrackDelayPolicy.PREFS, 0)
                    .getInt(InterTrackDelayPolicy.SECONDS, -1));
            assertTrue(settings.label().endsWith("5:00"));
        });
    }

    private android.widget.SeekBar findGapSlider(android.view.View view) {
        if (view instanceof android.widget.SeekBar) return (android.widget.SeekBar) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.SeekBar result = findGapSlider(group.getChildAt(index));
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test
    public void automaticGapResumesWithScreenOffAndKeepsSessionActive() throws Exception {
        beginGap(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_OFF);
        long gapStarted = SystemClock.elapsedRealtime();
        assertTrue(new com.dumuzeyn.mp3player.data.playback.PlaybackStateManager(context).load().playing);
        InstrumentedTestSupport.runShellCommand(instrumentation, "input keyevent 223");
        SystemClock.sleep(1000);
        assertFalse(controllerValue(controller::isPlaying));
        assertEquals(0, (int) controllerValue(controller::getCurrentMediaItemIndex));
        waitForPlayingUri("Screen-off gap did not advance", secondTrack.uri);
        assertTrue("Gap was shorter than configured", SystemClock.elapsedRealtime() - gapStarted >= 2600);
    }

    @Test
    public void userPauseDuringGapCancelsTheScheduledResume() {
        beginGap(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_OFF);
        controllerAction(controller::pause);
        SystemClock.sleep(3700);
        assertFalse(controllerValue(controller::getPlayWhenReady));
        assertEquals(0, (int) controllerValue(controller::getCurrentMediaItemIndex));
    }

    @Test
    public void sleepTimerDuringGapCannotRestartPlaybackLater() {
        beginGap(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_OFF);
        Bundle timer = new Bundle();
        timer.putLong(Media3Commands.ARG_TIMER_MS, 1000L);
        controllerAction(() -> controller.sendCustomCommand(Media3Commands.TIMER_START_COMMAND, timer));
        SystemClock.sleep(3700);
        assertFalse(controllerValue(controller::getPlayWhenReady));
        assertEquals(Player.STATE_IDLE, (int) controllerValue(controller::getPlaybackState));
    }

    @Test
    public void repeatOneObservesGapAndRestartsTheSameSong() {
        beginGap(Collections.singletonList(firstTrack), Player.REPEAT_MODE_ONE);
        InstrumentedTestSupport.waitFor("Repeat-one gap did not resume", 8000L, () ->
                controllerValue(() -> controller.isPlaying() && controller.getCurrentPosition() < 2000));
        assertEquals(0, (int) controllerValue(controller::getCurrentMediaItemIndex));
    }

    @Test
    public void manualNextDuringGapOverridesTheWait() {
        beginGap(Arrays.asList(firstTrack, secondTrack), Player.REPEAT_MODE_OFF);
        controllerAction(() -> { controller.seekToNextMediaItem(); controller.play(); });
        waitForPlayingUri("Manual next remained delayed", secondTrack.uri);
        controllerAction(controller::pause);
        SystemClock.sleep(3500);
        assertFalse(controllerValue(controller::isPlaying));
        assertEquals(1, (int) controllerValue(controller::getCurrentMediaItemIndex));
    }

    private void beginGap(List<Track> tracks, int repeatMode) {
        activity = launchMainActivity();
        context.getSharedPreferences(InterTrackDelayPolicy.PREFS, 0).edit()
                .putInt(InterTrackDelayPolicy.SECONDS, 3).commit();
        startQueue(tracks, repeatMode);
        waitForPlayingUri("Gap setup did not start", firstTrack.uri);
        controllerAction(() -> controller.seekTo(5100));
        InstrumentedTestSupport.waitFor("Automatic boundary did not pause for the gap", 10000L, () ->
                controllerValue(() -> !controller.getPlayWhenReady() && controller.getCurrentMediaItemIndex() == 0
                        && controller.getPlaybackState() == Player.STATE_READY));
    }

    private static void setUiSnapshot(MainActivityCore host, int trackIndex, boolean playing) {
        Track track = host.libraryState.tracks.get(trackIndex);
        String mediaId = MediaItemMapper.stableHash(track.uri);
        host.updatePlaybackSnapshot(new PlaybackSnapshot(
                Collections.singletonList(mediaId), mediaId, 0, 0L, track.durationMs,
                playing, Player.STATE_READY, Player.REPEAT_MODE_OFF, false,
                PlaybackPhase.READY, PauseReason.NONE, StopReason.NONE,
                null, System.currentTimeMillis()));
    }

    private Activity launchMainActivity() {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Activity launched = monitor.waitForActivityWithTimeout(15000L);
        instrumentation.removeMonitor(monitor);
        assertNotNull("MainActivity did not start", launched);
        InstrumentedTestSupport.waitFor("MainActivity did not finish layout", 10000L,
                () -> ((MainActivityCore) launched).root != null
                        && ((MainActivityCore) launched).root.getWidth() > 0);
        return launched;
    }

    private void waitForPlayingUri(String message, String uri) {
        InstrumentedTestSupport.waitFor(message, TRANSITION_TIMEOUT_MS, () -> {
            return controllerValue(() -> {
                MediaItem current = controller.getCurrentMediaItem();
                return controller.isPlaying() && current != null
                        && current.localConfiguration != null
                        && uri.equals(current.localConfiguration.uri.toString());
            });
        });
    }

    private void stopPlayback() {
        if (controller == null) {
            return;
        }
        controllerAction(() -> {
            controller.sendCustomCommand(Media3Commands.TIMER_CANCEL_COMMAND, Bundle.EMPTY);
            controller.stop();
            controller.clearMediaItems();
            controller.sendCustomCommand(Media3Commands.CLEAR_QUEUE_COMMAND, Bundle.EMPTY);
        });
        InstrumentedTestSupport.waitFor("Media3 session did not clear", 3000L,
                () -> controllerValue(controller::getMediaItemCount) == 0);
    }

    private void controllerAction(Runnable action) {
        instrumentation.runOnMainSync(action);
    }

    private <T> T controllerValue(Callable<T> query) {
        FutureTask<T> task = new FutureTask<>(query);
        instrumentation.runOnMainSync(task);
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError("MediaController query failed", error);
        }
    }
}
