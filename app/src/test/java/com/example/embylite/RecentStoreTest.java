package com.example.embylite;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class RecentStoreTest {
    private static Movie movie(String id, long time) {
        Movie movie = new Movie(id, "Movie " + id, "2026", "Overview", "p", "t", "source",
                "mkv", 1024, false, true);
        movie.lastPlayedMillis = time;
        return movie;
    }

    @Test public void recordedPlayIsCachedAndSurvivesRestart() {
        MemoryPreferences prefs = new MemoryPreferences();
        RecentStore store = new RecentStore(prefs, "http://server", "user");
        store.record(movie("a", 0));
        RecentStore restarted = new RecentStore(prefs, "http://server/emby/", "user");
        assertEquals("a", restarted.cachedMovies().get(0).id);
        assertEquals("source", restarted.cachedMovies().get(0).mediaSourceId);
        assertTrue(restarted.cachedMovies().get(0).favorite);
        assertTrue(restarted.pending().containsKey("a"));
    }

    @Test public void olderServerSnapshotDoesNotOverwriteLocalPlay() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.record(movie("a", 0));
        store.merge(Arrays.asList(movie("a", 1), movie("b", 2)));
        assertEquals("a", store.sortTimeline(store.cachedMovies(), true).get(0).id);
        assertEquals("b", store.sortTimeline(store.cachedMovies(), false).get(0).id);
        assertTrue(store.pending().containsKey("a"));
    }

    @Test public void oldUploadAcknowledgementDoesNotLoseNewPlay() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.record(movie("a", 0));
        long time = store.pending().get("a");
        store.acknowledge("a", time - 1000, time - 1000);
        assertEquals(Long.valueOf(time), store.pending().get("a"));
        store.acknowledge("a", time, time);
        assertTrue(store.pending().isEmpty());
    }

    @Test public void serverAndAccountRecordsAreIsolated() {
        MemoryPreferences prefs = new MemoryPreferences();
        new RecentStore(prefs, "http://one", "user").record(movie("a", 0));
        assertTrue(new RecentStore(prefs, "http://two", "user").cachedMovies().isEmpty());
        assertTrue(new RecentStore(prefs, "http://one", "other").pending().isEmpty());
    }

    @Test public void legacyHistoryIsMigratedOnlyOnceAndQueued() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.edit().putString("recentPlayback", "{\"old\":100}").apply();
        RecentStore store = new RecentStore(prefs, "http://server", "user");
        assertEquals(Long.valueOf(100), store.pending().get("old"));
        assertFalse(prefs.contains("recentPlayback"));
        assertTrue(new RecentStore(prefs, "http://server", "other").localIds().isEmpty());
    }

    @Test public void playedItemsBeyondHundredRemainPlayedAndVisible() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        List<Movie> movies = new ArrayList<>();
        for (int i = 1; i <= 120; i++) movies.add(movie(String.valueOf(i), i));
        store.merge(movies);
        assertEquals(120, store.cachedMovies().size());
        assertEquals("120", store.sortTimeline(store.cachedMovies(), true).get(0).id);
        assertEquals("1", store.sortTimeline(store.cachedMovies(), false).get(0).id);
    }

    @Test public void playArrivingDuringDownloadIsKeptInCache() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.record(movie("local", 0));
        store.merge(Collections.singletonList(movie("remote", 100)));
        assertEquals(2, store.cachedMovies().size());
        assertEquals("local", store.sortTimeline(store.cachedMovies(), true).get(0).id);
    }

    @Test public void unavailableMediaIsRemovedWithoutLosingOtherPendingItems() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.record(movie("a", 0));
        store.record(movie("b", 0));
        store.remove("a");
        assertFalse(store.pending().containsKey("a"));
        assertEquals("b", store.cachedMovies().get(0).id);
    }
    private static Movie unplayed(String id, long createdTime) {
        Movie movie = movie(id, 0);
        movie.dateCreatedMillis = createdTime;
        return movie;
    }

    private static List<String> orderedIds(RecentStore store, boolean forward) {
        List<String> ids = new ArrayList<>();
        for (Movie movie : store.sortTimeline(store.cachedMovies(), forward)) ids.add(movie.id);
        return ids;
    }

    @Test public void forwardOrderIsRecentPlayedThenOldestUnplayed() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.merge(Arrays.asList(unplayed("new_unplayed", 40), movie("old_played", 10),
                unplayed("old_unplayed", 5), movie("new_played", 30)));
        assertEquals(Arrays.asList("new_played", "old_played", "old_unplayed", "new_unplayed"),
                orderedIds(store, true));
    }

    @Test public void reverseOrderIsNewestUnplayedThenOldestPlayed() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.merge(Arrays.asList(unplayed("new_unplayed", 40), movie("old_played", 10),
                unplayed("old_unplayed", 5), movie("new_played", 30)));
        assertEquals(Arrays.asList("new_unplayed", "old_unplayed", "old_played", "new_played"),
                orderedIds(store, false));
    }

    @Test public void neverPlayedTimelineIsRetainedAcrossRestart() {
        MemoryPreferences prefs = new MemoryPreferences();
        RecentStore store = new RecentStore(prefs, "http://server", "user");
        store.merge(Arrays.asList(unplayed("new", 200), unplayed("old", 100)));
        RecentStore restarted = new RecentStore(prefs, "http://server", "user");
        assertEquals(Arrays.asList("old", "new"), orderedIds(restarted, true));
        assertEquals(Arrays.asList("new", "old"), orderedIds(restarted, false));
        assertTrue(restarted.pending().isEmpty());
    }

    @Test public void playingUnplayedMovieMovesItToPlayedGroupImmediately() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        Movie fresh = unplayed("fresh", 300);
        store.merge(Arrays.asList(fresh, unplayed("other", 200), movie("played", 100)));
        store.record(fresh);
        assertEquals(Arrays.asList("fresh", "played", "other"), orderedIds(store, true));
        assertEquals(Arrays.asList("other", "played", "fresh"), orderedIds(store, false));
        assertTrue(store.pending().containsKey("fresh"));
    }

    @Test public void missingDatesRemainLastWithinUnplayedGroupInBothDirections() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.merge(Arrays.asList(unplayed("unknown", 0), unplayed("known", 100)));
        assertEquals(Arrays.asList("known", "unknown"), orderedIds(store, true));
        assertEquals(Arrays.asList("known", "unknown"), orderedIds(store, false));
    }

    @Test public void completeSnapshotRemovesDeletedMediaWithoutPendingPlayback() {
        RecentStore store = new RecentStore(new MemoryPreferences(), "http://server", "user");
        store.merge(Arrays.asList(unplayed("deleted", 100), movie("kept", 100)));
        store.merge(Collections.singletonList(movie("kept", 100)));
        assertEquals(Collections.singletonList("kept"), orderedIds(store, true));
    }

}
