package com.github.squi2rel.mcng.fabric.client;

import net.minecraft.client.gui.DrawContext;

final class EditorStyleRenderer {
	private static final int DEFAULT_RADIUS = 6;

	private EditorStyleRenderer() {
	}

	static void drawBorder(DrawContext context, int x, int y, int width, int height, int color) {
		context.drawStrokedRectangle(x, y, width, height, color);
	}

	static void drawBox(DrawContext context, int x, int y, int width, int height, int fillColor, int borderColor, GraphEditorUiConfig config) {
		if (config.nodeCornerStyle() == NodeCornerStyle.ROUNDED) {
			fillRoundedRect(context, x, y, width, height, fillColor, DEFAULT_RADIUS);
			drawRoundedOutline(context, x, y, width, height, borderColor, DEFAULT_RADIUS);
			return;
		}
		context.fill(x, y, x + width, y + height, fillColor);
		drawBorder(context, x, y, width, height, borderColor);
	}

	static void drawNodeBox(DrawContext context, NodeWidget widget, int bodyColor, int headerColor, int borderColor, GraphEditorUiConfig config) {
		drawBox(context, widget.x(), widget.y(), widget.width(), widget.height(), bodyColor, borderColor, config);
		if (!widget.hasHeader()) {
			return;
		}
		if (config.nodeCornerStyle() == NodeCornerStyle.ROUNDED) {
			fillTopRoundedRect(context, widget.x() + 1, widget.y() + 1, widget.width() - 2, Math.max(1, widget.headerHeight() - 1), headerColor, DEFAULT_RADIUS - 1);
			return;
		}
		context.fill(
			widget.x() + 1,
			widget.y() + 1,
			widget.x() + widget.width() - 1,
			widget.y() + widget.headerHeight(),
			headerColor
		);
	}

	static void drawPort(DrawContext context, int centerX, int centerY, int radius, int color, PortShape portShape) {
		if (portShape == PortShape.SQUARE) {
			context.fill(centerX - radius, centerY - radius, centerX + radius + 1, centerY + radius + 1, color);
			return;
		}
		fillCircle(context, centerX, centerY, radius, color);
	}

	static int blend(int startColor, int endColor, float amount) {
		int alpha = mixChannel((startColor >>> 24) & 0xFF, (endColor >>> 24) & 0xFF, amount);
		int red = mixChannel((startColor >>> 16) & 0xFF, (endColor >>> 16) & 0xFF, amount);
		int green = mixChannel((startColor >>> 8) & 0xFF, (endColor >>> 8) & 0xFF, amount);
		int blue = mixChannel(startColor & 0xFF, endColor & 0xFF, amount);
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	static int brighten(int color, float amount) {
		return blend(color, 0xFFFFFFFF, amount);
	}

	static int darken(int color, float amount) {
		return blend(color, 0xFF000000, amount);
	}

	private static void fillCircle(DrawContext context, int centerX, int centerY, int radius, int color) {
		for (int dy = -radius; dy <= radius; dy++) {
			double distance = Math.sqrt(Math.max(0, (radius * radius) - (dy * dy)));
			int xOffset = (int) Math.floor(distance);
			context.fill(centerX - xOffset, centerY + dy, centerX + xOffset + 1, centerY + dy + 1, color);
		}
	}

	private static void fillRoundedRect(DrawContext context, int x, int y, int width, int height, int color, int radius) {
		if (width <= 0 || height <= 0) {
			return;
		}
		int clampedRadius = clampRadius(width, height, radius);
		if (clampedRadius <= 0) {
			context.fill(x, y, x + width, y + height, color);
			return;
		}

		for (int row = 0; row < height; row++) {
			int inset = insetForRow(height, clampedRadius, row);
			context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
		}
	}

	private static void drawRoundedOutline(DrawContext context, int x, int y, int width, int height, int color, int radius) {
		if (width <= 0 || height <= 0) {
			return;
		}
		int clampedRadius = clampRadius(width, height, radius);
		if (clampedRadius <= 0) {
			drawBorder(context, x, y, width, height, color);
			return;
		}

		int innerX = x + 1;
		int innerY = y + 1;
		int innerWidth = width - 2;
		int innerHeight = height - 2;
		int innerRadius = innerWidth > 0 && innerHeight > 0 ? clampRadius(innerWidth, innerHeight, Math.max(0, clampedRadius - 1)) : 0;
		for (int row = 0; row < height; row++) {
			int outerInset = insetForRow(height, clampedRadius, row);
			int outerLeft = x + outerInset;
			int outerRight = x + width - outerInset - 1;
			if (outerLeft > outerRight) {
				continue;
			}

			int innerRow = row - 1;
			if (innerWidth <= 0 || innerHeight <= 0 || innerRow < 0 || innerRow >= innerHeight) {
				context.fill(outerLeft, y + row, outerRight + 1, y + row + 1, color);
				continue;
			}

			int innerInset = insetForRow(innerHeight, innerRadius, innerRow);
			int innerLeft = innerX + innerInset;
			int innerRight = innerX + innerWidth - innerInset - 1;
			if (innerLeft > innerRight) {
				context.fill(outerLeft, y + row, outerRight + 1, y + row + 1, color);
				continue;
			}
			if (outerLeft < innerLeft) {
				context.fill(outerLeft, y + row, innerLeft, y + row + 1, color);
			}
			if (innerRight < outerRight) {
				context.fill(innerRight + 1, y + row, outerRight + 1, y + row + 1, color);
			}
		}
	}

	private static void fillTopRoundedRect(DrawContext context, int x, int y, int width, int height, int color, int radius) {
		if (width <= 0 || height <= 0) {
			return;
		}
		int clampedRadius = clampRadius(width, height * 2, radius);
		if (clampedRadius <= 0 || height <= clampedRadius) {
			fillRoundedRect(context, x, y, width, height, color, clampedRadius);
			return;
		}

		for (int row = 0; row < height; row++) {
			int inset = row < clampedRadius ? cornerInset(clampedRadius, row) : 0;
			context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
		}
	}

	private static int clampRadius(int width, int height, int radius) {
		return Math.max(0, Math.min(radius, Math.min(width, height) / 2));
	}

	private static int insetForRow(int height, int radius, int row) {
		if (radius <= 0) {
			return 0;
		}
		int mirroredRow = Math.min(row, height - row - 1);
		if (mirroredRow >= radius) {
			return 0;
		}
		return cornerInset(radius, mirroredRow);
	}

	private static int cornerInset(int radius, int row) {
		double dy = (radius - row) - 0.5;
		return Math.max(0, (int) Math.floor(radius - Math.sqrt(Math.max(0.0, (radius * radius) - (dy * dy)))));
	}

	private static int mixChannel(int start, int end, float amount) {
		return Math.max(0, Math.min(255, Math.round(start + ((end - start) * amount))));
	}
}
