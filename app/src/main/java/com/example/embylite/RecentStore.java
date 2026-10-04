package com.example.embylite;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecentStore {
    private static final String LEGACY_KEY = "recentPlayback";
    private static final int MAX_ITEMS = 100;
    private final SharedPreferences preferences;
    private final String key;

    RecentStore(SharedPreferences preferences, String server, String userId) {
        this.preferences = preferences;
        String normalized = server.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/emby")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        key = "recentPlayback." + Base64.getUrlEncoder().encodeToString(
                (normalized + "\n" + userId).getBytes(StandardCharsets.UTF_8));
        synchronized (preferences) {
            if (preferences.contains(LEGACY_KEY)) {
                JSONObject legacy = read(LEGACY_KEY);
                JSONObject recent = read(key);
                JSONObject pending = read(key + ".pending");
                for (String id : ids(legacy)) {
                    long time = legacy.optLong(id, 0);
                    if (time > recent.optLong(id, 0)) {
                        put(recent, id, time);
                        put(pending, id, time);
                    }
                }
                trim(recent);
                preferences.edit().putString(key, recent.toString())
                        .putString(key + ".pending", pending.toString())
                        .remove(LEGACY_KEY).apply();
            }
        }
    }

    void record(Movie movie) {
        synchronized (preferences) {
            long time = System.currentTimeMillis();
            JSONObject recent = read(key);
            JSONObject pending = read(key + ".pending");
            put(recent, movie.id, Math.max(time, recent.optLong(movie.id, 0)));
            put(pending, movie.id, Math.max(time, pending.optLong(movie.id, 0)));
            trim(recent);
            preferences.edit().putString(key, recent.toString())
                    .putString(key + ".pending", pending.toString()).apply();
            List<Movie> cached = cachedMovies();
            for (Movie item : cached) {
                if (item.id.equals(movie.id) && movie.dateCreatedMillis <= 0) {
                    movie.dateCreatedMillis = item.dateCreatedMillis;
                }
            }
            movie.lastPlayedMillis = Math.max(movie.lastPlayedMillis, recent.optLong(movie.id, 0));
            cached.removeIf(item -> item.id.equals(movie.id));
            cached.add(movie);
            preferences.edit().putString(key, recent.toString())
                    .putString(key + ".pending", pending.toString())
                    .putString(key + ".movies", encodeMovies(cached))
                    .apply();
        }
    }

    Map<String, Long> pending() {
        synchronized (preferences) {
            JSONObject data = read(key + ".pending");
            Map<String, Long> result = new LinkedHashMap<>();
            List<String> ordered = ids(data);
            ordered.sort(Comparator.<String>comparingLong(id -> data.optLong(id, 0)).reversed());
            for (String id : ordered) result.put(id, data.optLong(id, 0));
            return result;
        }
    }

    void acknowledge(String id, long uploadedTime, long serverTime) {
        synchronized (preferences) {
            JSONObject pending = read(key + ".pending");
            // A play recorded during upload must remain queued.
            if (pending.optLong(id, 0) <= uploadedTime) pending.remove(id);
            JSONObject recent = read(key);
            put(recent, id, Math.max(serverTime, recent.optLong(id, 0)));
            trim(recent);
            preferences.edit().putString(key, recent.toString())
                    .putString(key + ".pending", pending.toString()).apply();
        }
    }

    List<String> localIds() {
        synchronized (preferences) {
            return ids(read(key));
        }
    }

    void remove(String id) {
        synchronized (preferences) {
            JSONObject recent = read(key);
            JSONObject pending = read(key + ".pending");
            recent.remove(id);
            pending.remove(id);
            List<Movie> cached = cachedMovies();
            cached.removeIf(movie -> movie.id.equals(id));
            preferences.edit().putString(key, recent.toString())
                    .putString(key + ".pending", pending.toString())
                    .putString(key + ".movies", encodeMovies(cached)).apply();
        }
    }

    void merge(List<Movie> movies) {
        synchronized (preferences) {
            JSONObject recent = read(key);
            JSONObject pending = read(key + ".pending");
            Map<String, Movie> cached = new LinkedHashMap<>();
            for (Movie movie : cachedMovies()) cached.put(movie.id, movie);
            Map<String, Movie> merged = new LinkedHashMap<>();
            for (Movie movie : movies) {
                Movie previous = cached.get(movie.id);
                movie.lastPlayedMillis = Math.max(movie.lastPlayedMillis,
                        Math.max(recent.optLong(movie.id, 0), pending.optLong(movie.id, 0)));
                if (previous != null) {
                    movie.lastPlayedMillis = Math.max(movie.lastPlayedMillis, previous.lastPlayedMillis);
                }
                if (movie.lastPlayedMillis > recent.optLong(movie.id, 0)) {
                    put(recent, movie.id, movie.lastPlayedMillis);
                }
                merged.put(movie.id, movie);
            }
            // A local play may have been recorded after the download began.
            for (Movie movie : cached.values()) {
                if (!merged.containsKey(movie.id) && pending.has(movie.id)) {
                    merged.put(movie.id, movie);
                }
            }
            for (String id : ids(recent)) {
                if (!merged.containsKey(id) && !pending.has(id)) recent.remove(id);
            }
            trim(recent);
            preferences.edit().putString(key, recent.toString())
                    .putString(key + ".movies", encodeMovies(new ArrayList<>(merged.values())))
                    .apply();
        }
    }

    List<Movie> sortTimeline(List<Movie> allMovies, boolean forwardOrder) {
        synchronized (preferences) {
            JSONObject recent = read(key);
            JSONObject pending = read(key + ".pending");
            List<Movie> result = new ArrayList<>();
            for (Movie movie : allMovies) {
                if (!movie.collection) result.add(movie);
            }
            result.sort((first, second) -> {
                long firstPlayed = playedTime(first, recent, pending);
                long secondPlayed = playedTime(second, recent, pending);
                boolean firstHasPlayed = firstPlayed > 0;
                boolean secondHasPlayed = secondPlayed > 0;
                int comparison;
                if (firstHasPlayed != secondHasPlayed) {
                    comparison = firstHasPlayed ? -1 : 1;
                } else if (firstHasPlayed) {
                    comparison = Long.compare(secondPlayed, firstPlayed);
                } else {
                    boolean firstHasDate = first.dateCreatedMillis > 0;
                    boolean secondHasDate = second.dateCreatedMillis > 0;
                    // Unknown dates go last within the unplayed group in either direction.
                    if (firstHasDate != secondHasDate) return firstHasDate ? -1 : 1;
                    comparison = Long.compare(first.dateCreatedMillis, second.dateCreatedMillis);
                }
                if (comparison == 0) comparison = first.id.compareTo(second.id);
                return forwardOrder ? comparison : -comparison;
            });
            return result;
        }
    }

    private static long playedTime(Movie movie, JSONObject recent, JSONObject pending) {
        return Math.max(movie.lastPlayedMillis,
                Math.max(recent.optLong(movie.id, 0), pending.optLong(movie.id, 0)));
    }

    List<Movie> cachedMovies() {
        synchronized (preferences) {
            List<Movie> movies = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(preferences.getString(key + ".movies", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    Movie movie = new Movie(item.getString("id"), item.getString("name"),
                            item.optString("year"), item.optString("overview"),
                            item.optString("primary"), item.optString("thumb"),
                            item.optString("source"), item.optString("container"),
                            item.optLong("size"), false, item.optBoolean("favorite"));
                    movie.lastPlayedMillis = item.optLong("lastPlayed", 0);
                    movie.dateCreatedMillis = item.optLong("dateCreated", 0);
                    movies.add(movie);
                }
            } catch (Exception ignored) {
            }
            return movies;
        }
    }

    private static String encodeMovies(List<Movie> movies) {
        JSONArray array = new JSONArray();
        for (Movie movie : movies) {
            try {
                array.put(new JSONObject().put("id", movie.id).put("name", movie.name)
                        .put("year", movie.year).put("overview", movie.overview)
                        .put("primary", movie.primaryImageTag).put("thumb", movie.thumbImageTag)
                        .put("source", movie.mediaSourceId).put("container", movie.container)
                        .put("size", movie.size).put("favorite", movie.favorite)
                        .put("lastPlayed", movie.lastPlayedMillis)
                        .put("dateCreated", movie.dateCreatedMillis));
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    private JSONObject read(String dataKey) {
        try {
            return new JSONObject(preferences.getString(dataKey, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void put(JSONObject data, String id, long time) {
        try {
            data.put(id, time);
        } catch (Exception ignored) {
        }
    }

    private static List<String> ids(JSONObject data) {
        List<String> ids = new ArrayList<>();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) ids.add(keys.next());
        return ids;
    }

    private static void trim(JSONObject recent) {
        List<String> ids = ids(recent);
        ids.sort(Comparator.comparingLong(id -> recent.optLong(id, 0)));
        for (int i = 0; i < ids.size() - MAX_ITEMS; i++) recent.remove(ids.get(i));
    }
}
