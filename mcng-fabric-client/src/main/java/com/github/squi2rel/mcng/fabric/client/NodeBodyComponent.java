package com.github.squi2rel.mcng.fabric.client;

public interface NodeBodyComponent {
	default NodeBodyMeasurement measure(NodeBodyMeasureContext context) {
		return NodeBodyMeasurement.hidden();
	}

	default void render(NodeBodyRenderContext context) {
	}

	default NodeInteractionResult mouseClicked(NodeBodyInputContext context, double localMouseX, double localMouseY, int button) {
		return NodeInteractionResult.ignored();
	}

	default NodeInteractionResult mouseDragged(NodeBodyInputContext context, double localMouseX, double localMouseY, int button, double deltaX, double deltaY) {
		return NodeInteractionResult.ignored();
	}

	default NodeInteractionResult mouseReleased(NodeBodyInputContext context, double localMouseX, double localMouseY, int button) {
		return NodeInteractionResult.ignored();
	}

	default NodeInteractionResult mouseScrolled(NodeBodyInputContext context, double localMouseX, double localMouseY, double horizontalAmount, double verticalAmount) {
		return NodeInteractionResult.ignored();
	}

	default boolean keyPressed(NodeBodyInputContext context, int keyCode, int scanCode, int modifiers) {
		return false;
	}

	default boolean charTyped(NodeBodyInputContext context, char chr, int modifiers) {
		return false;
	}

	default void blur() {
	}

	default void close() {
	}
}
