package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record SubgraphDefinition(
	String id,
	String displayName,
	GraphScope scope
) {
	public SubgraphDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(scope, "scope");
		if (id.isBlank()) {
			throw new IllegalArgumentException("Subgraph id must not be blank");
		}
		if (displayName.isBlank()) {
			throw new IllegalArgumentException("Subgraph display name must not be blank");
		}
	}

	public GraphDefinition graph() {
		return scope.graph();
	}

	public GraphLayout layout() {
		return scope.layout();
	}
}
