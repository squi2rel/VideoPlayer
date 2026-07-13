package com.github.squi2rel.mcng.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NodeTypeRegistry {
	private final Map<String, NodeType<?>> nodeTypes = new LinkedHashMap<>();

	public synchronized NodeTypeRegistry register(NodeType<?> nodeType) {
		Objects.requireNonNull(nodeType, "nodeType");
		NodeType<?> existing = nodeTypes.putIfAbsent(nodeType.id(), nodeType);
		if (existing != null) {
			throw new IllegalArgumentException("Duplicate node type id: " + nodeType.id());
		}
		return this;
	}

	public Optional<NodeType<?>> find(String id) {
		return Optional.ofNullable(nodeTypes.get(id));
	}

	public NodeType<?> getOrThrow(String id) {
		NodeType<?> nodeType = nodeTypes.get(id);
		if (nodeType == null) {
			throw new IllegalArgumentException("Unknown node type: " + id);
		}
		return nodeType;
	}

	public Collection<NodeType<?>> all() {
		return nodeTypes.values();
	}
}
