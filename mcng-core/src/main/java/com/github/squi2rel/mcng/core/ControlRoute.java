package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record ControlRoute(PortId inputPortId, PortId outputPortId) {
	public ControlRoute {
		Objects.requireNonNull(inputPortId, "inputPortId");
		Objects.requireNonNull(outputPortId, "outputPortId");
	}
}
