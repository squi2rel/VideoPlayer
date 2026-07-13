package com.github.squi2rel.mcng.fabric.client;

record NodePaletteInteractionResult(boolean handled, NodePaletteAction action) {
	static NodePaletteInteractionResult handledResult() {
		return new NodePaletteInteractionResult(true, null);
	}

	static NodePaletteInteractionResult ignoredResult() {
		return new NodePaletteInteractionResult(false, null);
	}

	static NodePaletteInteractionResult actionResult(NodePaletteAction action) {
		return new NodePaletteInteractionResult(true, action);
	}
}
