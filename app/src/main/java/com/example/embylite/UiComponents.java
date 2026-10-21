package com.example.embylite;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;

final class UiComponents {
    private final Context context;
    private final ThemePalette palette;

    UiComponents(Context context, ThemePalette palette) {
        this.context = context;
        this.palette = palette;
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    ScrollView header() {
        return new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                boolean compact = getResources().getConfiguration().screenHeightDp < 600
                        || getResources().getConfiguration().fontScale >= 1.3f;
                int available = MeasureSpec.getSize(height);
                if (available > 0) height = MeasureSpec.makeMeasureSpec(
                        Math.round(available * (compact ? 0.45f : 0.65f)), MeasureSpec.AT_MOST);
                super.onMeasure(width, height);
            }
        };
    }

    GradientDrawable rounded(int color, int radius, int border, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke > 0) drawable.setStroke(dp(stroke), border);
        return drawable;
    }

    void button(Button button, boolean primary, boolean chip, boolean danger) {
        button.setAllCaps(false);
        button.setTextSize(chip ? 14 : 15);
        button.setTypeface(Typeface.DEFAULT, primary || !chip ? Typeface.BOLD : Typeface.NORMAL);
        int text = danger ? palette.dangerText : primary ? Color.WHITE : palette.text;
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{palette.muted, text}));
        button.setPadding(dp(chip ? 14 : 16), dp(8), dp(chip ? 14 : 16), dp(8));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        int surface = danger ? palette.dangerSurface
                : primary ? palette.primary : palette.surfaceHigh;
        int border = danger ? palette.dangerBorder : primary ? palette.primary : palette.border;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.border),
                rounded(surface, chip ? 24 : 16, border, primary ? 0 : 1),
                rounded(Color.WHITE, chip ? 24 : 16, 0, 0)));
        button.setElevation(0);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
}
