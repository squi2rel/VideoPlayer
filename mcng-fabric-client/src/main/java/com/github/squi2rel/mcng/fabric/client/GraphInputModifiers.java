package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;

final class GraphInputModifiers {
	private GraphInputModifiers() {
	}

	static boolean shiftDown() {
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client == null) {
				return false;
			}
			Window window = client.getWindow();
			return InputUtil.isKeyPressed(window, InputUtil.GLFW_KEY_LEFT_SHIFT)
				|| InputUtil.isKeyPressed(window, InputUtil.GLFW_KEY_RIGHT_SHIFT);
		} catch (RuntimeException ignored) {
			return false;
		}
	}
}
