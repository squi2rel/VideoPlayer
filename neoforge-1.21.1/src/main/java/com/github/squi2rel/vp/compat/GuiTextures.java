package com.github.squi2rel.vp.compat;

import com.github.squi2rel.vp.ScreenRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class GuiTextures {
    private GuiTextures() {}
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int left, int top, int right, int bottom,
            float u1, float u2, float v1, float v2) {
        ScreenRenderer.drawGuiTexturedQuad(graphics, texture, left, top, right, bottom, u1, u2, v1, v2, 0xFFFFFFFF);
    }
}
