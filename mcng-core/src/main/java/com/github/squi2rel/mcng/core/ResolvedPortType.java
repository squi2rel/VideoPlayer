package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record ResolvedPortType(
	PortDefinition definition,
	PortType<?> effectiveType,
	boolean unresolvedGeneric,
	boolean numericFamily
) {
	public ResolvedPortType {
		Objects.requireNonNull(definition, "definition");
		Objects.requireNonNull(effectiveType, "effectiveType");
	}

	public boolean unresolvedNumeric() {
		return numericFamily && MCNGPortTypes.ANY.equals(effectiveType);
	}
}
