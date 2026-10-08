package com.dumuzeyn.mp3player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class SoundClusterEngineTest {
    @Test
    public void distinctPairsAreNotForcedIntoFewerAlbums() {
        ArrayList<TrackAudioProfile> profiles = new ArrayList<>();
        Random random = new Random(42);
        for (int family = 0; family < 40; family++) {
            double[] features = new double[TrackAudioProfile.FEATURE_COUNT];
            for (int feature = 0; feature < features.length; feature++) {
                features[feature] = random.nextGaussian();
            }
            profiles.add(profile("pair-" + family + "-a", features));
            profiles.add(profile("pair-" + family + "-b", features));
        }
        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);
        assertEquals(40, groups.size());
        for (SoundGroup group : groups) assertEquals(2, group.trackIds.size());
    }

    @Test
    public void isolatedOutlierDoesNotGetAttachedToDenseFamily() {
        ArrayList<TrackAudioProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 40; index++) profiles.add(profile("dense-" + index, values(4)));
        double[] outlier = values(4);
        for (int feature = 1; feature < outlier.length; feature++) outlier[feature] += 100;
        profiles.add(profile("outlier", outlier));
        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);
        assertEquals(2, groups.size());
        for (SoundGroup group : groups) {
            if (group.trackIds.contains("outlier")) assertEquals(1, group.trackIds.size());
        }
    }

    @Test
    public void gradualChainCannotBridgeDissimilarEnds() {
        ArrayList<TrackAudioProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            double[] features = new double[TrackAudioProfile.FEATURE_COUNT];
            Arrays.fill(features, index / 100.0);
            profiles.add(profile(String.format(java.util.Locale.ROOT, "chain-%03d", index), features));
        }
        SoundFeatureNormalizer.Result normalized = SoundFeatureNormalizer.normalize(profiles);
        Map<String, double[]> vectors = new HashMap<>();
        for (int index = 0; index < profiles.size(); index++) {
            vectors.put(profiles.get(index).trackId, normalized.vectors.get(index));
        }
        for (SoundGroup group : new SoundClusterEngine().cluster(profiles)) {
            for (String first : group.trackIds) for (String second : group.trackIds) {
                assertTrue("A similarity chain merged distant tracks", SoundFeatureNormalizer.distance(
                        vectors.get(first), vectors.get(second)) <= 0.63000001);
            }
        }
    }

    @Test
    public void incrementalAssignmentRejectsDistantTrackAndIgnoresStaleCentroid() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(10);
        SoundClusterEngine engine = new SoundClusterEngine();
        ArrayList<SoundGroup> groups = engine.cluster(profiles);
        double[] candidate = profiles.get(0).features.clone();
        String expected = engine.nearestGroup(candidate, profiles, groups);
        assertFalse(expected.isEmpty());
        for (SoundGroup group : groups) Arrays.fill(group.centroid, 10000);
        assertEquals(expected, engine.nearestGroup(candidate, profiles, groups));
        Arrays.fill(candidate, 1000);
        assertEquals("", engine.nearestGroup(candidate, profiles, groups));
    }

    @Test
    public void emptyAndSmallLibrariesStayUngrouped() {
        SoundClusterEngine engine = new SoundClusterEngine();
        assertTrue(engine.cluster(new ArrayList<>()).isEmpty());
        assertTrue(engine.cluster(cloud("small", 3, 0, 1L)).isEmpty());
    }

    @Test
    public void clearlyDifferentAudioFamiliesDoNotCollapseIntoOneGroup() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(24);
        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);

        assertTrue("groups=" + groups.size(), groups.size() >= 3);
        assertTrue(largest(groups) < profiles.size() * 0.55d);
        assertTrue(distinctFamilies(groups, profiles) >= 3);
    }

    @Test
    public void oversizedHeterogeneousPopulationIsSplitByCohesion() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(20);
        profiles.addAll(cloud("neutral", 20, 4, 91L));

        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);

        assertTrue(groups.size() >= 3);
        assertTrue("largest=" + largest(groups), largest(groups) < 70);
    }

    @Test
    public void genuinelyDenseLargePopulationIsNotSplitOnlyBecauseItIsLarge() {
        ArrayList<TrackAudioProfile> profiles = new ArrayList<>();
        for (int index = 0; index < 140; index++) {
            profiles.add(profile("dense-" + index, values(4)));
        }
        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);

        assertEquals(1, groups.size());
        assertEquals(140, largest(groups));
    }

    @Test
    public void shuffledInputProducesEquivalentMembership() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(20);
        SoundClusterEngine engine = new SoundClusterEngine();
        Map<String, String> expected = membership(engine.cluster(profiles));
        for (int seed = 0; seed < 5; seed++) {
            ArrayList<TrackAudioProfile> shuffled = new ArrayList<>(profiles);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(expected, membership(engine.cluster(shuffled)));
        }
    }

    @Test
    public void bpmAndTempoConfidenceDoNotChangeMembershipOrNearestGroup() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(20);
        SoundClusterEngine engine = new SoundClusterEngine();
        ArrayList<SoundGroup> original = engine.cluster(profiles);
        ArrayList<TrackAudioProfile> tempoChanged = new ArrayList<>();
        for (int index = 0; index < profiles.size(); index++) {
            TrackAudioProfile profile = profiles.get(index);
            double[] changed = profile.features.clone();
            changed[TrackAudioProfile.BPM] = index % 2 == 0 ? 45.0d : 220.0d;
            changed[TrackAudioProfile.TEMPO_CONFIDENCE] = index % 3 == 0 ? 0.0d : 1.0d;
            tempoChanged.add(profile(profile.trackId, changed));
        }
        ArrayList<SoundGroup> changed = engine.cluster(tempoChanged);

        assertEquals(membership(original), membership(changed));
        double[] candidate = profiles.get(0).features.clone();
        String nearest = engine.nearestGroup(candidate, profiles, original);
        candidate[TrackAudioProfile.BPM] = 240.0d;
        candidate[TrackAudioProfile.TEMPO_CONFIDENCE] = 0.0d;
        assertEquals(nearest, engine.nearestGroup(candidate, profiles, original));
    }

    @Test
    public void everyUsableTrackRemainsAssigned() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(41);
        profiles.add(profile("extra", values(4)));
        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);

        assertEquals(165, membership(groups).size());
        assertTrue("groups=" + groups.size(), groups.size() > 3);
    }

    @Test(timeout = 12000L)
    public void syntheticLibrariesScaleToFiveThousandTracks() {
        SoundClusterEngine engine = new SoundClusterEngine();
        for (int size : new int[]{100, 500, 2000, 5000}) {
            ArrayList<TrackAudioProfile> profiles = new ArrayList<>();
            int each = size / 4;
            profiles.addAll(family("slow-" + size, each, 0, size));
            profiles.addAll(family("fast-" + size, each, 1, size + 1L));
            profiles.addAll(family("bass-" + size, each, 2, size + 2L));
            profiles.addAll(family("bright-" + size, size - each * 3, 3, size + 3L));
            long started = System.nanoTime();
            ArrayList<SoundGroup> groups = engine.cluster(profiles);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("similar_cluster size=" + size + " elapsedMs="
                    + elapsedMs + " groups=" + groups.size());
            assertFalse(groups.isEmpty());
            assertTrue("size=" + size + " elapsedMs=" + elapsedMs, elapsedMs < 5000L);
        }
    }

    @Test
    public void noisyCohortsDoNotDegenerateIntoMostlySingleSongAlbums() {
        ArrayList<TrackAudioProfile> profiles = fourFamilies(30);
        ArrayList<SoundGroup> groups = new SoundClusterEngine().cluster(profiles);
        long singles = groups.stream().filter(group -> group.trackIds.size() == 1).count();
        assertTrue("singletons=" + singles + " groups=" + groups.size(), singles <= profiles.size() / 8);
        assertTrue(largest(groups) < profiles.size() * 0.55);
    }

    private static ArrayList<TrackAudioProfile> fourFamilies(int each) {
        ArrayList<TrackAudioProfile> result = new ArrayList<>();
        result.addAll(family("slow", each, 0, 10L));
        result.addAll(family("fast", each, 1, 20L));
        result.addAll(family("bass", each, 2, 30L));
        result.addAll(family("bright", each, 3, 40L));
        return result;
    }

    private static ArrayList<TrackAudioProfile> family(String prefix, int count,
            int type, long seed) {
        return cloud(prefix, count, type, seed);
    }

    private static ArrayList<TrackAudioProfile> cloud(String prefix, int count,
            int type, long seed) {
        Random random = new Random(seed);
        ArrayList<TrackAudioProfile> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double[] features = values(type);
            for (int feature = 0; feature < features.length; feature++) {
                if (feature == TrackAudioProfile.TEMPO_CONFIDENCE) continue;
                double scale = feature == TrackAudioProfile.BPM ? 1.4d : 0.008d;
                features[feature] += random.nextGaussian() * scale;
            }
            result.add(profile(prefix + "-" + index, features));
        }
        return result;
    }

    private static double[] values(int type) {
        double[] value = new double[TrackAudioProfile.FEATURE_COUNT];
        value[TrackAudioProfile.BPM] = 108.0d;
        value[TrackAudioProfile.ENERGY] = 0.14d;
        value[TrackAudioProfile.LOUDNESS] = -15.0d;
        value[TrackAudioProfile.DYNAMIC_RANGE] = 9.0d;
        value[TrackAudioProfile.CENTROID] = 0.30d;
        value[TrackAudioProfile.BANDWIDTH] = 0.22d;
        value[TrackAudioProfile.ROLLOFF] = 0.48d;
        value[TrackAudioProfile.ZERO_CROSSING] = 0.08d;
        value[TrackAudioProfile.BASS] = 0.22d;
        value[TrackAudioProfile.TREBLE] = 0.10d;
        value[TrackAudioProfile.RHYTHM] = 0.10d;
        value[TrackAudioProfile.CONTRAST] = 1.8d;
        value[TrackAudioProfile.TEMPO_CONFIDENCE] = 0.90d;
        for (int index = TrackAudioProfile.TIMBRE_START; index < value.length; index++) {
            value[index] = (index - TrackAudioProfile.TIMBRE_START) * 0.04d;
        }
        if (type == 0) {
            value[TrackAudioProfile.BPM] = 78.0d;
            value[TrackAudioProfile.ENERGY] = 0.06d;
            value[TrackAudioProfile.DYNAMIC_RANGE] = 13.0d;
        } else if (type == 1) {
            value[TrackAudioProfile.BPM] = 152.0d;
            value[TrackAudioProfile.ENERGY] = 0.29d;
            value[TrackAudioProfile.RHYTHM] = 0.21d;
        } else if (type == 2) {
            value[TrackAudioProfile.BPM] = 102.0d;
            value[TrackAudioProfile.BASS] = 0.56d;
            value[TrackAudioProfile.CENTROID] = 0.19d;
        } else if (type == 3) {
            value[TrackAudioProfile.BPM] = 126.0d;
            value[TrackAudioProfile.ENERGY] = 0.23d;
            value[TrackAudioProfile.CENTROID] = 0.58d;
            value[TrackAudioProfile.TREBLE] = 0.27d;
        }
        return value;
    }

    private static TrackAudioProfile profile(String id, double[] features) {
        return new TrackAudioProfile(id, TrackAudioProfile.ANALYSIS_VERSION, 1L, 2L, id,
                SoundAnalysisState.ANALYZED, features, "", "", 0L);
    }

    private static int largest(ArrayList<SoundGroup> groups) {
        int result = 0;
        for (SoundGroup group : groups) result = Math.max(result, group.trackIds.size());
        return result;
    }

    private static int distinctFamilies(ArrayList<SoundGroup> groups,
            ArrayList<TrackAudioProfile> profiles) {
        Map<String, String> familyByTrack = new HashMap<>();
        for (TrackAudioProfile profile : profiles) {
            familyByTrack.put(profile.trackId, profile.trackId.substring(0,
                    profile.trackId.indexOf('-')));
        }
        int separated = 0;
        for (SoundGroup group : groups) {
            HashMap<String, Integer> counts = new HashMap<>();
            for (String id : group.trackIds) {
                String family = familyByTrack.get(id);
                counts.put(family, counts.containsKey(family) ? counts.get(family) + 1 : 1);
            }
            for (int count : counts.values()) {
                if (count >= group.trackIds.size() * 0.75d) {
                    separated++;
                    break;
                }
            }
        }
        return separated;
    }

    private static Map<String, String> membership(ArrayList<SoundGroup> groups) {
        HashMap<String, String> result = new HashMap<>();
        for (SoundGroup group : groups) {
            for (String trackId : group.trackIds) result.put(trackId, group.id);
        }
        return result;
    }
}
