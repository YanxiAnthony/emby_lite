package com.example.embylite;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

final class MovieAdapter extends BaseAdapter {
    private final Context context;
    private final List<Movie> movies;
    private final PosterLoader posters;
    private final boolean timeline;
    private final ThemePalette palette;

    MovieAdapter(Context context, List<Movie> movies, PosterLoader posters,
                 ThemePalette palette, boolean timeline) {
        this.context = context;
        this.movies = movies;
        this.posters = posters;
        this.timeline = timeline;
        this.palette = palette;
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
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMinLines(2);
            title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            title.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams titleParams =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = dp(6);
            card.addView(title, titleParams);

            TextView year = new TextView(context);
            year.setTextColor(palette.muted);
            year.setTextSize(13);
            year.setMaxLines(2);
            year.setEllipsize(android.text.TextUtils.TruncateAt.END);
            year.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            year.setPadding(dp(8), 0, dp(8), 0);
            card.addView(year, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        holder.year.setText(movie.collection ? context.getString(R.string.collections)
                : timeline ? (movie.lastPlayedMillis > 0
                ? context.getString(R.string.last_opened, android.text.format.DateUtils
                        .getRelativeTimeSpanString(movie.lastPlayedMillis, System.currentTimeMillis(),
                                android.text.format.DateUtils.MINUTE_IN_MILLIS))
                : context.getString(R.string.not_played)) : movie.year);
        holder.poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        posters.bind(holder.poster, movie, 400, palette.muted);
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
