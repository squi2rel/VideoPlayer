package com.github.squi2rel.mcng.core;

import java.util.Objects;
import java.util.UUID;

public record NodeId(String value) {
	public NodeId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank()) {
			throw new IllegalArgumentException("Node id must not be blank");
		}
	}

	public static NodeId random() {
		return new NodeId(UUID.randomUUID().toString());
	}

	@Override
	public String toString() {
		return value;
	}
}
