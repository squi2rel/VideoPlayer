package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphEditorComponentPlacementTest {
	@Test
	void placementPositionMatchesPreviewForRegularNode() {
		assertPlacementMatchesPreview(BuiltinNodeTypes.ADD);
	}

	@Test
	void placementPositionMatchesPreviewForReroute() {
		assertPlacementMatchesPreview(BuiltinNodeTypes.REROUTE);
	}

	@Test
	void placementPositionMatchesPreviewForRegisteredBodyComponent() {
		GraphViewportState viewport = new GraphViewportState();
		viewport.reset();
		GraphEditorBounds canvasBounds = new GraphEditorBounds(20, 48, 640, 360);
		double screenX = 240.0;
		double screenY = 180.0;
		NodeComponentRegistry components = new NodeComponentRegistry()
			.register(new NodeComponentDefinition(BuiltinNodeTypes.ADD.id(), PlacementPreviewBody::new, ResizePolicy.allSides()));

		NodeWidget preview = NodeWidget.preview(BuiltinNodeTypes.ADD, GraphEditorUiConfig.defaultConfig(), new PlacementPreviewBody(), ResizePolicy.allSides(), (int) Math.round(screenX), (int) Math.round(screenY));
		NodePosition placement = GraphEditorComponent.placementPosition(viewport, canvasBounds, BuiltinNodeTypes.ADD, GraphEditorUiConfig.defaultConfig(), components, screenX, screenY);

		double placedScreenX = canvasBounds.x() + viewport.toScreenX(placement.x());
		double placedScreenY = canvasBounds.y() + viewport.toScreenY(placement.y());
		assertEquals(preview.x(), (int) Math.round(placedScreenX));
		assertEquals(preview.y(), (int) Math.round(placedScreenY));
	}

	private static void assertPlacementMatchesPreview(NodeType<?> nodeType) {
		GraphViewportState viewport = new GraphViewportState();
		viewport.reset();
		GraphEditorBounds canvasBounds = new GraphEditorBounds(20, 48, 640, 360);
		double screenX = 240.0;
		double screenY = 180.0;

		NodeWidget preview = NodeWidget.preview(nodeType, (int) Math.round(screenX), (int) Math.round(screenY));
		NodePosition placement = GraphEditorComponent.placementPosition(viewport, canvasBounds, nodeType, screenX, screenY);

		double placedScreenX = canvasBounds.x() + viewport.toScreenX(placement.x());
		double placedScreenY = canvasBounds.y() + viewport.toScreenY(placement.y());
		assertEquals(preview.x(), (int) Math.round(placedScreenX));
		assertEquals(preview.y(), (int) Math.round(placedScreenY));
	}

	private static final class PlacementPreviewBody implements NodeBodyComponent {
		@Override
		public NodeBodyMeasurement measure(NodeBodyMeasureContext context) {
			return new NodeBodyMeasurement(true, 120, 72, 160, 72);
		}
	}
}
