package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.mixin.client.FontAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DanmakuTextLayoutCache {
    private static final Map<String, CachedLayout> CACHE = new LinkedHashMap<>(2048, 0.75f, true);
    private DanmakuTextLayoutCache() {}
    static float measureWidth(String text, float scale) { return Math.max(1, Minecraft.getInstance().font.width(safe(text)) * Math.max(.01f, scale)); }
    static float measureHeight(float scale) { return Math.max(1, Minecraft.getInstance().font.lineHeight * Math.max(.01f, scale)); }
    static FormattedCharSequence orderedText(String text) { return Component.literal(safe(text)).getVisualOrderText(); }
    static void prepare(List<ClientDanmakuController.RenderableDanmaku> items) {
        if (items != null) for (var item : items) if (item != null) get(item.text());
    }
    static CachedLayout get(String text) {
        String key = safe(text);
        CachedLayout cached = CACHE.get(key);
        if (cached != null) return cached;
        Font font = Minecraft.getInstance().font;
        FormattedCharSequence ordered = orderedText(key);
        ArrayList<TextRenderable.Styled> glyphs = new ArrayList<>();
        float[] cursor = {0};
        ordered.accept((index, style, codepoint) -> {
            FontSet set = ((FontAccessor) font).videoplayer$getFontSet(style.getFont());
            var info = set.getGlyphInfo(codepoint, false);
            var glyph = set.getGlyph(codepoint);
            if (!(glyph instanceof EmptyGlyph)) {
                glyphs.add(new TextRenderable.Styled(glyph, cursor[0], 0, style.isItalic()));
                if (style.isBold()) glyphs.add(new TextRenderable.Styled(glyph, cursor[0] + info.getBoldOffset(), 0, style.isItalic()));
            }
            cursor[0] += info.getAdvance(style.isBold());
            return true;
        });
        CachedLayout layout = new CachedLayout(new PreparedText(List.copyOf(glyphs)), font.width(ordered), font.lineHeight);
        CACHE.put(key, layout);
        if (CACHE.size() > 2048) CACHE.remove(CACHE.keySet().iterator().next());
        return layout;
    }
    static void clear() { CACHE.clear(); }
    private static String safe(String text) { return text == null ? "" : text; }
    record CachedLayout(PreparedText body, int width, int height) {}
    record PreparedText(List<TextRenderable.Styled> glyphs) {
        void visit(TextRenderable.GlyphVisitor visitor) { glyphs.forEach(visitor::acceptGlyph); }
    }
}
