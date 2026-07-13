package com.github.squi2rel.mcng.fabric.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NodeComponentRegistry {
	private final Map<String, NodeComponentDefinition> definitions = new LinkedHashMap<>();

	public synchronized NodeComponentRegistry register(NodeComponentDefinition definition) {
		Objects.requireNonNull(definition, "definition");
		NodeComponentDefinition existing = definitions.putIfAbsent(definition.nodeTypeId(), definition);
		if (existing != null) {
			throw new IllegalArgumentException("Duplicate node component node type id: " + definition.nodeTypeId());
		}
		return this;
	}

	public synchronized Optional<NodeComponentDefinition> find(String nodeTypeId) {
		return Optional.ofNullable(definitions.get(nodeTypeId));
	}

	public synchronized List<NodeComponentDefinition> all() {
		return List.copyOf(definitions.values());
	}
}
