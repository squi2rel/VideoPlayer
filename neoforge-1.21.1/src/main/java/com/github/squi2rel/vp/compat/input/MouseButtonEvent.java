package com.github.squi2rel.vp.compat.input;

import net.minecraft.client.gui.screens.Screen;

public record MouseButtonEvent(double x, double y, int button) {
    public MouseButtonEvent buttonInfo() { return this; }
    public boolean hasControlDownWithQuirk() { return Screen.hasControlDown(); }
    public boolean hasShiftDown() { return Screen.hasShiftDown(); }
}
