package com.example.embylite;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class DetailPage {
    interface Actions {
        void back();
        void theme();
        void play();
        void favorite();
        void collection();
        void rename();
        void delete();
        boolean busy(String action);
    }

    private final Context context;
    private final ThemePalette palette;
    private final UiComponents ui;

    DetailPage(Context context, ThemePalette palette) {
        this.context = context;
        this.palette = palette;
        this.ui = new UiComponents(context, palette);
    }

    View create(Movie movie, String metadata, PosterLoader posters, Actions actions) {
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("detailScroll");
        scroll.setFillViewport(true);
        LinearLayout outer = column();
        outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        LinearLayout content = column();
        content.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(28));
        int width = Math.min(context.getResources().getConfiguration().screenWidthDp, 840);
        outer.addView(content, new LinearLayout.LayoutParams(-1, -2));
        outer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = Math.min(right - left, ui.dp(840));
            if (available > 0 && content.getLayoutParams().width != available) {
                content.getLayoutParams().width = available;
                content.requestLayout();
            }
        });
        scroll.addView(outer);

        LinearLayout top = row();
        top.addView(button(R.string.back, false, false, actions::back), new LinearLayout.LayoutParams(-2, -2));
        TextView heading = text(context.getString(R.string.detail_title), 16, true);
        heading.setGravity(Gravity.CENTER);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button theme = button(palette.dark ? R.string.theme_light : R.string.theme_dark,
                false, false, actions::theme);
        theme.setText(palette.dark ? "☀" : "☾");
        theme.setContentDescription(context.getString(palette.dark ? R.string.theme_light : R.string.theme_dark));
        top.addView(theme, new LinearLayout.LayoutParams(ui.dp(48), -2));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        boolean wide = width >= 600;
        boolean stacked = context.getResources().getConfiguration().fontScale >= 1.5f;
        LinearLayout hero = stacked ? column() : row();
        hero.setGravity(Gravity.TOP);
        ImageView poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(ui.rounded(palette.surfaceHigh, 16, palette.border, 1));
        poster.setClipToOutline(true);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int posterWidth = wide ? 184 : 104;
        hero.addView(poster, new LinearLayout.LayoutParams(ui.dp(posterWidth), ui.dp(posterWidth * 3 / 2)));
        posters.bind(poster, movie, wide ? 600 : 400, palette.muted);
        LinearLayout info = column();
        info.setPadding(ui.dp(stacked ? 0 : 18), ui.dp(stacked ? 12 : 2), 0, 0);
        TextView title = text(movie.name, wide ? 28 : 23, false);
        title.setTag("detailTitle");
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView meta = text(metadata, 14, true);
        meta.setPadding(0, ui.dp(12), 0, 0);
        info.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        hero.addView(info, stacked ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(hero, spaced(22));

        LinearLayout playback = column();
        Button play = button(R.string.play_now, true, false, actions::play);
        playback.addView(play, new LinearLayout.LayoutParams(-1, -2));
        Button favorite = button(movie.favorite ? R.string.favorite_saved : R.string.favorite_add,
                false, false, actions::favorite);
        favorite.setTag("detailFavorite");
        favorite.setEnabled(!actions.busy("favorite"));
        playback.addView(favorite, spaced(8));
        content.addView(playback, spaced(20));

        if (!movie.overview.trim().isEmpty()) {
            LinearLayout summary = column();
            summary.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(8));
            summary.setBackground(ui.rounded(palette.surface, 18, palette.border, 1));
            TextView summaryTitle = text(context.getString(R.string.overview_title), 16, false);
            summaryTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            summary.addView(summaryTitle, new LinearLayout.LayoutParams(-1, -2));
            TextView overview = text(movie.overview.trim(), 15, true);
            overview.setLineSpacing(ui.dp(3), 1);
            overview.setMaxLines(4);
            overview.setEllipsize(TextUtils.TruncateAt.END);
            summary.addView(overview, spaced(10));
            Button expand = button(R.string.expand_overview, false, false, () -> { });
            expand.setOnClickListener(v -> {
                boolean expanding = overview.getMaxLines() == 4;
                overview.setMaxLines(expanding ? Integer.MAX_VALUE : 4);
                overview.setEllipsize(expanding ? null : TextUtils.TruncateAt.END);
                expand.setText(expanding ? R.string.collapse_overview : R.string.expand_overview);
            });
            summary.addView(expand, spaced(8));
            content.addView(summary, spaced(20));
        }

        TextView management = text(context.getString(R.string.manage_movie), 14, true);
        content.addView(management, spaced(24));
        Button collection = button(R.string.add_collection, false, false, actions::collection);
        collection.setTag("detailCollection");
        collection.setEnabled(!actions.busy("collection"));
        content.addView(collection, spaced(8));
        Button rename = button(R.string.rename_button, false, false, actions::rename);
        rename.setTag("detailRename");
        rename.setEnabled(!actions.busy("rename"));
        content.addView(rename, spaced(8));
        Button delete = button(R.string.delete_movie, false, true, actions::delete);
        delete.setTag("detailDelete");
        delete.setEnabled(!actions.busy("delete"));
        content.addView(delete, spaced(20));
        return scroll;
    }

    private Button button(int text, boolean primary, boolean danger, Runnable action) {
        Button button = new Button(context);
        button.setText(text);
        ui.button(button, primary, false, danger);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView text(String value, int size, boolean muted) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(muted ? palette.muted : palette.text);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(top);
        return params;
    }
}
