package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record PortDefinition(
	PortId id,
	String name,
	PortDirection direction,
	PortChannel channel,
	PortType<?> type,
	boolean required,
	PortInlineWidget inlineWidget,
	String genericGroupId,
	boolean numericFamily
) {
	public PortDefinition {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(type, "type");
		if (name.isBlank()) {
			throw new IllegalArgumentException("Port name must not be blank");
		}
		if (genericGroupId != null && genericGroupId.isBlank()) {
			throw new IllegalArgumentException("Port generic group id must not be blank");
		}
		if (channel == PortChannel.CONTROL) {
			if (inlineWidget != null) {
				throw new IllegalArgumentException("Control ports cannot define inline widgets");
			}
			if (genericGroupId != null) {
				throw new IllegalArgumentException("Control ports cannot be generic");
			}
			if (numericFamily) {
				throw new IllegalArgumentException("Control ports cannot be numeric-family ports");
			}
		}
	}

	public static PortDefinition input(String id, String name, PortType<?> type, boolean required) {
		return input(id, name, type, required, null, null, false);
	}

	public static PortDefinition input(String id, String name, PortType<?> type, boolean required, PortInlineWidget inlineWidget) {
		return input(id, name, type, required, inlineWidget, null, false);
	}

	public static PortDefinition input(String id, String name, PortType<?> type, boolean required, PortInlineWidget inlineWidget, String genericGroupId) {
		return input(id, name, type, required, inlineWidget, genericGroupId, false);
	}

	public static PortDefinition input(String id, String name, PortType<?> type, boolean required, PortInlineWidget inlineWidget, String genericGroupId, boolean numericFamily) {
		return new PortDefinition(new PortId(id), name, PortDirection.INPUT, PortChannel.DATA, type, required, inlineWidget, genericGroupId, numericFamily);
	}

	public static PortDefinition inputGeneric(String id, String name, boolean required, String genericGroupId) {
		return input(id, name, MCNGPortTypes.ANY, required, null, genericGroupId);
	}

	public static PortDefinition inputNumeric(String id, String name, boolean required, PortInlineWidget inlineWidget) {
		return input(id, name, MCNGPortTypes.ANY, required, inlineWidget, null, true);
	}

	public static PortDefinition inputNumericGeneric(String id, String name, boolean required, String genericGroupId) {
		return input(id, name, MCNGPortTypes.ANY, required, null, genericGroupId, true);
	}

	public static PortDefinition output(String id, String name, PortType<?> type) {
		return output(id, name, type, null, false);
	}

	public static PortDefinition output(String id, String name, PortType<?> type, String genericGroupId) {
		return output(id, name, type, genericGroupId, false);
	}

	public static PortDefinition output(String id, String name, PortType<?> type, String genericGroupId, boolean numericFamily) {
		return new PortDefinition(new PortId(id), name, PortDirection.OUTPUT, PortChannel.DATA, type, false, null, genericGroupId, numericFamily);
	}

	public static PortDefinition outputGeneric(String id, String name, String genericGroupId) {
		return output(id, name, MCNGPortTypes.ANY, genericGroupId);
	}

	public static PortDefinition outputNumeric(String id, String name) {
		return output(id, name, MCNGPortTypes.ANY, null, true);
	}

	public static PortDefinition outputNumericGeneric(String id, String name, String genericGroupId) {
		return output(id, name, MCNGPortTypes.ANY, genericGroupId, true);
	}

	public static PortDefinition controlInput(String id, String name) {
		return new PortDefinition(new PortId(id), name, PortDirection.INPUT, PortChannel.CONTROL, MCNGPortTypes.ANY, false, null, null, false);
	}

	public static PortDefinition controlOutput(String id, String name) {
		return new PortDefinition(new PortId(id), name, PortDirection.OUTPUT, PortChannel.CONTROL, MCNGPortTypes.ANY, false, null, null, false);
	}
}
