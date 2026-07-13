package com.github.squi2rel.mcng.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public record NodeExecutionRequest<C>(
	NodeInstance node,
	C config,
	Map<PortId, Object> inputs,
	Map<PortId, PortType<?>> outputTypes,
	Set<PortId> connectedControlInputs,
	Set<PortId> activeControlInputs,
	Supplier<Map<PortId, Object>> inputResolver,
	ExecutionTrigger trigger,
	ExecutionRuntime runtime,
	PortTypeRegistry portTypes,
	NodeTypeRegistry registry,
	NodeExecutionContext context
) {
	public NodeExecutionRequest {
		inputs = Map.copyOf(inputs);
		outputTypes = Map.copyOf(outputTypes);
		connectedControlInputs = Set.copyOf(connectedControlInputs);
		activeControlInputs = Set.copyOf(activeControlInputs);
	}

	public Optional<Object> input(PortId portId) {
		return Optional.ofNullable(inputs.get(portId));
	}

	public Optional<PortType<?>> outputType(PortId portId) {
		return Optional.ofNullable(outputTypes.get(portId));
	}

	public boolean hasConnectedControlInput(PortId portId) {
		return connectedControlInputs.contains(portId);
	}

	public boolean isControlInputActive(PortId portId) {
		return activeControlInputs.contains(portId);
	}

	public Map<PortId, Object> resolveInputs() {
		return Map.copyOf(inputResolver.get());
	}
}
