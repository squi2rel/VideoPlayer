package com.github.squi2rel.mcng.fabric.client;

public record GraphEditorBounds(int x, int y, int width, int height) {
	public GraphEditorBounds {
		if (width < 0) {
			throw new IllegalArgumentException("width must be non-negative");
		}
		if (height < 0) {
			throw new IllegalArgumentException("height must be non-negative");
		}
	}

	public boolean contains(double screenX, double screenY) {
		return screenX >= x && screenX <= x + width && screenY >= y && screenY <= y + height;
	}

	public int right() {
		return x + width;
	}

	public int bottom() {
		return y + height;
	}

	public GraphEditorBounds inset(int inset) {
		return new GraphEditorBounds(x + inset, y + inset, Math.max(0, width - (inset * 2)), Math.max(0, height - (inset * 2)));
	}
}
