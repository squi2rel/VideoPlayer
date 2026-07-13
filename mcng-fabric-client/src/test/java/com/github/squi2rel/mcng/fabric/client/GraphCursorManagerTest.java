package com.github.squi2rel.mcng.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphCursorManagerTest {
	@Test
	void mapsResizeDirectionsToExpectedCursorKinds() {
		assertEquals(GraphCursorManager.CursorKind.DEFAULT, GraphCursorManager.forResizeDirection(null));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_EW, GraphCursorManager.forResizeDirection(ResizeDirection.LEFT));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_EW, GraphCursorManager.forResizeDirection(ResizeDirection.RIGHT));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_NS, GraphCursorManager.forResizeDirection(ResizeDirection.TOP));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_NS, GraphCursorManager.forResizeDirection(ResizeDirection.BOTTOM));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_NWSE, GraphCursorManager.forResizeDirection(ResizeDirection.TOP_LEFT));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_NWSE, GraphCursorManager.forResizeDirection(ResizeDirection.BOTTOM_RIGHT));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_NESW, GraphCursorManager.forResizeDirection(ResizeDirection.TOP_RIGHT));
		assertEquals(GraphCursorManager.CursorKind.RESIZE_NESW, GraphCursorManager.forResizeDirection(ResizeDirection.BOTTOM_LEFT));
	}
}
