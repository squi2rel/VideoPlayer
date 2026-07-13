package com.github.squi2rel.mcng.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeRenderDetailLevelTest {
	@Test
	void usesFullDetailAboveMinimumZoom() {
		assertEquals(NodeRenderDetailLevel.FULL, NodeRenderDetailLevel.fromZoom(1.0));
		assertEquals(NodeRenderDetailLevel.FULL, NodeRenderDetailLevel.fromZoom(0.84));
		assertEquals(NodeRenderDetailLevel.FULL, NodeRenderDetailLevel.fromZoom(GraphViewportState.MIN_ZOOM + 0.01));
	}

	@Test
	void usesMinimalOnlyAtMinimumZoom() {
		assertEquals(NodeRenderDetailLevel.MINIMAL, NodeRenderDetailLevel.fromZoom(GraphViewportState.MIN_ZOOM));
	}
}
