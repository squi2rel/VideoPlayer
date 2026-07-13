package com.github.squi2rel.mcng.core.builtin;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.PortTypeRegistry;

public final class BuiltinPortTypeRegistrar {
	private BuiltinPortTypeRegistrar() {
	}

	public static void registerBaseTypes(PortTypeRegistry registry) {
		registry.registerType(MCNGPortTypes.ANY, MCNGPortTypes.ANY_COLOR);
	}

	public static void registerNumericTypes(PortTypeRegistry registry) {
		registry.registerType(MCNGPortTypes.INT, MCNGPortTypes.INT_COLOR);
		registry.registerType(MCNGPortTypes.LONG, MCNGPortTypes.LONG_COLOR);
		registry.registerType(MCNGPortTypes.DOUBLE, MCNGPortTypes.DOUBLE_COLOR);
	}

	public static void registerTextTypes(PortTypeRegistry registry) {
		registry.registerType(MCNGPortTypes.STRING, MCNGPortTypes.STRING_COLOR);
	}

	public static void registerBooleanTypes(PortTypeRegistry registry) {
		registry.registerType(MCNGPortTypes.BOOLEAN, MCNGPortTypes.BOOLEAN_COLOR);
	}

	public static void registerCollectionTypes(PortTypeRegistry registry) {
		registry.registerType(MCNGPortTypes.LIST, MCNGPortTypes.LIST_COLOR);
		registry.registerType(MCNGPortTypes.MAP, MCNGPortTypes.MAP_COLOR);
	}

	public static void registerMetaTypes(PortTypeRegistry registry) {
		registry.registerType(MCNGPortTypes.CLASS, MCNGPortTypes.CLASS_COLOR);
	}

	public static void registerCoreTypes(PortTypeRegistry registry) {
		registerBaseTypes(registry);
		registerNumericTypes(registry);
		registerTextTypes(registry);
		registerBooleanTypes(registry);
		registerCollectionTypes(registry);
		registerMetaTypes(registry);
	}
}
