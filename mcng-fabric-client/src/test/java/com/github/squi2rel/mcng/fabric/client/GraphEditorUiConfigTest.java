package com.github.squi2rel.mcng.fabric.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphEditorUiConfigTest {
	@Test
	void defaultConfigUsesClassicCurveRoundedCircle() {
		GraphEditorUiConfig config = GraphEditorUiConfig.defaultConfig();

		assertEquals(GraphEditorTheme.classic(), config.theme());
		assertEquals(EdgeStyle.CURVE, config.edgeStyle());
		assertEquals(NodeCornerStyle.ROUNDED, config.nodeCornerStyle());
		assertEquals(PortShape.CIRCLE, config.portShape());
	}

	@Test
	void withMethodsReplaceOnlyRequestedFields() {
		GraphEditorUiConfig config = GraphEditorUiConfig.defaultConfig()
			.withEdgeStyle(EdgeStyle.STRAIGHT)
			.withNodeCornerStyle(NodeCornerStyle.SQUARE)
			.withPortShape(PortShape.SQUARE)
			.withTheme(GraphEditorTheme.light());

		assertEquals(GraphEditorTheme.light(), config.theme());
		assertEquals(EdgeStyle.STRAIGHT, config.edgeStyle());
		assertEquals(NodeCornerStyle.SQUARE, config.nodeCornerStyle());
		assertEquals(PortShape.SQUARE, config.portShape());
	}
}
