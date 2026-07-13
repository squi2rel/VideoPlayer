package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Map;

public record ExecutionResult(
	Map<NodeId, Map<PortId, Object>> outputs,
	Map<NodeId, List<PortId>> activatedControlOutputs,
	List<GraphError> errors,
	List<NodeId> visitedNodes
) {
	public ExecutionResult {
		outputs = Map.copyOf(outputs);
		activatedControlOutputs = Map.copyOf(activatedControlOutputs);
		errors = List.copyOf(errors);
		visitedNodes = List.copyOf(visitedNodes);
	}

	public boolean success() {
		return errors.isEmpty();
	}
}
