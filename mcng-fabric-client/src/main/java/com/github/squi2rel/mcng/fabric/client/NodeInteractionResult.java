package com.github.squi2rel.mcng.fabric.client;

public record NodeInteractionResult(
	boolean handled,
	boolean requestFocus,
	boolean capturePointer
) {
	public static NodeInteractionResult ignored() {
		return new NodeInteractionResult(false, false, false);
	}

	public static NodeInteractionResult handledResult() {
		return new NodeInteractionResult(true, false, false);
	}

	public static NodeInteractionResult focusHandled() {
		return new NodeInteractionResult(true, true, false);
	}

	public static NodeInteractionResult captured() {
		return new NodeInteractionResult(true, true, true);
	}
}
