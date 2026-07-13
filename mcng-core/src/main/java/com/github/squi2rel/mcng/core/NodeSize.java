package com.github.squi2rel.mcng.core;

public record NodeSize(int width, int height) {
	public NodeSize {
		if (width <= 0) {
			throw new IllegalArgumentException("Node width must be positive");
		}
		if (height <= 0) {
			throw new IllegalArgumentException("Node height must be positive");
		}
	}
}
