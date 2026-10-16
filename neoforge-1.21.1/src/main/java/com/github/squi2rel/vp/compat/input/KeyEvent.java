package com.github.squi2rel.vp.compat.input;

import org.lwjgl.glfw.GLFW;

public record KeyEvent(int key, int scancode, int modifiers) {
    public boolean isSelection() {
        return key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
    }
}
