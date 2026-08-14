package com.example.embylite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;

final class MovieAdapter extends BaseAdapter {
    private final Context context;
    private final List<Movie> movies;
    private final EmbyClient client;
    private final ExecutorService executor;
    private final ThemePalette palette;
    private final LruCache<String, Bitmap> cache;

    MovieAdapter(Context context, List<Movie> movies, EmbyClient client,
                 ExecutorService executor, ThemePalette palette) {
        this.context = context;
        this.movies = movies;
        this.client = client;
        this.executor = executor;
        this.palette = palette;
        int cacheSize = (int) (Runtime.getRuntime().maxMemory() / 1024 / 10);
        this.cache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    @Override public int getCount() { return movies.size(); }
    @Override public Movie getItem(int position) { return movies.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(6), dp(6), dp(6), dp(10));
            card.setGravity(Gravity.START);
            card.setBackground(rounded(palette.surface, 20, palette.border, 1));
            card.setElevation(dp(palette.dark ? 1 : 3));

            ImageView poster = new PosterView(context);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(rounded(palette.surfaceHigh, 16, palette.border, 1));
            poster.setClipToOutline(true);
            poster.setImageDrawable(new ColorDrawable(palette.surfaceHigh));
            card.addView(poster, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = new TextView(context);
            title.setTextColor(palette.text);
            title.setTextSize(15);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(2);
            title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            title.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams titleParams =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            titleParams.topMargin = dp(6);
            card.addView(title, titleParams);

            TextView year = new TextView(context);
            year.setTextColor(palette.muted);
            year.setTextSize(13);
            year.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            year.setPadding(dp(8), 0, dp(8), 0);
            card.addView(year, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

            holder = new Holder(poster, title, year);
            card.setTag(holder);
            convertView = card;
        } else {
            holder = (Holder) convertView.getTag();
        }

        Movie movie = getItem(position);
        holder.title.setText(movie.favorite
                ? context.getString(R.string.favorite_movie_title, movie.name)
                : movie.name);
        holder.year.setText(movie.collection ? "合集" : movie.year);
        holder.poster.setTag(movie.id);
        Bitmap bitmap = cache.get(movie.id);
        if (bitmap != null) {
            holder.poster.setImageBitmap(bitmap);
        } else {
            holder.poster.setImageDrawable(new ColorDrawable(palette.surfaceHigh));
            executor.execute(() -> {
                try {
                    Bitmap loaded = client.loadPoster(movie, 400);
                    if (loaded != null) {
                        cache.put(movie.id, loaded);
                        holder.poster.post(() -> {
                            if (movie.id.equals(holder.poster.getTag())) {
                                holder.poster.setImageBitmap(loaded);
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            });
        }
        return convertView;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private static final class Holder {
        final ImageView poster;
        final TextView title;
        final TextView year;

        Holder(ImageView poster, TextView title, TextView year) {
            this.poster = poster;
            this.title = title;
            this.year = year;
        }
    }

    private static final class PosterView extends ImageView {
        PosterView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, Math.round(width * 1.5f));
        }
    }
}
