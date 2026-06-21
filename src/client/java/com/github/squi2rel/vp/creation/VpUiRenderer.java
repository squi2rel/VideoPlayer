package com.github.squi2rel.vp.creation;

import net.minecraft.client.gui.DrawContext;

final class VpUiRenderer {
    private VpUiRenderer() {
    }

    static void drawBox(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor) {
        if (width <= 0 || height <= 0) {
            return;
        }
        context.fill(x, y, x + width, y + height, fillColor);
        context.drawStrokedRectangle(x, y, width, height, borderColor);
    }

    static int blend(int startColor, int endColor, float amount) {
        int alpha = mixChannel((startColor >>> 24) & 0xFF, (endColor >>> 24) & 0xFF, amount);
        int red = mixChannel((startColor >>> 16) & 0xFF, (endColor >>> 16) & 0xFF, amount);
        int green = mixChannel((startColor >>> 8) & 0xFF, (endColor >>> 8) & 0xFF, amount);
        int blue = mixChannel(startColor & 0xFF, endColor & 0xFF, amount);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    static int darken(int color, float amount) {
        return blend(color, 0xFF000000, amount);
    }

    static int brighten(int color, float amount) {
        return blend(color, 0xFFFFFFFF, amount);
    }

    static int withAlpha(int color, int alpha) {
        return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int mixChannel(int start, int end, float amount) {
        return Math.max(0, Math.min(255, Math.round(start + ((end - start) * amount))));
    }
}
