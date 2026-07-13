package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;

import java.util.List;

public final class BuiltinNodePaletteRegistrar {
	private static final int CONSTANTS_ORDER = 100;
	private static final int OPERATIONS_ORDER = 200;
	private static final int EVENTS_ORDER = 300;
	private static final int DEBUG_ORDER = 400;
	private static final String CONSTANTS_KEY = "mcng.ui.palette.section.constants";
	private static final String OPERATIONS_KEY = "mcng.ui.palette.section.operations";
	private static final String CONTROL_KEY = "mcng.ui.palette.section.control";
	private static final String VARIABLES_KEY = "mcng.ui.palette.section.variables";
	private static final String EVENTS_KEY = "mcng.ui.palette.section.events";
	private static final String DEBUG_KEY = "mcng.ui.palette.section.debug";

	private BuiltinNodePaletteRegistrar() {
	}

	public static void registerCoreEntries(NodePaletteRegistry registry) {
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.NUMERIC_CONSTANT.id(), "Constants", CONSTANTS_KEY, CONSTANTS_ORDER, 10, List.of("number", "constant")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.BOOLEAN_CONSTANT.id(), "Constants", CONSTANTS_KEY, CONSTANTS_ORDER, 20, List.of("bool", "constant")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.STRING_CONSTANT.id(), "Constants", CONSTANTS_KEY, CONSTANTS_ORDER, 30, List.of("text", "constant")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.TYPE_CONSTANT.id(), "Constants", CONSTANTS_KEY, CONSTANTS_ORDER, 40, List.of("type", "class", "constant")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.ADD.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 10, List.of("math", "sum")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.SUBTRACT.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 20, List.of("math", "minus")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.MULTIPLY.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 30, List.of("math", "product")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.CAST.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 40, List.of("convert", "type")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.ROUND.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 50, List.of("math", "integer")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.CONCAT.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 60, List.of("string", "text")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.LESS_THAN.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 70, List.of("compare", "boolean")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.EQUALS.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 80, List.of("compare", "equal")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.SELECT.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 90, List.of("branch", "choose", "ternary")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.AND.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 100, List.of("boolean", "logic")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.OR.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 110, List.of("boolean", "logic")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.IDENTITY.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 120, List.of("generic", "pass through")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.LIST_CREATE.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 130, List.of("list", "array", "collection")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.LIST_APPEND.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 140, List.of("list", "append", "push")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.LIST_GET.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 150, List.of("list", "index", "array")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.MAP_PUT.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 160, List.of("map", "dict", "set")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.MAP_GET.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 170, List.of("map", "dict", "get")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.MAP_KEYS.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 180, List.of("map", "dict", "keys")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.TYPE_OF.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 190, List.of("type", "class", "inspect")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.CONVERT_TYPE.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 200, List.of("convert", "cast", "type")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.REROUTE.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 210, List.of("wire", "route")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.NOT.id(), "Operations", OPERATIONS_KEY, OPERATIONS_ORDER, 220, List.of("boolean", "logic")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.IF.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 10, List.of("branch", "condition")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.WHILE.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 20, List.of("loop", "iterate")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.MERGE.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 30, List.of("join", "merge", "union")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.GATE.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 40, List.of("gate", "open", "close", "switch")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.SEQUENCE.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 50, List.of("sequence", "step", "fanout")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.ONCE.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 60, List.of("once", "single", "do once")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.FLIP_FLOP.id(), "Control", CONTROL_KEY, OPERATIONS_ORDER + 10, 70, List.of("flipflop", "toggle", "alternate")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.GET_VARIABLE.id(), "Variables", VARIABLES_KEY, OPERATIONS_ORDER + 20, 10, List.of("state", "read")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.SET_VARIABLE.id(), "Variables", VARIABLES_KEY, OPERATIONS_ORDER + 20, 20, List.of("state", "write")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.LATCH.id(), "Variables", VARIABLES_KEY, OPERATIONS_ORDER + 20, 30, List.of("state", "memory", "local")));
	}

	public static void registerDebugEntries(NodePaletteRegistry registry) {
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.MANUAL_TRIGGER.id(), "Events", EVENTS_KEY, EVENTS_ORDER, 10, List.of("event", "trigger")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.DEBUG_OUTPUT.id(), "Debug", DEBUG_KEY, DEBUG_ORDER, 10, List.of("log", "print", "debug")));
		registry.register(new NodePaletteDefinition(BuiltinNodeTypes.IMAGE_PREVIEW.id(), "Debug", DEBUG_KEY, DEBUG_ORDER, 20, List.of("image", "preview", "demo")));
	}

	public static void registerAll(NodePaletteRegistry registry) {
		registerCoreEntries(registry);
		registerDebugEntries(registry);
	}
}
