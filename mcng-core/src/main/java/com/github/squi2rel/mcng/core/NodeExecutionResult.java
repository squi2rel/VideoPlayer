package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Map;

public record NodeExecutionResult(Map<PortId, Object> outputs, List<GraphError> errors, List<PortId> activatedControlOutputs) {
	public NodeExecutionResult {
		outputs = Map.copyOf(outputs);
		errors = List.copyOf(errors);
		activatedControlOutputs = List.copyOf(activatedControlOutputs);
	}

	public static NodeExecutionResult of(Map<PortId, Object> outputs) {
		return new NodeExecutionResult(outputs, List.of(), List.of());
	}

	public static NodeExecutionResult control(Map<PortId, Object> outputs, List<PortId> activatedControlOutputs) {
		return new NodeExecutionResult(outputs, List.of(), activatedControlOutputs);
	}

	public static NodeExecutionResult control(List<PortId> activatedControlOutputs) {
		return new NodeExecutionResult(Map.of(), List.of(), activatedControlOutputs);
	}
}
