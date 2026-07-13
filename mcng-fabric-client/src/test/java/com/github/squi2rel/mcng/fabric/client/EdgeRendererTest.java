package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.PortDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeRendererTest {
	@Test
	void curveControlsRespectActualPortSides() {
		EdgeRenderer.CurveControls controls = EdgeRenderer.curveControls(
			100,
			60,
			200,
			90,
			NodeWidget.PortSide.RIGHT,
			NodeWidget.PortSide.RIGHT
		);

		assertTrue(controls.control1X() > 100.0);
		assertTrue(controls.control2X() > 200.0);
	}

	@Test
	void curveControlsRespectVerticalPortSides() {
		EdgeRenderer.CurveControls controls = EdgeRenderer.curveControls(
			120,
			140,
			130,
			40,
			NodeWidget.PortSide.TOP,
			NodeWidget.PortSide.BOTTOM
		);

		assertTrue(controls.control1Y() < 140.0);
		assertTrue(controls.control2Y() > 40.0);
	}

	@Test
	void pendingEdgeFromInputUsesMouseAsLogicalSource() {
		NodeWidget.PortWidget inputPort = new NodeWidget.PortWidget(
			new NodeId("reroute"),
			PortDefinition.input("value", "Value", MCNGPortTypes.ANY, false),
			NodeWidget.PortSide.RIGHT,
			200,
			100,
			4,
			6
		);

		GraphCanvasComponent.PendingEdgeGeometry geometry = GraphCanvasComponent.pendingEdgeGeometry(inputPort, 80, 120);

		assertEquals(80, geometry.startX());
		assertEquals(120, geometry.startY());
		assertEquals(200, geometry.endX());
		assertEquals(100, geometry.endY());
		assertEquals(NodeWidget.PortSide.RIGHT, geometry.startSide());
		assertEquals(NodeWidget.PortSide.RIGHT, geometry.endSide());
	}

	@Test
	void pendingEdgeCanUseVerticalFreeEndpointSide() {
		NodeWidget.PortWidget inputPort = new NodeWidget.PortWidget(
			new NodeId("reroute"),
			PortDefinition.input("value", "Value", MCNGPortTypes.ANY, false),
			NodeWidget.PortSide.BOTTOM,
			120,
			180,
			4,
			6
		);

		GraphCanvasComponent.PendingEdgeGeometry geometry = GraphCanvasComponent.pendingEdgeGeometry(inputPort, 118, 20);

		assertEquals(NodeWidget.PortSide.BOTTOM, geometry.startSide());
		assertEquals(NodeWidget.PortSide.BOTTOM, geometry.endSide());
	}
}
