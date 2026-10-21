package com.example.embylite;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded image queue and cache for the current account's pages. */
final class PosterLoader {
    private final EmbyClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(48));
    private final Map<String, Request> pending = new HashMap<>();
    private final LruCache<String, Long> failures = new LruCache<>(100);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 10)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private volatile boolean closed;

    PosterLoader(EmbyClient client) {
        this.client = client;
        executor.setRejectedExecutionHandler((task, pool) -> {
            Runnable dropped = pool.getQueue().poll();
            if (dropped instanceof Request) {
                Request request = (Request) dropped;
                pending.remove(request.key);
            }
            if (!pool.isShutdown()) pool.execute(task);
        });
    }

    synchronized void bind(ImageView view, Movie movie, int width, int placeholderColor) {
        String key = movie.id + ":" + movie.primaryImageTag + ":" + movie.thumbImageTag + ":" + width;
        view.setTag(key);
        view.setImageResource(R.drawable.ic_poster_placeholder);
        view.getDrawable().mutate().setTint(placeholderColor);
        if (closed) return;
        Bitmap existing = cache.get(key);
        if (existing != null) {
            view.setImageBitmap(existing);
            return;
        }
        Long failedAt = failures.get(key);
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < 60_000) return;
        Request request = pending.get(key);
        if (request == null) {
            request = new Request(key, movie, width);
            pending.put(key, request);
            request.targets.add(new WeakReference<>(view));
            executor.execute(request);
        } else {
            // A recycled view may bind the same item repeatedly before its download finishes.
            request.targets.removeIf(ref -> ref.get() == null || ref.get() == view);
            request.targets.add(new WeakReference<>(view));
        }
    }

    synchronized void close() {
        closed = true;
        executor.shutdownNow();
        pending.clear();
        cache.evictAll();
        failures.evictAll();
        main.removeCallbacksAndMessages(null);
    }

    private final class Request implements Runnable {
        final String key;
        final Movie movie;
        final int width;
        final List<WeakReference<ImageView>> targets = new ArrayList<>();

        Request(String key, Movie movie, int width) {
            this.key = key;
            this.movie = movie;
            this.width = width;
        }

        @Override public void run() {
            Bitmap bitmap = null;
            try { bitmap = client.loadPoster(movie, width); } catch (Exception ignored) { }
            Bitmap result = bitmap;
            synchronized (PosterLoader.this) {
                if (closed) return;
                pending.remove(key);
                if (result != null) cache.put(key, result);
                else failures.put(key, android.os.SystemClock.elapsedRealtime());
                List<WeakReference<ImageView>> snapshot = new ArrayList<>(targets);
                main.post(() -> {
                    if (closed || result == null) return;
                    for (WeakReference<ImageView> target : snapshot) {
                        ImageView image = target.get();
                        if (image != null && key.equals(image.getTag())) image.setImageBitmap(result);
                    }
                });
            }
        }
    }
}
