package com.github.squi2rel.mcng.fabric.client;

public record GraphEditorTheme(
	int canvasBackgroundColor,
	int gridColor,
	int nodeBodyColor,
	int nodeHeaderColor,
	int nodeBorderColor,
	int panelBackgroundColor,
	int panelBorderColor,
	int primaryTextColor,
	int secondaryTextColor,
	int accentColor,
	int controlFlowColor,
	int executionColor,
	int errorColor
) {
	public static GraphEditorTheme classic() {
		return new GraphEditorTheme(
			0xFF10151F,
			0xFF1E2430,
			0xFF1A2230,
			0xFF223047,
			0xFF4B5D78,
			0xD0101520,
			0xFF4B5D78,
			0xFFF6F7FB,
			0xFFD6DEEF,
			0xFFFFCC55,
			0xFF7AC4FF,
			0xFF7CFF8A,
			0xFFFF8C8C
		);
	}

	public static GraphEditorTheme light() {
		return new GraphEditorTheme(
			0xFFF4F1EA,
			0xFFD3CCBE,
			0xFFFBFAF6,
			0xFFE3DDCF,
			0xFF7E7360,
			0xEAF7F3EA,
			0xFF8C7F69,
			0xFF221C14,
			0xFF5B5044,
			0xFFB26A2A,
			0xFF4E83B6,
			0xFF37A843,
			0xFFB43C3C
		);
	}

	public static GraphEditorTheme highContrast() {
		return new GraphEditorTheme(
			0xFF050505,
			0xFF1E1E1E,
			0xFF111111,
			0xFF1B1B1B,
			0xFFFFFFFF,
			0xE0000000,
			0xFFFFFFFF,
			0xFFFFFFFF,
			0xFFB8B8B8,
			0xFF00E2FF,
			0xFFFFF066,
			0xFF00FF66,
			0xFFFF4B4B
		);
	}

	public static GraphEditorTheme defaultTheme() {
		return classic();
	}

	public GraphEditorTheme withCanvasBackgroundColor(int color) {
		return copy(color, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withGridColor(int color) {
		return copy(canvasBackgroundColor, color, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withNodeBodyColor(int color) {
		return copy(canvasBackgroundColor, gridColor, color, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withNodeHeaderColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, color, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withNodeBorderColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, color, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withPanelBackgroundColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, color, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withPanelBorderColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, color, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withPrimaryTextColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, color, secondaryTextColor, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withSecondaryTextColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, color, accentColor, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withAccentColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, color, controlFlowColor, executionColor, errorColor);
	}

	public GraphEditorTheme withControlFlowColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, color, executionColor, errorColor);
	}

	public GraphEditorTheme withExecutionColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, color, errorColor);
	}

	public GraphEditorTheme withErrorColor(int color) {
		return copy(canvasBackgroundColor, gridColor, nodeBodyColor, nodeHeaderColor, nodeBorderColor, panelBackgroundColor, panelBorderColor, primaryTextColor, secondaryTextColor, accentColor, controlFlowColor, executionColor, color);
	}

	private GraphEditorTheme copy(
		int canvasBackgroundColor,
		int gridColor,
		int nodeBodyColor,
		int nodeHeaderColor,
		int nodeBorderColor,
		int panelBackgroundColor,
		int panelBorderColor,
		int primaryTextColor,
		int secondaryTextColor,
		int accentColor,
		int controlFlowColor,
		int executionColor,
		int errorColor
	) {
		return new GraphEditorTheme(
			canvasBackgroundColor,
			gridColor,
			nodeBodyColor,
			nodeHeaderColor,
			nodeBorderColor,
			panelBackgroundColor,
			panelBorderColor,
			primaryTextColor,
			secondaryTextColor,
			accentColor,
			controlFlowColor,
			executionColor,
			errorColor
		);
	}
}
