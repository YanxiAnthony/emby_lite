package com.example.embylite;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Iterator;

import static org.junit.Assert.*;

public class RecentPlaybackSyncTest {
    private TestHttpServer server;
    private EmbyClient client;
    private RecentStore store;
    private volatile JSONObject userData;
    private volatile JSONObject submitted;
    private volatile int status;
    private volatile int postStatus = 200;
    private volatile String listQuery;
    private volatile boolean malformedList;
    private volatile int failedPageStart = -1;
    private volatile int pageRequests;
    private volatile Runnable afterFirstPage;
    private volatile boolean repeatFirstPage;
    private final List<JSONObject> remoteMovies = new CopyOnWriteArrayList<>();

    @Before public void setUp() throws Exception {
        status = 200;
        userData = new JSONObject().put("PlaybackPositionTicks", 987654321L)
                .put("PlayCount", 4).put("Played", false).put("IsFavorite", true)
                .put("Rating", 7.5).put("LastPlayedDate", "2025-01-01T00:00:00Z");
        server = new TestHttpServer((method, uri, body) -> {
            String response;
            if (status != 200) {
                response = "Unavailable";
            } else if (method.equals("POST") && postStatus != 200) {
                return new TestHttpServer.Response(postStatus, "Unsupported");
            } else if (method.equals("POST")) {
                submitted = new JSONObject(body);
                userData = submitted;
                response = "";
            } else if (uri.getPath().endsWith("/a")) {
                response = new JSONObject().put("Id", "a").put("UserData", userData).toString();
            } else {
                listQuery = uri.getRawQuery();
                int start = 0;
                for (String part : listQuery.split("&")) {
                    if (part.startsWith("StartIndex=")) start = Integer.parseInt(part.substring(11));
                }
                pageRequests++;
                if (repeatFirstPage) start = 0;
                if (start == failedPageStart) return new TestHttpServer.Response(503, "Unavailable");
                if (malformedList) return new TestHttpServer.Response(200, "{}");
                JSONArray items = new JSONArray();
                for (int i = start; i < Math.min(start + 500, remoteMovies.size()); i++) {
                    items.put(remoteMovies.get(i));
                }
                if (store.localIds().contains("a")) {
                    items.put(new JSONObject().put("Id", "a").put("Name", "Local movie")
                            .put("Type", "Movie").put("UserData", userData));
                }
                response = new JSONObject().put("Items", items)
                        .put("TotalRecordCount", remoteMovies.size()
                                + (store.localIds().contains("a") ? 1 : 0)).toString();
                if (pageRequests == 1 && afterFirstPage != null) afterFirstPage.run();
            }
            return new TestHttpServer.Response(status, response);
        });
        String url = server.url();
        client = new EmbyClient(url, "test-device", "test-token");
        store = new RecentStore(new MemoryPreferences(), url, "user");
    }

    @After public void tearDown() throws Exception { server.close(); }

    private void recordLocal() {
        store.record(new Movie("a", "Local movie", "", "", "", "", "", "mkv", 1,
                false, true));
    }

