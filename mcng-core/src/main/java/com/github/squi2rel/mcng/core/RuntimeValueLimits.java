package com.github.squi2rel.mcng.core;

public record RuntimeValueLimits(int maxAggregateValues, int maxDepth) {
	public static final RuntimeValueLimits DEFAULT = new RuntimeValueLimits(256, 8);

	public RuntimeValueLimits {
		if (maxAggregateValues <= 0) {
			throw new IllegalArgumentException("maxAggregateValues must be positive");
		}
		if (maxDepth <= 0) {
			throw new IllegalArgumentException("maxDepth must be positive");
		}
	}
}
