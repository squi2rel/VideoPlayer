package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Map;

public record ExecutionSnapshot(
	Map<NodeId, Map<PortId, Object>> outputs,
	Map<NodeId, List<PortId>> activatedControlOutputs,
	List<GraphError> errors,
	List<NodeId> visitedNodes,
	List<ExecutionPosition> frontier,
	ExecutionSessionStatus status
) {
	public ExecutionSnapshot {
		outputs = Map.copyOf(outputs);
		activatedControlOutputs = Map.copyOf(activatedControlOutputs);
		errors = List.copyOf(errors);
		visitedNodes = List.copyOf(visitedNodes);
		frontier = List.copyOf(frontier);
	}

	public boolean running() {
		return status == ExecutionSessionStatus.RUNNING;
	}

	public boolean completed() {
		return status == ExecutionSessionStatus.COMPLETED;
	}

	public boolean cancelled() {
		return status == ExecutionSessionStatus.CANCELLED;
	}

	public ExecutionResult toExecutionResult() {
		return new ExecutionResult(outputs, activatedControlOutputs, errors, visitedNodes);
	}
}
