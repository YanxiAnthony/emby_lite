package com.example.embylite;

import android.content.Context;
import android.graphics.Color;

final class ThemePalette {
    final boolean dark;
    final int background;
    final int surface;
    final int surfaceHigh;
    final int primary;
    final int primaryLight;
    final int onPrimary;
    final int text;
    final int muted;
    final int border;
    final int dangerSurface;
    final int dangerText;
    final int dangerBorder;

    private ThemePalette(boolean dark, int background, int surface, int surfaceHigh,
                         int primary, int primaryLight, int onPrimary, int text, int muted, int border,
                         int dangerSurface, int dangerText, int dangerBorder) {
        this.dark = dark;
        this.background = background;
        this.surface = surface;
        this.surfaceHigh = surfaceHigh;
        this.primary = primary;
        this.primaryLight = primaryLight;
        this.onPrimary = onPrimary;
        this.text = text;
        this.muted = muted;
        this.border = border;
        this.dangerSurface = dangerSurface;
        this.dangerText = dangerText;
        this.dangerBorder = dangerBorder;
    }

    static ThemePalette create(Context context, boolean dark) {
        int primary = context.getColor(R.color.theme_primary);
        int onPrimary = context.getColor(R.color.theme_on_primary);
        if (dark) {
            return new ThemePalette(
                    true,
                    Color.rgb(10, 11, 14),
                    Color.rgb(20, 22, 27),
                    Color.rgb(29, 32, 39),
                    primary,
                    primary,
                    onPrimary,
                    Color.rgb(247, 247, 249),
                    Color.rgb(155, 160, 171),
                    Color.rgb(45, 49, 58),
                    Color.rgb(55, 25, 30),
                    Color.rgb(255, 185, 190),
                    Color.rgb(137, 53, 62)
            );
        }
        return new ThemePalette(
                false,
                Color.rgb(246, 247, 249),
                Color.WHITE,
                Color.rgb(237, 239, 243),
                primary,
                context.getColor(R.color.theme_primary_text_light),
                onPrimary,
                Color.rgb(24, 25, 29),
                Color.rgb(100, 105, 116),
                Color.rgb(224, 226, 232),
                Color.rgb(255, 238, 239),
                Color.rgb(174, 43, 54),
                Color.rgb(238, 183, 188)
        );
    }
}
