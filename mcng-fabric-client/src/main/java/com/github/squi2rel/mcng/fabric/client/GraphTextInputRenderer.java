package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

final class GraphTextInputRenderer {
	static final int CONTENT_PADDING_X = 4;
	static final int CONTENT_PADDING_Y = 2;

	private GraphTextInputRenderer() {
	}

	static void render(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget.Bounds bounds,
		GraphTextInputState state,
		GraphEditorTheme theme,
		GraphEditorUiConfig uiConfig,
		boolean focused
	) {
		renderFrame(context, bounds, theme, uiConfig, focused);
		renderContent(context, textRenderer, bounds, state, theme, focused);
	}

	static void renderFrame(
		DrawContext context,
		NodeWidget.Bounds bounds,
		GraphEditorTheme theme,
		GraphEditorUiConfig uiConfig
	) {
		renderFrame(context, bounds, theme, uiConfig, true);
	}

	static void renderFrame(
		DrawContext context,
		NodeWidget.Bounds bounds,
		GraphEditorTheme theme,
		GraphEditorUiConfig uiConfig,
		boolean focused
	) {
		EditorStyleRenderer.drawBox(
			context,
			bounds.x(),
			bounds.y(),
			bounds.width(),
			bounds.height(),
			EditorStyleRenderer.darken(theme.nodeBodyColor(), 0.02f),
			focused ? theme.accentColor() : theme.panelBorderColor(),
			uiConfig
		);
	}

	static void renderContent(
		DrawContext context,
		TextRenderer textRenderer,
		NodeWidget.Bounds bounds,
		GraphTextInputState state,
		GraphEditorTheme theme,
		boolean focused
	) {
		int innerX = bounds.x() + CONTENT_PADDING_X;
		int innerWidth = Math.max(1, bounds.width() - (CONTENT_PADDING_X * 2));
		int baselineY = bounds.y() + Math.max(2, (bounds.height() - textRenderer.fontHeight) / 2);
		String text = state.text();
		VisibleTextSlice visible = visibleSlice(textRenderer, state, innerWidth);
		int prefixWidth = textRenderer.getWidth(text.substring(0, visible.start()));
		int textX = innerX + prefixWidth - state.scrollX();

		if (focused && state.hasSelection()) {
			int selectionStartX = innerX + textRenderer.getWidth(text.substring(0, state.selectionStart())) - state.scrollX();
			int selectionWidth = textRenderer.getWidth(text.substring(state.selectionStart(), state.selectionEnd()));
			context.fill(
				selectionStartX,
				bounds.y() + CONTENT_PADDING_Y,
				selectionStartX + selectionWidth,
				bounds.y() + bounds.height() - CONTENT_PADDING_Y,
				EditorStyleRenderer.blend(theme.accentColor(), theme.nodeBodyColor(), 0.24f)
			);
		}

		context.drawText(textRenderer, visible.text(), textX, baselineY, theme.primaryTextColor(), false);
		if (focused && (System.currentTimeMillis() / 530L) % 2L == 0L) {
			int cursorX = innerX + textRenderer.getWidth(text.substring(0, state.cursor())) - state.scrollX();
			context.fill(cursorX, bounds.y() + CONTENT_PADDING_Y, cursorX + 1, bounds.y() + bounds.height() - CONTENT_PADDING_Y, theme.primaryTextColor());
		}
	}

	private static VisibleTextSlice visibleSlice(TextRenderer textRenderer, GraphTextInputState state, int innerWidth) {
		String text = state.text();
		if (text.isEmpty()) {
			return new VisibleTextSlice(0, 0, "");
		}

		int start = 0;
		while (start < text.length()) {
			int nextWidth = textRenderer.getWidth(text.substring(0, start + 1));
			if (nextWidth > state.scrollX()) {
				break;
			}
			start++;
		}

		int end = start;
		int visibleRight = state.scrollX() + innerWidth;
		while (end < text.length()) {
			int nextWidth = textRenderer.getWidth(text.substring(0, end + 1));
			if (nextWidth > visibleRight) {
				break;
			}
			end++;
		}

		start = Math.max(0, start - 1);
		end = Math.min(text.length(), Math.max(start, end + 1));
		return new VisibleTextSlice(start, end, text.substring(start, end));
	}

	private record VisibleTextSlice(int start, int end, String text) {
	}
}
