package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record DocumentNodeDefinition(
	String id,
	String displayName,
	GraphScope scope
) {
	public DocumentNodeDefinition(String id, String displayName, DocumentNodeDefinitionKind kind, GraphDefinition graph, GraphLayout layout) {
		this(id, displayName, GraphScope.of(graph, layout));
		if (kind != DocumentNodeDefinitionKind.CUSTOM_NODE) {
			throw new IllegalArgumentException("Subgraph document definitions are no longer supported");
		}
	}

	public DocumentNodeDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(scope, "scope");
		if (id.isBlank()) {
			throw new IllegalArgumentException("Definition id must not be blank");
		}
		if (displayName.isBlank()) {
			throw new IllegalArgumentException("Definition display name must not be blank");
		}
	}

	public GraphDefinition graph() {
		return scope.graph();
	}

	public GraphLayout layout() {
		return scope.layout();
	}

	public java.util.List<GraphVariableDefinition> variables() {
		return scope.variables();
	}

	public java.util.List<SubgraphDefinition> subgraphs() {
		return scope.subgraphs();
	}

	public DocumentNodeDefinitionKind kind() {
		return DocumentNodeDefinitionKind.CUSTOM_NODE;
	}
}
