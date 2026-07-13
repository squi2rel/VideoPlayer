package com.github.squi2rel.mcng.fabric.client;

sealed interface NodePaletteAction permits NodePaletteAction.CreateAtCenter, NodePaletteAction.CreateAtPointer {
	record CreateAtCenter(String nodeTypeId) implements NodePaletteAction {
	}

	record CreateAtPointer(String nodeTypeId, double screenX, double screenY) implements NodePaletteAction {
	}
}
