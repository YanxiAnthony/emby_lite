package com.example.embylite;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class RecentPlaybackSync {
    private static final Object SYNC_LOCK = new Object();
    private final EmbyClient client;
    private final String userId;
    private final RecentStore store;
    private volatile boolean cancelled;

    RecentPlaybackSync(EmbyClient client, String userId, RecentStore store) {
        this.client = client;
        this.userId = userId;
        this.store = store;
    }

    void cancel() {
        cancelled = true;
    }

    boolean synchronize() {
        // Serialize across Activity recreation as well as within one page.
        synchronized (SYNC_LOCK) {
            return synchronizeSession();
        }
    }

    private boolean synchronizeSession() {
        boolean success = true;
        for (Map.Entry<String, Long> entry : store.pending().entrySet()) {
            if (cancelled || Thread.currentThread().isInterrupted()) return false;
            try {
                long time = client.updateLastPlayed(userId, entry.getKey(), entry.getValue());
                store.acknowledge(entry.getKey(), entry.getValue(), time);
            } catch (EmbyClient.HttpException error) {
                if (error.statusCode == 404 && "GET".equals(error.method)) {
                    store.remove(entry.getKey());
                    continue;
                }
                success = false;
                if (error.statusCode == 401 || error.statusCode == 403
                        || error.statusCode >= 500 || error.statusCode == 404) break;
            } catch (IOException error) {
                success = false;
                break;
            } catch (Exception ignored) {
                success = false;
            }
        }
        if (cancelled || Thread.currentThread().isInterrupted()) return false;
        try {
            List<Movie> loaded = client.loadRecentMovies(userId, () -> !cancelled);
            if (cancelled) return false;
            store.merge(loaded);
        } catch (Exception ignored) {
            success = false;
        }
        return success;
    }
}
