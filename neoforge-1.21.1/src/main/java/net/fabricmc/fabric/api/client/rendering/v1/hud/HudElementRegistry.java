package net.fabricmc.fabric.api.client.rendering.v1.hud;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class HudElementRegistry {
    private static final List<HudElement> ELEMENTS = new ArrayList<>();

    private HudElementRegistry() {
    }

    @FunctionalInterface
    public interface HudElement {
        void render(GuiGraphics graphics, DeltaTracker deltaTracker);
    }

    public static void attachElementAfter(ResourceLocation anchor, ResourceLocation id, HudElement element) {
        ELEMENTS.add(element);
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        for (HudElement element : List.copyOf(ELEMENTS)) element.render(graphics, deltaTracker);
    }
}
