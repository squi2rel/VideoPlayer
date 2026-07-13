package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record GraphDefinition(List<NodeInstance> nodes, List<EdgeDefinition> edges) {
	public GraphDefinition {
		nodes = List.copyOf(nodes);
		edges = List.copyOf(edges);
	}

	public Map<NodeId, NodeInstance> nodeMap() {
		return nodes.stream().collect(Collectors.toMap(NodeInstance::id, Function.identity()));
	}
}
