package com.github.squi2rel.mcng.core;

import java.util.List;

public interface NodeType<C> {
	String id();

	String displayName();

	NodeKind kind();

	List<PortDefinition> inputs();

	List<PortDefinition> outputs();

	default List<PortDefinition> inputs(NodeInstance node) {
		return inputs();
	}

	default List<PortDefinition> outputs(NodeInstance node) {
		return outputs();
	}

	C defaultConfig();

	NodeConfigCodec<C> configCodec();

	default List<NodeEditorControl> editorControls() {
		return List.of();
	}

	default NodeControlMode controlMode() {
		return NodeControlMode.NONE;
	}

	default NodeVisualStyle visualStyle() {
		return NodeVisualStyle.STANDARD;
	}

	default List<ControlRoute> controlRoutes(NodeInstance node) {
		List<PortId> controlInputs = inputs(node).stream()
			.filter(port -> port.channel() == PortChannel.CONTROL)
			.map(PortDefinition::id)
			.toList();
		List<PortId> controlOutputs = outputs(node).stream()
			.filter(port -> port.channel() == PortChannel.CONTROL)
			.map(PortDefinition::id)
			.toList();
		if (controlInputs.isEmpty() || controlOutputs.isEmpty()) {
			return List.of();
		}
		java.util.ArrayList<ControlRoute> routes = new java.util.ArrayList<>();
		for (PortId input : controlInputs) {
			for (PortId output : controlOutputs) {
				routes.add(new ControlRoute(input, output));
			}
		}
		return List.copyOf(routes);
	}

	NodeExecutionResult execute(NodeExecutionRequest<C> request);
}
