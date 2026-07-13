package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record PortType<T>(String id, Class<T> javaType) {
	public PortType {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(javaType, "javaType");
		if (id.isBlank()) {
			throw new IllegalArgumentException("Port type id must not be blank");
		}
	}

	public boolean accepts(Object value) {
		return value == null || javaType.isInstance(value);
	}

	public boolean isCompatibleWith(PortType<?> other) {
		return this.equals(MCNGPortTypes.ANY) || other.equals(MCNGPortTypes.ANY) || id.equals(other.id);
	}

	@Override
	public String toString() {
		return id;
	}
}
