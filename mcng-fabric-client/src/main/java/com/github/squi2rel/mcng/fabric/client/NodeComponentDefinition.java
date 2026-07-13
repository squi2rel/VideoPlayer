package com.github.squi2rel.mcng.fabric.client;

import java.util.Objects;

public record NodeComponentDefinition(
	String nodeTypeId,
	NodeBodyComponentFactory factory,
	ResizePolicy resizePolicy
) {
	public NodeComponentDefinition {
		if (nodeTypeId == null || nodeTypeId.isBlank()) {
			throw new IllegalArgumentException("nodeTypeId must not be blank");
		}
		Objects.requireNonNull(factory, "factory");
		Objects.requireNonNull(resizePolicy, "resizePolicy");
	}
}
