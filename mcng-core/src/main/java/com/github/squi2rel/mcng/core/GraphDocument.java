package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Objects;

public record GraphDocument(
	int version,
	GraphScope rootScope,
	List<DocumentNodeDefinition> definitions
) {
	public static final int CURRENT_VERSION = 1;

	public GraphDocument {
		Objects.requireNonNull(rootScope, "rootScope");
		if (version <= 0) {
			throw new IllegalArgumentException("Document version must be positive");
		}
		definitions = List.copyOf(definitions);
	}

	public static GraphDocument of(GraphDefinition graph, GraphLayout layout) {
		return new GraphDocument(CURRENT_VERSION, GraphScope.of(graph, layout), List.of());
	}

	public static GraphDocument of(GraphDefinition graph, GraphLayout layout, List<DocumentNodeDefinition> definitions) {
		return new GraphDocument(CURRENT_VERSION, GraphScope.of(graph, layout), definitions);
	}

	public static GraphDocument of(GraphDefinition graph, GraphLayout layout, List<DocumentNodeDefinition> definitions, List<GraphVariableDefinition> variables) {
		return new GraphDocument(CURRENT_VERSION, GraphScope.of(graph, layout, variables, List.of()), definitions);
	}

	public static GraphDocument of(GraphScope rootScope, List<DocumentNodeDefinition> definitions) {
		return new GraphDocument(CURRENT_VERSION, rootScope, definitions);
	}

	public GraphDefinition graph() {
		return rootScope.graph();
	}

	public GraphLayout layout() {
		return rootScope.layout();
	}

	public List<GraphVariableDefinition> variables() {
		return rootScope.variables();
	}

	public List<SubgraphDefinition> subgraphs() {
		return rootScope.subgraphs();
	}
}
