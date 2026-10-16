package com.github.squi2rel.vp.danmaku;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

interface TextRenderable {
    RenderType renderType(Font.DisplayMode mode);
    void render(Matrix4f matrix, VertexConsumer consumer, int light, boolean gui);

    interface GlyphVisitor {
        void acceptGlyph(Styled glyph);
        void acceptEffect(TextRenderable effect);
    }

    record Styled(BakedGlyph glyph, float x, float y, boolean italic) implements TextRenderable {
        public RenderType renderType(Font.DisplayMode mode) { return glyph.renderType(mode); }
        public void render(Matrix4f matrix, VertexConsumer consumer, int light, boolean gui) {
            glyph.render(italic, x, y, matrix, consumer, 1, 1, 1, 1, light);
        }
    }
}
