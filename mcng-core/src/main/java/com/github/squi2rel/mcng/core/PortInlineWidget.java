package com.github.squi2rel.mcng.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Objects;

public record PortInlineWidget(Kind kind, JsonElement defaultValue) {
	public PortInlineWidget {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(defaultValue, "defaultValue");
		defaultValue = defaultValue.deepCopy();
	}

	public static PortInlineWidget numericText(String defaultValue) {
		return new PortInlineWidget(Kind.NUMERIC_TEXT, new JsonPrimitive(defaultValue));
	}

	public static PortInlineWidget stringText(String defaultValue) {
		return new PortInlineWidget(Kind.STRING_TEXT, new JsonPrimitive(defaultValue));
	}

	public static PortInlineWidget booleanToggle(boolean defaultValue) {
		return new PortInlineWidget(Kind.BOOLEAN_TOGGLE, new JsonPrimitive(defaultValue));
	}

	public enum Kind {
		NUMERIC_TEXT,
		STRING_TEXT,
		BOOLEAN_TOGGLE
	}
}
