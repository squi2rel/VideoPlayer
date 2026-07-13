package com.github.squi2rel.mcng.fabric.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class NodePaletteRegistry {
	private final Map<String, NodePaletteDefinition> definitions = new LinkedHashMap<>();

	public synchronized NodePaletteRegistry register(NodePaletteDefinition definition) {
		Objects.requireNonNull(definition, "definition");
		NodePaletteDefinition existing = definitions.putIfAbsent(definition.nodeTypeId(), definition);
		if (existing != null) {
			throw new IllegalArgumentException("Duplicate palette node type id: " + definition.nodeTypeId());
		}
		return this;
	}

	public synchronized Optional<NodePaletteDefinition> find(String nodeTypeId) {
		return Optional.ofNullable(definitions.get(nodeTypeId));
	}

	public synchronized Collection<NodePaletteDefinition> all() {
		return List.copyOf(definitions.values());
	}
}
