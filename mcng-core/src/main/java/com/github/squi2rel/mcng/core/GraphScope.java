package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Objects;

public record GraphScope(
	GraphDefinition graph,
	GraphLayout layout,
	List<GraphVariableDefinition> variables,
	List<SubgraphDefinition> subgraphs
) {
	public GraphScope {
		Objects.requireNonNull(graph, "graph");
		Objects.requireNonNull(layout, "layout");
		variables = List.copyOf(variables);
		subgraphs = List.copyOf(subgraphs);
	}

	public static GraphScope of(GraphDefinition graph, GraphLayout layout) {
		return new GraphScope(graph, layout, List.of(), List.of());
	}

	public static GraphScope of(GraphDefinition graph, GraphLayout layout, List<GraphVariableDefinition> variables, List<SubgraphDefinition> subgraphs) {
		return new GraphScope(graph, layout, variables, subgraphs);
	}
}
