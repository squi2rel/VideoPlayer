package com.github.squi2rel.mcng.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

import java.util.Objects;

public record GraphVariableDefinition(String id, String displayName, String typeId, JsonElement defaultValue) {
	public GraphVariableDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(typeId, "typeId");
		defaultValue = defaultValue == null ? JsonNull.INSTANCE : defaultValue.deepCopy();
		if (id.isBlank()) {
			throw new IllegalArgumentException("Variable id must not be blank");
		}
		if (displayName.isBlank()) {
			throw new IllegalArgumentException("Variable display name must not be blank");
		}
		if (typeId.isBlank()) {
			throw new IllegalArgumentException("Variable type id must not be blank");
		}
	}
}
