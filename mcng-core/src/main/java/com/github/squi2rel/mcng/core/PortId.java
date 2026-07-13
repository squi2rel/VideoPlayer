package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record PortId(String value) {
	public PortId {
		Objects.requireNonNull(value, "value");
		if (value.isBlank()) {
			throw new IllegalArgumentException("Port id must not be blank");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
