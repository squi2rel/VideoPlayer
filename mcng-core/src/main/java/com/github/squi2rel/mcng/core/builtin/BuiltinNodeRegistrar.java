package com.github.squi2rel.mcng.core.builtin;

import com.github.squi2rel.mcng.core.NodeTypeRegistry;

public final class BuiltinNodeRegistrar {
	private BuiltinNodeRegistrar() {
	}

	public static void registerCoreNodes(NodeTypeRegistry registry) {
		registry.register(BuiltinNodeTypes.NUMERIC_CONSTANT);
		registry.register(BuiltinNodeTypes.BOOLEAN_CONSTANT);
		registry.register(BuiltinNodeTypes.STRING_CONSTANT);
		registry.register(BuiltinNodeTypes.TYPE_CONSTANT);
		registry.register(BuiltinNodeTypes.ADD);
		registry.register(BuiltinNodeTypes.SUBTRACT);
		registry.register(BuiltinNodeTypes.MULTIPLY);
		registry.register(BuiltinNodeTypes.CAST);
		registry.register(BuiltinNodeTypes.ROUND);
		registry.register(BuiltinNodeTypes.CONCAT);
		registry.register(BuiltinNodeTypes.LESS_THAN);
		registry.register(BuiltinNodeTypes.EQUALS);
		registry.register(BuiltinNodeTypes.SELECT);
		registry.register(BuiltinNodeTypes.AND);
		registry.register(BuiltinNodeTypes.OR);
		registry.register(BuiltinNodeTypes.IDENTITY);
		registry.register(BuiltinNodeTypes.LIST_CREATE);
		registry.register(BuiltinNodeTypes.LIST_APPEND);
		registry.register(BuiltinNodeTypes.LIST_GET);
		registry.register(BuiltinNodeTypes.MAP_PUT);
		registry.register(BuiltinNodeTypes.MAP_GET);
		registry.register(BuiltinNodeTypes.MAP_KEYS);
		registry.register(BuiltinNodeTypes.TYPE_OF);
		registry.register(BuiltinNodeTypes.CONVERT_TYPE);
		registry.register(BuiltinNodeTypes.REROUTE);
		registry.register(BuiltinNodeTypes.NOT);
		registry.register(BuiltinNodeTypes.IF);
		registry.register(BuiltinNodeTypes.MERGE);
		registry.register(BuiltinNodeTypes.GATE);
		registry.register(BuiltinNodeTypes.SEQUENCE);
		registry.register(BuiltinNodeTypes.ONCE);
		registry.register(BuiltinNodeTypes.FLIP_FLOP);
		registry.register(BuiltinNodeTypes.GET_VARIABLE);
		registry.register(BuiltinNodeTypes.SET_VARIABLE);
		registry.register(BuiltinNodeTypes.LATCH);
		registry.register(BuiltinNodeTypes.WHILE);
	}

	public static void registerDebugNodes(NodeTypeRegistry registry) {
		registry.register(BuiltinNodeTypes.MANUAL_TRIGGER);
		registry.register(BuiltinNodeTypes.DEBUG_OUTPUT);
		registry.register(BuiltinNodeTypes.IMAGE_PREVIEW);
	}

	public static void registerAll(NodeTypeRegistry registry) {
		registerCoreNodes(registry);
		registerDebugNodes(registry);
	}
}
