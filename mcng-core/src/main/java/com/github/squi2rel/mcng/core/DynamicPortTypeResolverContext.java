package com.github.squi2rel.mcng.core;

public interface DynamicPortTypeResolverContext {
	NodeInstance node(NodeId nodeId);

	ResolvedPortType resolve(NodeId nodeId, PortId portId, PortDirection direction);

	Object readInlineInputValue(NodeId nodeId, PortId portId);

	PortType<?> variableType(String variableId);

	Iterable<EdgeDefinition> edges();
}
