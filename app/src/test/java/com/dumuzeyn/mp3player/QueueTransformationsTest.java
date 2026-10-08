package com.dumuzeyn.mp3player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class QueueTransformationsTest {
    @Test
    public void recentHistoryUsesNewestFirstAndNeverFillsWithUnplayedTracks() {
        Track oldest = history("old", 100);
        Track newest = history("new", 300);
        Track middle = history("middle", 200);
        Track unplayed = history("unplayed", 0);
        List<Track> source = Arrays.asList(oldest, newest, unplayed, middle, newest);
        assertEquals(Arrays.asList(newest, middle), QueueTransformations.historySubset(source, 2, true));
        assertEquals(Arrays.asList(newest, middle, oldest), QueueTransformations.historySubset(source, 99, true));
        assertEquals(Arrays.asList(oldest, newest, unplayed, middle, newest), source);
    }

    @Test
    public void oldestHistoryPrioritizesUnplayedThenLeastRecentTracks() {
        Track oldest = history("old", 100);
        Track newest = history("new", 300);
        Track unplayed = history("unplayed", 0);
        assertEquals(Arrays.asList(unplayed, oldest),
                QueueTransformations.historySubset(Arrays.asList(newest, oldest, unplayed), 2, false));
        assertEquals(Arrays.asList(unplayed),
                QueueTransformations.historySubset(Arrays.asList(newest, oldest, unplayed), 0, false));
        assertTrue(QueueTransformations.historySubset(Arrays.asList(unplayed), 10, true).isEmpty());
    }

    private static Track history(String id, long timestamp) {
        return new Track(id, "content://history/" + id, id, "Artist", "Album", "", 1000, 10, 0, "")
                .withPlaybackStats(timestamp > 0 ? 1 : 0, 0, timestamp, 0);
    }

    @Test
    public void historySamplingVariesAndStillFavorsTheChosenDirection() {
        List<Track> source = Arrays.asList(history("never", 0), history("old", 100),
                history("middle", 200), history("new", 300));
        Random recentRandom = new Random(42);
        Random oldRandom = new Random(42);
        int newest = 0, oldest = 0;
        HashSet<String> recentSets = new HashSet<>();
        for (int attempt = 0; attempt < 1000; attempt++) {
            List<Track> recent = QueueTransformations.historySample(source, 2, true, recentRandom);
            assertEquals(2, new HashSet<>(recent).size());
            assertFalse(recent.contains(source.get(0)));
            recentSets.add(recent.toString());
            if (QueueTransformations.historySample(source, 1, true, recentRandom).get(0) == source.get(3)) newest++;
            if (QueueTransformations.historySample(source, 1, false, oldRandom).get(0) == source.get(0)) oldest++;
        }
        assertTrue(recentSets.size() > 2);
        assertTrue("newest=" + newest, newest > 600);
        assertTrue("unplayed=" + oldest, oldest > 500);
    }

    @Test
    public void identicalRegenerationChangesSelectionOrOrderWheneverPossible() {
        List<String> previous = Arrays.asList("a", "b");
        assertEquals(Arrays.asList("a", "c"), QueueTransformations.freshQueue(previous, previous,
                Arrays.asList("a", "b", "c"), true));
        assertEquals(Arrays.asList("b", "a"), QueueTransformations.freshQueue(previous, previous, previous, false));
        assertEquals(Arrays.asList("b"), QueueTransformations.freshQueue(Arrays.asList("a"), Arrays.asList("a"), previous, false));
        assertEquals(Arrays.asList("a"), QueueTransformations.freshQueue(Arrays.asList("a"), Arrays.asList("a"), previous, true));
        assertEquals(Arrays.asList("a"), QueueTransformations.freshQueue(Arrays.asList("a"), Arrays.asList("a"), Arrays.asList("a"), false));
    }

    @Test
    public void moveKeepsEveryElement() {
        assertEquals(Arrays.asList("b", "c", "a"),
                QueueTransformations.move(Arrays.asList("a", "b", "c"), 0, 2));
    }

    @Test
    public void playNextMovesExistingItem() {
        assertEquals(Arrays.asList("a", "c", "b"),
                QueueTransformations.playNext(Arrays.asList("a", "b", "c"), "c", 0));
    }

    @Test
    public void randomSubsetRespectsRequestedAndAvailableCounts() {
        List<String> source = Arrays.asList("a", "b", "c", "d");
        List<String> subset = QueueTransformations.randomSubset(source, 3, new Random(7));

        assertEquals(3, subset.size());
        assertEquals(3, new HashSet<>(subset).size());
        assertFalse(subset.equals(source.subList(0, 3)));
        assertEquals(1, QueueTransformations.randomSubset(source, 0, new Random(7)).size());
        assertEquals(4, QueueTransformations.randomSubset(source, 99, new Random(7)).size());
    }

    @Test
    public void similarSubsetKeepsSeedFirstAndUsesPreferredTracksBeforeFallback() {
        List<String> source = Arrays.asList("seed", "near-a", "far", "near-b");
        List<String> subset = QueueTransformations.similarSubset(
                source,
                "seed",
                new HashSet<>(Arrays.asList("near-a", "near-b")),
                3,
                new Random(7));

        assertEquals("seed", subset.get(0));
        assertEquals(3, subset.size());
        assertEquals(new HashSet<>(Arrays.asList("seed", "near-a", "near-b")),
                new HashSet<>(subset));
    }
}
