package com.github.squi2rel.vp.danmaku;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DanmakuTextLayoutCache {
    private static final int MAX_ENTRIES = 2048;
    private static final int WHITE = 0xFFFFFFFF;
    private static final Map<String, CachedLayout> CACHE = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true);

    private DanmakuTextLayoutCache() {
    }

    static float measureWidth(String text, float scale) {
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        return Math.max(1.0f, textRenderer.getWidth(safeText(text)) * Math.max(0.01f, scale));
    }

    static float measureHeight(float scale) {
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        return Math.max(1.0f, textRenderer.fontHeight * Math.max(0.01f, scale));
    }

    static OrderedText orderedText(String text) {
        return Text.literal(safeText(text)).asOrderedText();
    }

    static void prepare(List<ClientDanmakuController.RenderableDanmaku> items) {
        if (items == null || items.isEmpty()) return;
        for (ClientDanmakuController.RenderableDanmaku item : items) {
            if (item != null) get(item.text());
        }
    }

    static CachedLayout get(String text) {
        String safe = safeText(text);
        CachedLayout cached = CACHE.get(safe);
        if (cached != null) return cached;

        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        OrderedText ordered = orderedText(safe);
        ArrayList<TextRenderer.GlyphDrawable> outlines = new ArrayList<>(8);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                if (ox == 0 && oy == 0) continue;
                outlines.add(textRenderer.prepare(ordered, ox, oy, WHITE, false, true, 0));
            }
        }
        CachedLayout created = new CachedLayout(
                List.copyOf(outlines),
                textRenderer.prepare(ordered, 0, 0, WHITE, false, true, 0),
                textRenderer.getWidth(ordered),
                textRenderer.fontHeight
        );
        CACHE.put(safe, created);
        evictOverflow();
        return created;
    }

    static void clear() {
        CACHE.clear();
    }

    private static void evictOverflow() {
        Iterator<String> iterator = CACHE.keySet().iterator();
        while (CACHE.size() > MAX_ENTRIES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String safeText(String text) {
        return text == null ? "" : text;
    }

    record CachedLayout(List<TextRenderer.GlyphDrawable> outlines,
                        TextRenderer.GlyphDrawable body,
                        int width,
                        int height) {
    }
}
