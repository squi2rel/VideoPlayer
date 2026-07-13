package com.github.squi2rel.mcng.fabric.client;

public record GraphEditorUiConfig(
	GraphEditorTheme theme,
	EdgeStyle edgeStyle,
	NodeCornerStyle nodeCornerStyle,
	PortShape portShape
) {
	public GraphEditorUiConfig {
		if (theme == null) {
			throw new IllegalArgumentException("theme must not be null");
		}
		if (edgeStyle == null) {
			throw new IllegalArgumentException("edgeStyle must not be null");
		}
		if (nodeCornerStyle == null) {
			throw new IllegalArgumentException("nodeCornerStyle must not be null");
		}
		if (portShape == null) {
			throw new IllegalArgumentException("portShape must not be null");
		}
	}

	public static GraphEditorUiConfig defaultConfig() {
		return new GraphEditorUiConfig(GraphEditorTheme.defaultTheme(), EdgeStyle.CURVE, NodeCornerStyle.ROUNDED, PortShape.CIRCLE);
	}

	public GraphEditorUiConfig withTheme(GraphEditorTheme theme) {
		return new GraphEditorUiConfig(theme, edgeStyle, nodeCornerStyle, portShape);
	}

	public GraphEditorUiConfig withEdgeStyle(EdgeStyle edgeStyle) {
		return new GraphEditorUiConfig(theme, edgeStyle, nodeCornerStyle, portShape);
	}

	public GraphEditorUiConfig withNodeCornerStyle(NodeCornerStyle nodeCornerStyle) {
		return new GraphEditorUiConfig(theme, edgeStyle, nodeCornerStyle, portShape);
	}

	public GraphEditorUiConfig withPortShape(PortShape portShape) {
		return new GraphEditorUiConfig(theme, edgeStyle, nodeCornerStyle, portShape);
	}
}
