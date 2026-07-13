package com.github.squi2rel.mcng.core;

import java.util.List;

public record ExecutionPosition(
	List<String> definitionPath,
	List<NodeId> invocationPath,
	NodeId nodeId
) {
	public ExecutionPosition {
		definitionPath = List.copyOf(definitionPath);
		invocationPath = List.copyOf(invocationPath);
	}
}