    @Test public void uploadPreservesResumeFavoriteWatchedCountAndRating() throws Exception {
        recordLocal();
        long time = store.pending().get("a");
        JSONObject before = new JSONObject(userData.toString());
        assertTrue(new RecentPlaybackSync(client, "user", store).synchronize());
        assertNotNull(submitted);
        Iterator<String> keys = before.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("LastPlayedDate")) assertEquals(before.get(key), submitted.get(key));
        }
        assertEquals(time, Instant.parse(submitted.getString("LastPlayedDate")).toEpochMilli());
        assertTrue(store.pending().isEmpty());
    }

    @Test public void newerServerPlayIsNotOverwrittenByOlderLocalPlay() throws Exception {
        recordLocal();
        long newer = store.pending().get("a") + 10_000;
        userData.put("LastPlayedDate", Instant.ofEpochMilli(newer).toString());
        assertTrue(new RecentPlaybackSync(client, "user", store).synchronize());
        assertNull(submitted);
        assertTrue(store.pending().isEmpty());
    }

    @Test public void incompleteUserDataIsNeverSubmitted() throws Exception {
        recordLocal();
        userData.remove("PlaybackPositionTicks");
        assertFalse(new RecentPlaybackSync(client, "user", store).synchronize());
        assertNull(submitted);
        assertFalse(store.pending().isEmpty());
        assertEquals("a", store.cachedMovies().get(0).id);
    }

    @Test public void networkFailureKeepsOfflineHistoryAndCanRetry() {
        recordLocal();
        status = 503;
        RecentPlaybackSync sync = new RecentPlaybackSync(client, "user", store);
        assertFalse(sync.synchronize());
        assertTrue(store.pending().containsKey("a"));
        assertEquals(1, store.cachedMovies().size());
        status = 200;
        assertTrue(sync.synchronize());
        assertTrue(store.pending().isEmpty());
    }

    @Test public void unfinishedPlaybackFromOtherDeviceAppearsInRecentList() throws Exception {
        remoteMovies.add(new JSONObject().put("Id", "remote").put("Name", "Remote movie")
                .put("Type", "Movie").put("UserData", new JSONObject()
                        .put("Played", false).put("LastPlayedDate", "2026-01-01T12:30:00.1234567Z")));
        assertTrue(new RecentPlaybackSync(client, "user", store).synchronize());
        assertEquals("remote", store.cachedMovies().get(0).id);
        assertTrue(listQuery.contains("UserDataLastPlayedDate"));
        assertTrue(listQuery.contains("DateCreated"));
        assertFalse(listQuery.contains("Limit=100"));
        assertFalse(listQuery.contains("IsPlayed"));
    }

    @Test public void unsupportedUploadRouteDoesNotDiscardLocalHistory() {
        recordLocal();
        postStatus = 404;
        assertFalse(new RecentPlaybackSync(client, "user", store).synchronize());
        assertTrue(store.pending().containsKey("a"));
        assertEquals("a", store.cachedMovies().get(0).id);
    }

    @Test public void invalidServerDateIsNotOverwritten() throws Exception {
        recordLocal();
        userData.put("LastPlayedDate", "invalid-date");
        assertFalse(new RecentPlaybackSync(client, "user", store).synchronize());
        assertNull(submitted);
        assertTrue(store.pending().containsKey("a"));
    }

    @Test public void cancelledSessionDoesNotUploadOrDownload() {
        recordLocal();
        RecentPlaybackSync sync = new RecentPlaybackSync(client, "user", store);
        sync.cancel();
        assertFalse(sync.synchronize());
        assertNull(submitted);
        assertNull(listQuery);
        assertFalse(store.pending().isEmpty());
    }

    @Test public void malformedAndAbsentDatesDoNotBecomePlayedRecords() {
        assertEquals(0, EmbyClient.parseLastPlayed(""));
        assertEquals(0, EmbyClient.parseLastPlayed("not-a-date"));
        assertTrue(EmbyClient.parseLastPlayed("0001-01-01T00:00:00Z") <= 0);
    }
    @Test public void unplayedMediaUsesServerDateCreatedAndIsIncluded() throws Exception {
        remoteMovies.add(new JSONObject().put("Id", "unplayed").put("Name", "Unplayed")
                .put("Type", "Movie").put("DateCreated", "2026-09-01T12:00:00Z")
                .put("UserData", new JSONObject().put("Played", false)));
        assertTrue(new RecentPlaybackSync(client, "user", store).synchronize());
        Movie movie = store.cachedMovies().get(0);
        assertEquals("unplayed", movie.id);
        assertEquals(0, movie.lastPlayedMillis);
        assertEquals(Instant.parse("2026-09-01T12:00:00Z").toEpochMilli(), movie.dateCreatedMillis);
        assertTrue(store.pending().isEmpty());
    }

    @Test public void completeLibraryIsLoadedAcrossPages() throws Exception {
        for (int i = 0; i < 520; i++) {
            remoteMovies.add(new JSONObject().put("Id", "movie_" + i).put("Name", "Movie " + i)
                    .put("DateCreated", "2026-09-01T12:00:00Z").put("Type", "Movie"));
        }
        assertTrue(new RecentPlaybackSync(client, "user", store).synchronize());
        assertEquals(520, store.cachedMovies().size());
        assertEquals(2, pageRequests);
    }

    @Test public void failedLaterPageKeepsPreviousCompleteCache() throws Exception {
        for (int i = 0; i < 520; i++) {
            remoteMovies.add(new JSONObject().put("Id", "movie_" + i).put("Name", "Movie " + i)
                    .put("Type", "Movie"));
        }
        RecentPlaybackSync sync = new RecentPlaybackSync(client, "user", store);
        assertTrue(sync.synchronize());
        remoteMovies.remove(0);
        failedPageStart = 500;
        assertFalse(sync.synchronize());
        assertEquals(520, store.cachedMovies().size());
    }

    @Test public void malformedLibraryResponseDoesNotEraseCachedTimeline() throws Exception {
        remoteMovies.add(new JSONObject().put("Id", "kept").put("Name", "Kept")
                .put("Type", "Movie"));
        RecentPlaybackSync sync = new RecentPlaybackSync(client, "user", store);
        assertTrue(sync.synchronize());
        malformedList = true;
        assertFalse(sync.synchronize());
        assertEquals("kept", store.cachedMovies().get(0).id);
    }

    @Test public void cancellingDuringPagingStopsFurtherPagesAndKeepsCache() throws Exception {
        for (int i = 0; i < 520; i++) {
            remoteMovies.add(new JSONObject().put("Id", "movie_" + i).put("Name", "Movie " + i)
                    .put("Type", "Movie"));
        }
        store.merge(java.util.Collections.singletonList(new Movie("cached", "Cached", "", "",
                "", "", "", "mkv", 1, false, false)));
        RecentPlaybackSync sync = new RecentPlaybackSync(client, "user", store);
        afterFirstPage = sync::cancel;
        assertFalse(sync.synchronize());
        assertEquals(1, pageRequests);
        assertEquals("cached", store.cachedMovies().get(0).id);
    }

    @Test public void repeatedPageIsNotAcceptedAsCompleteLibrary() throws Exception {
        for (int i = 0; i < 520; i++) {
            remoteMovies.add(new JSONObject().put("Id", "movie_" + i).put("Name", "Movie " + i)
                    .put("Type", "Movie"));
        }
        store.merge(java.util.Collections.singletonList(new Movie("cached", "Cached", "", "",
                "", "", "", "mkv", 1, false, false)));
        repeatFirstPage = true;
        assertFalse(new RecentPlaybackSync(client, "user", store).synchronize());
        assertEquals("cached", store.cachedMovies().get(0).id);
    }

}
