package com.example.embylite;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

final class RecentStore {
    private static final String KEY = "recentPlayback";
    private static final int MAX_ITEMS = 100;

    private RecentStore() {
    }

    static void record(SharedPreferences preferences, String itemId) {
        try {
            JSONObject recent = read(preferences);
            recent.put(itemId, System.currentTimeMillis());
            trim(recent);
            preferences.edit().putString(KEY, recent.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static List<Movie> filterAndSort(
            SharedPreferences preferences,
            List<Movie> allMovies,
            boolean newestFirst
    ) {
        JSONObject recent = read(preferences);
        List<Movie> result = new ArrayList<>();
        for (Movie movie : allMovies) {
            if (recent.has(movie.id)) result.add(movie);
        }
        Comparator<Movie> comparator = Comparator.comparingLong(
                movie -> recent.optLong(movie.id, 0)
        );
        if (newestFirst) comparator = comparator.reversed();
        Collections.sort(result, comparator);
        return result;
    }

    private static JSONObject read(SharedPreferences preferences) {
        try {
            return new JSONObject(preferences.getString(KEY, "{}"));
        } catch (Exception error) {
            return new JSONObject();
        }
    }

    private static void trim(JSONObject recent) {
        if (recent.length() <= MAX_ITEMS) return;
        List<String> ids = new ArrayList<>();
        Iterator<String> keys = recent.keys();
        while (keys.hasNext()) ids.add(keys.next());
        Collections.sort(ids, Comparator.comparingLong(id -> recent.optLong(id, 0)));
        for (int i = 0; i < ids.size() - MAX_ITEMS; i++) {
            recent.remove(ids.get(i));
        }
    }
}
