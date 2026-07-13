package com.github.squi2rel.mcng.core;

import com.google.gson.JsonObject;

import java.util.Objects;

public record NodeInstance(NodeId id, String typeId, JsonObject config) {
	public NodeInstance {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(typeId, "typeId");
		Objects.requireNonNull(config, "config");
		if (typeId.isBlank()) {
			throw new IllegalArgumentException("Node type id must not be blank");
		}
		config = config.deepCopy();
	}
}
