package com.github.squi2rel.mcng.fabric.client;

public enum ResizeDirection {
	LEFT,
	RIGHT,
	TOP,
	BOTTOM,
	TOP_LEFT,
	TOP_RIGHT,
	BOTTOM_LEFT,
	BOTTOM_RIGHT;

	public boolean includesLeft() {
		return this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT;
	}

	public boolean includesRight() {
		return this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT;
	}

	public boolean includesTop() {
		return this == TOP || this == TOP_LEFT || this == TOP_RIGHT;
	}

	public boolean includesBottom() {
		return this == BOTTOM || this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
	}

	public boolean isCorner() {
		return (includesLeft() || includesRight()) && (includesTop() || includesBottom());
	}
}
