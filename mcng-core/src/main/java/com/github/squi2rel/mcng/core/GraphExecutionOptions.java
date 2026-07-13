package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record GraphExecutionOptions(RuntimeValueLimits valueLimits) {
	public static final GraphExecutionOptions DEFAULT = new GraphExecutionOptions(RuntimeValueLimits.DEFAULT);

	public GraphExecutionOptions {
		valueLimits = Objects.requireNonNull(valueLimits, "valueLimits");
	}
}
