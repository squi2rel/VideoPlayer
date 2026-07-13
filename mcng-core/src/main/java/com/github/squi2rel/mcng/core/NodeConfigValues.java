package com.github.squi2rel.mcng.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public final class NodeConfigValues {
	public static final String INLINE_INPUTS_KEY = "__inlineInputs";

	private NodeConfigValues() {
	}

	public static JsonObject copyWithInlineInput(JsonObject config, PortId portId, JsonElement value) {
		JsonObject updated = config.deepCopy();
		inlineInputs(updated, true).add(portId.value(), value.deepCopy());
		return updated;
	}

	public static JsonObject copyWithControlValue(JsonObject config, String key, JsonElement value) {
		JsonObject updated = config.deepCopy();
		updated.add(key, value.deepCopy());
		return updated;
	}

	public static Object readInlineInputValue(JsonObject config, PortDefinition port) {
		PortInlineWidget inlineWidget = port.inlineWidget();
		if (inlineWidget == null) {
			return null;
		}
		try {
			return switch (inlineWidget.kind()) {
				case NUMERIC_TEXT -> NumericTypes.parseLiteral(readInlineInputText(config, port)).value();
				case STRING_TEXT -> readInlineInputText(config, port);
				case BOOLEAN_TOGGLE -> readInlineInputBoolean(config, port);
			};
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid inline input value for " + port.id().value());
		}
	}

	public static String readInlineInputText(JsonObject config, PortDefinition port) {
		PortInlineWidget inlineWidget = port.inlineWidget();
		if (inlineWidget == null) {
			return "";
		}
		JsonObject inlineInputs = inlineInputs(config, false);
		JsonElement value = inlineInputs != null && inlineInputs.has(port.id().value())
			? inlineInputs.get(port.id().value())
			: inlineWidget.defaultValue();
		try {
			return value.getAsString();
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid inline input value for " + port.id().value());
		}
	}

	public static boolean readInlineInputBoolean(JsonObject config, PortDefinition port) {
		PortInlineWidget inlineWidget = port.inlineWidget();
		if (inlineWidget == null) {
			return false;
		}
		JsonObject inlineInputs = inlineInputs(config, false);
		JsonElement value = inlineInputs != null && inlineInputs.has(port.id().value())
			? inlineInputs.get(port.id().value())
			: inlineWidget.defaultValue();
		try {
			return value.getAsBoolean();
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid inline input value for " + port.id().value());
		}
	}

	public static String readTextControlValue(JsonObject config, NodeEditorControl.TextControl control) {
		JsonElement value = config.get(control.key());
		try {
			return value != null ? value.getAsString() : control.defaultValue();
		} catch (RuntimeException exception) {
			return control.defaultValue();
		}
	}

	public static String readNumericTextControlValue(JsonObject config, NodeEditorControl.NumericTextControl control) {
		JsonElement value = config.get(control.key());
		try {
			return value != null ? value.getAsString() : control.defaultValue();
		} catch (RuntimeException exception) {
			return control.defaultValue();
		}
	}

	public static boolean readBooleanControlValue(JsonObject config, NodeEditorControl.BooleanControl control) {
		JsonElement value = config.get(control.key());
		try {
			return value != null ? value.getAsBoolean() : control.defaultValue();
		} catch (RuntimeException exception) {
			return control.defaultValue();
		}
	}

	public static String readCycleControlValue(JsonObject config, NodeEditorControl.CycleControl control) {
		JsonElement value = config.get(control.key());
		if (value == null) {
			return control.defaultOptionId();
		}
		try {
			String candidate = value.getAsString();
			boolean valid = control.options().stream().anyMatch(option -> option.id().equals(candidate));
			return valid ? candidate : control.defaultOptionId();
		} catch (RuntimeException exception) {
			return control.defaultOptionId();
		}
	}

	public static JsonPrimitive stringValue(String value) {
		return new JsonPrimitive(value);
	}

	public static JsonPrimitive intValue(int value) {
		return new JsonPrimitive(value);
	}

	public static JsonPrimitive longValue(long value) {
		return new JsonPrimitive(value);
	}

	public static JsonPrimitive doubleValue(double value) {
		return new JsonPrimitive(value);
	}

	public static JsonPrimitive numericLiteral(String value) {
		return new JsonPrimitive(NumericTypes.normalizeLiteralText(value));
	}

	public static JsonPrimitive numberValue(double value) {
		return doubleValue(value);
	}

	public static JsonPrimitive booleanValue(boolean value) {
		return new JsonPrimitive(value);
	}

	private static JsonObject inlineInputs(JsonObject config, boolean create) {
		if (config.has(INLINE_INPUTS_KEY) && config.get(INLINE_INPUTS_KEY).isJsonObject()) {
			return config.getAsJsonObject(INLINE_INPUTS_KEY);
		}
		if (!create) {
			return null;
		}
		JsonObject inlineInputs = new JsonObject();
		config.add(INLINE_INPUTS_KEY, inlineInputs);
		return inlineInputs;
	}
}
