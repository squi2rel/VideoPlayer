package com.github.squi2rel.mcng.core.builtin;

import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.ControlRoute;
import com.github.squi2rel.mcng.core.DynamicPortTypeResolverContext;
import com.github.squi2rel.mcng.core.EdgeDefinition;
import com.github.squi2rel.mcng.core.ExecutionTriggerKind;
import com.github.squi2rel.mcng.core.GraphError;
import com.github.squi2rel.mcng.core.GraphErrorCode;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeConfigCodec;
import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodeControlMode;
import com.github.squi2rel.mcng.core.NodeEditorControl;
import com.github.squi2rel.mcng.core.NodeExecutionRequest;
import com.github.squi2rel.mcng.core.NodeExecutionResult;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodeKind;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.NodeVisualStyle;
import com.github.squi2rel.mcng.core.NumericTypes;
import com.github.squi2rel.mcng.core.PortDefinition;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortInlineWidget;
import com.github.squi2rel.mcng.core.PortType;
import com.github.squi2rel.mcng.core.ResolvedPortType;
import com.github.squi2rel.mcng.core.RuntimeValueUtils;
import com.github.squi2rel.mcng.core.RuntimeValueConstraintException;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BuiltinNodeTypes {
	public static final PortId VALUE_PORT = new PortId("value");
	public static final PortId LEFT_PORT = new PortId("left");
	public static final PortId RIGHT_PORT = new PortId("right");
	public static final PortId NUMBER_PORT = new PortId("number");
	public static final PortId MESSAGE_PORT = new PortId("message");
	public static final PortId BOOLEAN_PORT = new PortId("flag");
	public static final PortId CONTROL_IN_PORT = new PortId("in");
	public static final PortId CONTROL_OUT_PORT = new PortId("out");
	public static final PortId CONTROL_OUT_A_PORT = new PortId("outA");
	public static final PortId CONTROL_OUT_B_PORT = new PortId("outB");
	public static final PortId STATE_PORT = new PortId("state");
	public static final PortId BODY_PORT = new PortId("body");
	public static final PortId TRUE_PORT = new PortId("true");
	public static final PortId FALSE_PORT = new PortId("false");
	public static final PortId RESET_PORT = new PortId("reset");
	public static final PortId OPEN_PORT = new PortId("open");
	public static final PortId CLOSE_PORT = new PortId("close");
	public static final PortId TOGGLE_PORT = new PortId("toggle");
	public static final PortId STORE_A_PORT = new PortId("storeA");
	public static final PortId STORE_B_PORT = new PortId("storeB");
	public static final PortId VALUE_A_PORT = new PortId("valueA");
	public static final PortId VALUE_B_PORT = new PortId("valueB");
	public static final PortId DONE_PORT = new PortId("done");
	public static final PortId A_PORT = new PortId("a");
	public static final PortId B_PORT = new PortId("b");
	public static final PortId FIRST_PORT = new PortId("first");
	public static final PortId SECOND_PORT = new PortId("second");
	public static final PortId THIRD_PORT = new PortId("third");
	public static final PortId WHEN_TRUE_PORT = new PortId("whenTrue");
	public static final PortId WHEN_FALSE_PORT = new PortId("whenFalse");
	public static final PortId LIST_PORT = new PortId("list");
	public static final PortId INDEX_PORT = new PortId("index");
	public static final PortId ITEM_PORT = new PortId("item");
	public static final PortId MAP_PORT = new PortId("map");
	public static final PortId KEY_PORT = new PortId("key");
	public static final PortId TYPE_PORT = new PortId("type");

	private static final String VALUE_GENERIC_GROUP = "value";
	private static final String CONVERT_OUTPUT_GENERIC_GROUP = "converted";
	private static final String NUMERIC_VALUE_KEY = "value";
	private static final String BOOLEAN_VALUE_KEY = "value";
	private static final String CAST_MODE_KEY = "mode";
	private static final String ROUND_MODE_KEY = "mode";
	private static final String VARIABLE_ID_KEY = "variableId";
	private static final String TYPE_ID_KEY = "typeId";
	private static final String IMAGE_PREVIEW_FILE_PATH_KEY = "filePath";
	private static final String INPUT_COUNT_KEY = "inputCount";
	private static final String OUTPUT_COUNT_KEY = "outputCount";
	private static final String INITIAL_OPEN_KEY = "initialOpen";
	private static final String LATCH_SLOT = "value";
	private static final String GATE_STATE_SLOT = "gateState";
	private static final String ONCE_TRIGGERED_SLOT = "onceTriggered";
	private static final String FLIP_FLOP_NEXT_A_SLOT = "flipFlopNextA";
	private static final String MODE_AUTO = "auto";
	private static final String MODE_INT = "int";
	private static final String MODE_LONG = "long";
	private static final String MODE_DOUBLE = "double";
	private static final int DYNAMIC_PORT_MIN = 2;
	private static final int DYNAMIC_PORT_MAX = 16;

	private static final List<NodeEditorControl.Option> MANUAL_TRIGGER_STYLE_OPTIONS = List.of(
		new NodeEditorControl.Option("plain", "Plain"),
		new NodeEditorControl.Option("boxed", "Boxed"),
		new NodeEditorControl.Option("burst", "Burst")
	);
	private static final List<NodeEditorControl.Option> CAST_MODE_OPTIONS = List.of(
		new NodeEditorControl.Option(MODE_AUTO, "Auto"),
		new NodeEditorControl.Option(MODE_INT, "Int"),
		new NodeEditorControl.Option(MODE_LONG, "Long"),
		new NodeEditorControl.Option(MODE_DOUBLE, "Double")
	);
	private static final List<NodeEditorControl.Option> ROUND_MODE_OPTIONS = List.of(
		new NodeEditorControl.Option(MODE_INT, "Int"),
		new NodeEditorControl.Option(MODE_LONG, "Long")
	);
	private static final List<NodeEditorControl.Option> DYNAMIC_PORT_COUNT_OPTIONS = buildDynamicPortCountOptions();
	private static final NodeEditorControl.NumericTextControl NUMERIC_CONSTANT_VALUE_CONTROL = new NodeEditorControl.NumericTextControl(NUMERIC_VALUE_KEY, "Value", "1");
	private static final NodeEditorControl.BooleanControl BOOLEAN_CONSTANT_VALUE_CONTROL = new NodeEditorControl.BooleanControl(BOOLEAN_VALUE_KEY, "Value", false);
	private static final NodeEditorControl.TextControl MANUAL_TRIGGER_LABEL_CONTROL = new NodeEditorControl.TextControl("label", "Label", "trigger");
	private static final NodeEditorControl.BooleanControl MANUAL_TRIGGER_UPPERCASE_CONTROL = new NodeEditorControl.BooleanControl("uppercase", "Uppercase", false);
	private static final NodeEditorControl.CycleControl MANUAL_TRIGGER_STYLE_CONTROL = new NodeEditorControl.CycleControl("style", "Style", MANUAL_TRIGGER_STYLE_OPTIONS, "plain");
	private static final NodeEditorControl.CycleControl CAST_MODE_CONTROL = new NodeEditorControl.CycleControl(CAST_MODE_KEY, "Target", CAST_MODE_OPTIONS, MODE_AUTO);
	private static final NodeEditorControl.CycleControl ROUND_MODE_CONTROL = new NodeEditorControl.CycleControl(ROUND_MODE_KEY, "Target", ROUND_MODE_OPTIONS, MODE_INT);
	private static final NodeEditorControl.TextControl VARIABLE_ID_CONTROL = new NodeEditorControl.TextControl(VARIABLE_ID_KEY, "Variable", "var_1");
	private static final NodeEditorControl.TextControl TYPE_CONSTANT_TYPE_ID_CONTROL = new NodeEditorControl.TextControl(TYPE_ID_KEY, "Type", MCNGPortTypes.STRING.id());
	private static final NodeEditorControl.CycleControl MERGE_INPUT_COUNT_CONTROL = new NodeEditorControl.CycleControl(INPUT_COUNT_KEY, "Inputs", DYNAMIC_PORT_COUNT_OPTIONS, String.valueOf(DYNAMIC_PORT_MIN));
	private static final NodeEditorControl.CycleControl SEQUENCE_OUTPUT_COUNT_CONTROL = new NodeEditorControl.CycleControl(OUTPUT_COUNT_KEY, "Outputs", DYNAMIC_PORT_COUNT_OPTIONS, String.valueOf(DYNAMIC_PORT_MIN));
	private static final NodeEditorControl.BooleanControl GATE_INITIAL_OPEN_CONTROL = new NodeEditorControl.BooleanControl(INITIAL_OPEN_KEY, "Initial Open", false);
	public static final NodeType<NumericConstantConfig> NUMERIC_CONSTANT = new SimpleNodeType<>(
		"mcng:numeric_constant",
		"Numeric Constant",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(),
		List.of(PortDefinition.outputNumeric("value", "Value")),
		new NumericConstantConfig("1"),
		new NumericConstantCodec(),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, NumericTypes.parseLiteral(request.config().value()).value()))
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(NUMERIC_CONSTANT_VALUE_CONTROL);
		}
	};

	public static final NodeType<BooleanConstantConfig> BOOLEAN_CONSTANT = new SimpleNodeType<>(
		"mcng:boolean_constant",
		"Boolean Constant",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.BOOLEAN)),
		new BooleanConstantConfig(false),
		new BooleanConstantCodec(),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.config().value()))
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(BOOLEAN_CONSTANT_VALUE_CONTROL);
		}
	};

	public static final NodeType<StringConstantConfig> STRING_CONSTANT = new SimpleNodeType<>(
		"mcng:string_constant",
		"String Constant",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.STRING)),
		new StringConstantConfig("hello"),
		new StringConstantCodec(),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.config().value()))
	);

	public static final NodeType<TypeConstantConfig> TYPE_CONSTANT = new SimpleNodeType<>(
		"mcng:type_constant",
		"Port Type",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.CLASS)),
		new TypeConstantConfig(MCNGPortTypes.STRING.id()),
		new TypeConstantCodec(),
		request -> {
			String typeId = request.config().typeId().trim();
			PortType<?> type = request.portTypes().findType(typeId).orElse(null);
			if (type == null) {
				return error(GraphErrorCode.UNKNOWN_PORT_TYPE, "Unknown port type: " + typeId, request.node().id(), VALUE_PORT);
			}
			return NodeExecutionResult.of(Map.of(VALUE_PORT, type));
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(TYPE_CONSTANT_TYPE_ID_CONTROL);
		}
	};

	public static final NodeType<EmptyConfig> ADD = new SimpleNodeType<>(
		"mcng:add",
		"Add",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputNumeric("left", "Left", true, PortInlineWidget.numericText("0.0")),
			PortDefinition.inputNumeric("right", "Right", true, PortInlineWidget.numericText("0.0"))
		),
		List.of(PortDefinition.outputNumeric("value", "Value")),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, NumericTypes.add(request.inputs().get(LEFT_PORT), request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<EmptyConfig> SUBTRACT = new SimpleNodeType<>(
		"mcng:subtract",
		"Subtract",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputNumeric("left", "Left", true, PortInlineWidget.numericText("0.0")),
			PortDefinition.inputNumeric("right", "Right", true, PortInlineWidget.numericText("0.0"))
		),
		List.of(PortDefinition.outputNumeric("value", "Value")),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, NumericTypes.subtract(request.inputs().get(LEFT_PORT), request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<EmptyConfig> MULTIPLY = new SimpleNodeType<>(
		"mcng:multiply",
		"Multiply",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputNumeric("left", "Left", true, PortInlineWidget.numericText("1.0")),
			PortDefinition.inputNumeric("right", "Right", true, PortInlineWidget.numericText("1.0"))
		),
		List.of(PortDefinition.outputNumeric("value", "Value")),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, NumericTypes.multiply(request.inputs().get(LEFT_PORT), request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<CastConfig> CAST = new SimpleNodeType<>(
		"mcng:cast",
		"Cast",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(PortDefinition.inputNumeric("value", "Value", true, null)),
		List.of(PortDefinition.outputNumeric("value", "Value")),
		new CastConfig(MODE_AUTO),
		new CastConfigCodec(),
		request -> {
			Object value = request.inputs().get(VALUE_PORT);
			PortType<?> targetType = resolveCastTargetType(request, request.config().mode());
			return NodeExecutionResult.of(Map.of(VALUE_PORT, NumericTypes.cast(value, targetType)));
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(CAST_MODE_CONTROL);
		}
	};

	public static final NodeType<RoundConfig> ROUND = new SimpleNodeType<>(
		"mcng:round",
		"Round",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(PortDefinition.inputNumeric("value", "Value", true, null)),
		List.of(PortDefinition.outputNumeric("value", "Value")),
		new RoundConfig(MODE_INT),
		new RoundConfigCodec(),
		request -> {
			Object value = request.inputs().get(VALUE_PORT);
			PortType<?> targetType = portTypeForMode(request.config().mode(), false);
			return NodeExecutionResult.of(Map.of(VALUE_PORT, NumericTypes.round(value, targetType)));
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(ROUND_MODE_CONTROL);
		}
	};

	public static final NodeType<EmptyConfig> CONCAT = new SimpleNodeType<>(
		"mcng:concat",
		"Concat",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("left", "Left", MCNGPortTypes.STRING, true, PortInlineWidget.stringText("")),
			PortDefinition.input("right", "Right", MCNGPortTypes.STRING, true, PortInlineWidget.stringText(""))
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.STRING)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, String.valueOf(request.inputs().get(LEFT_PORT)) + request.inputs().get(RIGHT_PORT)))
	);

	public static final NodeType<EmptyConfig> LESS_THAN = new SimpleNodeType<>(
		"mcng:less_than",
		"Less Than",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputNumeric("left", "Left", true, PortInlineWidget.numericText("0")),
			PortDefinition.inputNumeric("right", "Right", true, PortInlineWidget.numericText("0"))
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.BOOLEAN)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, numericLessThan(request.inputs().get(LEFT_PORT), request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<EmptyConfig> EQUALS = new SimpleNodeType<>(
		"mcng:equals",
		"Equals",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputGeneric("left", "Left", true, VALUE_GENERIC_GROUP),
			PortDefinition.inputGeneric("right", "Right", true, VALUE_GENERIC_GROUP)
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.BOOLEAN)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, valuesEqual(request.inputs().get(LEFT_PORT), request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<EmptyConfig> SELECT = new SimpleNodeType<>(
		"mcng:select",
		"Select",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("flag", "Flag", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false)),
			PortDefinition.input("whenTrue", "When True", MCNGPortTypes.ANY, false, null, VALUE_GENERIC_GROUP),
			PortDefinition.input("whenFalse", "When False", MCNGPortTypes.ANY, false, null, VALUE_GENERIC_GROUP)
		),
		List.of(PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(
			VALUE_PORT,
			Boolean.TRUE.equals(request.inputs().get(BOOLEAN_PORT))
				? request.inputs().get(WHEN_TRUE_PORT)
				: request.inputs().get(WHEN_FALSE_PORT)
		))
	);

	public static final NodeType<EmptyConfig> AND = new SimpleNodeType<>(
		"mcng:and",
		"And",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("left", "Left", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false)),
			PortDefinition.input("right", "Right", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false))
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.BOOLEAN)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, Boolean.TRUE.equals(request.inputs().get(LEFT_PORT)) && Boolean.TRUE.equals(request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<EmptyConfig> OR = new SimpleNodeType<>(
		"mcng:or",
		"Or",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("left", "Left", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false)),
			PortDefinition.input("right", "Right", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false))
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.BOOLEAN)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, Boolean.TRUE.equals(request.inputs().get(LEFT_PORT)) || Boolean.TRUE.equals(request.inputs().get(RIGHT_PORT))))
	);

	public static final NodeType<ManualTriggerConfig> MANUAL_TRIGGER = new SimpleNodeType<>(
		"mcng:manual_trigger",
		"Manual Trigger",
		NodeKind.EVENT_SOURCE,
		NodeControlMode.NONE,
		List.of(),
		List.of(
			PortDefinition.controlOutput("out", "Out"),
			PortDefinition.output("number", "Number", MCNGPortTypes.LONG),
			PortDefinition.output("message", "Message", MCNGPortTypes.STRING)
		),
		new ManualTriggerConfig("trigger", false, "plain"),
		new ManualTriggerConfigCodec(),
		request -> {
			long value = request.trigger().kind() == ExecutionTriggerKind.EVENT && request.trigger().payload() instanceof Number number
				? number.longValue()
				: 0L;
			String base = request.config().label() + " #" + value;
			if (request.config().uppercase()) {
				base = base.toUpperCase(Locale.ROOT);
			}
			String message = switch (request.config().style()) {
				case "boxed" -> "[" + base + "]";
				case "burst" -> base + "!";
				default -> base;
			};
			List<PortId> activated = request.trigger().kind() == ExecutionTriggerKind.EVENT ? List.of(CONTROL_OUT_PORT) : List.of();
			return NodeExecutionResult.control(Map.of(NUMBER_PORT, value, MESSAGE_PORT, message), activated);
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(MANUAL_TRIGGER_LABEL_CONTROL, MANUAL_TRIGGER_UPPERCASE_CONTROL, MANUAL_TRIGGER_STYLE_CONTROL);
		}
	};

	public static final NodeType<EmptyConfig> IDENTITY = new SimpleNodeType<>(
		"mcng:identity",
		"Identity",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(PortDefinition.inputGeneric("value", "Value", true, VALUE_GENERIC_GROUP)),
		List.of(PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.inputs().get(VALUE_PORT)))
	);

	public static final NodeType<EmptyConfig> LIST_CREATE = new SimpleNodeType<>(
		"mcng:list_create",
		"Make List",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputGeneric("first", "First", false, VALUE_GENERIC_GROUP),
			PortDefinition.inputGeneric("second", "Second", false, VALUE_GENERIC_GROUP),
			PortDefinition.inputGeneric("third", "Third", false, VALUE_GENERIC_GROUP)
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.LIST)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			List<Object> values = new ArrayList<>(3);
			appendPresentValue(values, request.inputs(), FIRST_PORT);
			appendPresentValue(values, request.inputs(), SECOND_PORT);
			appendPresentValue(values, request.inputs(), THIRD_PORT);
			return NodeExecutionResult.of(Map.of(VALUE_PORT, values));
		}
	);

	public static final NodeType<EmptyConfig> LIST_APPEND = new SimpleNodeType<>(
		"mcng:list_append",
		"List Append",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("list", "List", MCNGPortTypes.LIST, false),
			PortDefinition.inputGeneric("item", "Item", false, VALUE_GENERIC_GROUP)
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.LIST)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			List<Object> values = request.inputs().containsKey(LIST_PORT)
				? new ArrayList<>(RuntimeValueUtils.copyList(request.inputs().get(LIST_PORT), "List Append"))
				: new ArrayList<>();
			if (request.inputs().containsKey(ITEM_PORT)) {
				values.add(RuntimeValueUtils.copyIfContainer(request.inputs().get(ITEM_PORT)));
			}
			return NodeExecutionResult.of(Map.of(VALUE_PORT, values));
		}
	);

	public static final NodeType<EmptyConfig> LIST_GET = new SimpleNodeType<>(
		"mcng:list_get",
		"List Get",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("list", "List", MCNGPortTypes.LIST, true),
			PortDefinition.inputNumeric("index", "Index", true, PortInlineWidget.numericText("0"))
		),
		List.of(PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			List<Object> values = RuntimeValueUtils.requireList(request.inputs().get(LIST_PORT), "List Get");
			int index = numericIndex(request.inputs().get(INDEX_PORT));
			Object value = index >= 0 && index < values.size() ? RuntimeValueUtils.copyIfContainer(values.get(index)) : null;
			return NodeExecutionResult.of(Map.of(VALUE_PORT, value));
		}
	);

	public static final NodeType<EmptyConfig> MAP_PUT = new SimpleNodeType<>(
		"mcng:map_put",
		"Map Put",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("map", "Map", MCNGPortTypes.MAP, false),
			PortDefinition.input("key", "Key", MCNGPortTypes.STRING, true, PortInlineWidget.stringText("key")),
			PortDefinition.inputGeneric("value", "Value", false, VALUE_GENERIC_GROUP)
		),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.MAP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			LinkedHashMap<String, Object> map = request.inputs().containsKey(MAP_PORT)
				? new LinkedHashMap<>(RuntimeValueUtils.copyMap(request.inputs().get(MAP_PORT), "Map Put"))
				: new LinkedHashMap<>();
			map.put((String) request.inputs().get(KEY_PORT), RuntimeValueUtils.copyIfContainer(request.inputs().get(VALUE_PORT)));
			return NodeExecutionResult.of(Map.of(VALUE_PORT, map));
		}
	);

	public static final NodeType<EmptyConfig> MAP_GET = new SimpleNodeType<>(
		"mcng:map_get",
		"Map Get",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.input("map", "Map", MCNGPortTypes.MAP, true),
			PortDefinition.input("key", "Key", MCNGPortTypes.STRING, true, PortInlineWidget.stringText("key"))
		),
		List.of(PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			Map<String, Object> map = RuntimeValueUtils.requireMap(request.inputs().get(MAP_PORT), "Map Get");
			return NodeExecutionResult.of(Map.of(VALUE_PORT, RuntimeValueUtils.copyIfContainer(map.get(request.inputs().get(KEY_PORT)))));
		}
	);

	public static final NodeType<EmptyConfig> MAP_KEYS = new SimpleNodeType<>(
		"mcng:map_keys",
		"Map Keys",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(PortDefinition.input("map", "Map", MCNGPortTypes.MAP, true)),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.LIST)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			Map<String, Object> map = RuntimeValueUtils.requireMap(request.inputs().get(MAP_PORT), "Map Keys");
			return NodeExecutionResult.of(Map.of(VALUE_PORT, new ArrayList<>(map.keySet())));
		}
	);

	public static final NodeType<EmptyConfig> TYPE_OF = new SimpleNodeType<>(
		"mcng:type_of",
		"Type Of",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(PortDefinition.inputGeneric("value", "Value", false, VALUE_GENERIC_GROUP)),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.CLASS)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(
			VALUE_PORT,
			request.portTypes().typeOfValue(request.inputs().get(VALUE_PORT)).orElse(MCNGPortTypes.ANY)
		))
	);

	public static final NodeType<EmptyConfig> CONVERT_TYPE = new SimpleNodeType<>(
		"mcng:convert_type",
		"Convert Type",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(
			PortDefinition.inputGeneric("value", "Value", false, VALUE_GENERIC_GROUP),
			PortDefinition.input("type", "Type", MCNGPortTypes.CLASS, true)
		),
		List.of(PortDefinition.outputGeneric("value", "Value", CONVERT_OUTPUT_GENERIC_GROUP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			Object value = request.inputs().get(VALUE_PORT);
			if (value == null) {
				return NodeExecutionResult.of(Map.of(VALUE_PORT, null));
			}
			Object targetValue = request.inputs().get(TYPE_PORT);
			if (!(targetValue instanceof PortType<?> targetType)) {
				return error(GraphErrorCode.UNKNOWN_PORT_TYPE, "Missing target port type", request.node().id(), TYPE_PORT);
			}
			PortType<?> sourceType = request.portTypes().typeOfValue(value).orElse(null);
			if (sourceType == null) {
				return error(GraphErrorCode.UNKNOWN_PORT_TYPE, "Unknown runtime value type: " + value.getClass().getName(), request.node().id(), VALUE_PORT);
			}
			return NodeExecutionResult.of(Map.of(VALUE_PORT, request.portTypes().convert(RuntimeValueUtils.copyIfContainer(value), sourceType, targetType)));
		}
	);

	public static final NodeType<EmptyConfig> DEBUG_OUTPUT = new SimpleNodeType<>(
		"mcng:debug_output",
		"Debug Output",
		NodeKind.COMPUTE,
		NodeControlMode.OPTIONAL,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.inputGeneric("value", "Value", true, VALUE_GENERIC_GROUP)
		),
		List.of(
			PortDefinition.controlOutput("out", "Out"),
			PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)
		),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			Object value = request.inputs().get(VALUE_PORT);
			boolean controlDriven = request.hasConnectedControlInput(CONTROL_IN_PORT);
			boolean shouldPublish = request.trigger().kind() == ExecutionTriggerKind.CONTROL || !controlDriven;
			if (shouldPublish) {
				request.context().publishDebug(request.node().id(), String.valueOf(value));
			}
			List<PortId> activated = request.trigger().kind() == ExecutionTriggerKind.CONTROL ? List.of(CONTROL_OUT_PORT) : List.of();
			return NodeExecutionResult.control(Map.of(VALUE_PORT, value), activated);
		}
	);

	public static final NodeType<ImagePreviewConfig> IMAGE_PREVIEW = new SimpleNodeType<>(
		"mcng:image_preview",
		"Image Preview",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(),
		List.of(),
		new ImagePreviewConfig(""),
		new ImagePreviewConfigCodec(),
		request -> NodeExecutionResult.of(Map.of())
	);

	public static final NodeType<EmptyConfig> REROUTE = new SimpleNodeType<>(
		"mcng:reroute",
		"Reroute",
		NodeKind.COMPUTE,
		NodeControlMode.OPTIONAL,
		List.of(PortDefinition.inputGeneric("value", "Value", false, VALUE_GENERIC_GROUP)),
		List.of(PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			Object value = request.inputs().get(VALUE_PORT);
			List<PortId> activated = request.trigger().kind() == ExecutionTriggerKind.CONTROL ? List.of(VALUE_PORT) : List.of();
			if (value == null) {
				return NodeExecutionResult.control(activated);
			}
			return NodeExecutionResult.control(Map.of(VALUE_PORT, value), activated);
		}
	) {
		@Override
		public NodeVisualStyle visualStyle() {
			return NodeVisualStyle.COMPACT_REROUTE;
		}

		@Override
		public List<ControlRoute> controlRoutes(NodeInstance node) {
			return List.of(new ControlRoute(VALUE_PORT, VALUE_PORT));
		}
	};

	public static final NodeType<EmptyConfig> NOT = new SimpleNodeType<>(
		"mcng:not",
		"Not",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(PortDefinition.input("flag", "Flag", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false))),
		List.of(PortDefinition.output("value", "Value", MCNGPortTypes.BOOLEAN)),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, !Boolean.TRUE.equals(request.inputs().get(BOOLEAN_PORT))))
	);

	public static final NodeType<EmptyConfig> IF = new SimpleNodeType<>(
		"mcng:if",
		"If",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.input("flag", "Condition", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false))
		),
		List.of(
			PortDefinition.controlOutput("true", "True"),
			PortDefinition.controlOutput("false", "False")
		),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> Boolean.TRUE.equals(request.inputs().get(BOOLEAN_PORT))
			? NodeExecutionResult.control(List.of(TRUE_PORT))
			: NodeExecutionResult.control(List.of(FALSE_PORT))
	);

	public static final NodeType<MergeConfig> MERGE = new SimpleNodeType<>(
		"mcng:merge",
		"Merge",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		mergeInputs(DYNAMIC_PORT_MIN),
		List.of(PortDefinition.controlOutput("out", "Out")),
		new MergeConfig(DYNAMIC_PORT_MIN),
		new MergeConfigCodec(),
		request -> request.activeControlInputs().isEmpty()
			? NodeExecutionResult.control(List.of())
			: NodeExecutionResult.control(List.of(CONTROL_OUT_PORT))
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(MERGE_INPUT_COUNT_CONTROL);
		}

		@Override
		public List<PortDefinition> inputs(NodeInstance node) {
			return mergeInputs(readMergeInputCount(node.config()));
		}
	};

	public static final NodeType<GateConfig> GATE = new SimpleNodeType<>(
		"mcng:gate",
		"Gate",
		NodeKind.COMPUTE,
		NodeControlMode.OPTIONAL,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.controlInput("open", "Open"),
			PortDefinition.controlInput("close", "Close"),
			PortDefinition.controlInput("toggle", "Toggle")
		),
		List.of(
			PortDefinition.controlOutput("out", "Out"),
			PortDefinition.output("state", "State", MCNGPortTypes.BOOLEAN)
		),
		new GateConfig(false),
		new GateConfigCodec(),
		request -> {
			boolean open = currentBooleanState(request.runtime().readLocalState(request.node().id(), GATE_STATE_SLOT), request.config().initialOpen());
			if (request.trigger().kind() == ExecutionTriggerKind.CONTROL) {
				if (request.isControlInputActive(OPEN_PORT)) {
					open = true;
				}
				if (request.isControlInputActive(CLOSE_PORT)) {
					open = false;
				}
				if (request.isControlInputActive(TOGGLE_PORT)) {
					open = !open;
				}
				request.runtime().writeLocalState(request.node().id(), GATE_STATE_SLOT, open);
				boolean pass = request.isControlInputActive(CONTROL_IN_PORT) || !request.hasConnectedControlInput(CONTROL_IN_PORT);
				return NodeExecutionResult.control(Map.of(STATE_PORT, open), pass && open ? List.of(CONTROL_OUT_PORT) : List.of());
			}
			return NodeExecutionResult.of(Map.of(STATE_PORT, open));
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(GATE_INITIAL_OPEN_CONTROL);
		}

		@Override
		public List<ControlRoute> controlRoutes(NodeInstance node) {
			return List.of(new ControlRoute(CONTROL_IN_PORT, CONTROL_OUT_PORT));
		}
	};

	public static final NodeType<SequenceConfig> SEQUENCE = new SimpleNodeType<>(
		"mcng:sequence",
		"Sequence",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		List.of(PortDefinition.controlInput("in", "In")),
		sequenceOutputs(DYNAMIC_PORT_MIN),
		new SequenceConfig(DYNAMIC_PORT_MIN),
		new SequenceConfigCodec(),
		request -> isPrimaryControlTriggered(request)
			? NodeExecutionResult.control(sequenceOutputIds(request.config().outputCount()))
			: NodeExecutionResult.control(List.of())
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(SEQUENCE_OUTPUT_COUNT_CONTROL);
		}

		@Override
		public List<PortDefinition> outputs(NodeInstance node) {
			return sequenceOutputs(readSequenceOutputCount(node.config()));
		}
	};

	public static final NodeType<EmptyConfig> ONCE = new SimpleNodeType<>(
		"mcng:once",
		"Once",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.controlInput("reset", "Reset")
		),
		List.of(PortDefinition.controlOutput("out", "Out")),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			boolean fired = currentBooleanState(request.runtime().readLocalState(request.node().id(), ONCE_TRIGGERED_SLOT), false);
			if (request.trigger().kind() != ExecutionTriggerKind.CONTROL) {
				return NodeExecutionResult.control(List.of());
			}
			if (request.isControlInputActive(RESET_PORT)) {
				fired = false;
			}
			boolean pass = isPrimaryControlTriggered(request) && !fired;
			if (pass) {
				fired = true;
			}
			request.runtime().writeLocalState(request.node().id(), ONCE_TRIGGERED_SLOT, fired);
			return NodeExecutionResult.control(pass ? List.of(CONTROL_OUT_PORT) : List.of());
		}
	) {
		@Override
		public List<ControlRoute> controlRoutes(NodeInstance node) {
			return List.of(new ControlRoute(CONTROL_IN_PORT, CONTROL_OUT_PORT));
		}
	};

	public static final NodeType<EmptyConfig> FLIP_FLOP = new SimpleNodeType<>(
		"mcng:flip_flop",
		"Flip-Flop",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.controlInput("reset", "Reset")
		),
		List.of(
			PortDefinition.controlOutput("a", "A"),
			PortDefinition.controlOutput("b", "B")
		),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			boolean nextA = currentBooleanState(request.runtime().readLocalState(request.node().id(), FLIP_FLOP_NEXT_A_SLOT), true);
			if (request.trigger().kind() != ExecutionTriggerKind.CONTROL) {
				return NodeExecutionResult.control(List.of());
			}
			if (request.isControlInputActive(RESET_PORT)) {
				nextA = true;
			}
			List<PortId> activated = List.of();
			if (isPrimaryControlTriggered(request)) {
				activated = List.of(nextA ? A_PORT : B_PORT);
				nextA = !nextA;
			}
			request.runtime().writeLocalState(request.node().id(), FLIP_FLOP_NEXT_A_SLOT, nextA);
			return NodeExecutionResult.control(activated);
		}
	) {
		@Override
		public List<ControlRoute> controlRoutes(NodeInstance node) {
			return List.of(
				new ControlRoute(CONTROL_IN_PORT, A_PORT),
				new ControlRoute(CONTROL_IN_PORT, B_PORT)
			);
		}
	};

	public static final NodeType<VariableReferenceConfig> GET_VARIABLE = new SimpleNodeType<>(
		"mcng:get_variable",
		"Get Variable",
		NodeKind.COMPUTE,
		NodeControlMode.NONE,
		List.of(),
		List.of(PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)),
		new VariableReferenceConfig("var_1"),
		new VariableReferenceConfigCodec(),
		request -> {
			String variableId = request.config().variableId();
			if (!request.runtime().hasVariable(variableId)) {
				return error(GraphErrorCode.UNKNOWN_VARIABLE, "Unknown variable: " + variableId, request.node().id(), null);
			}
			return NodeExecutionResult.of(Map.of(VALUE_PORT, request.runtime().readVariable(variableId)));
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(VARIABLE_ID_CONTROL);
		}
	};

	public static final NodeType<VariableReferenceConfig> SET_VARIABLE = new SimpleNodeType<>(
		"mcng:set_variable",
		"Set Variable",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.inputGeneric("value", "Value", true, VALUE_GENERIC_GROUP)
		),
		List.of(
			PortDefinition.controlOutput("out", "Out"),
			PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)
		),
		new VariableReferenceConfig("var_1"),
		new VariableReferenceConfigCodec(),
		request -> {
			String variableId = request.config().variableId();
			if (!request.runtime().hasVariable(variableId)) {
				return error(GraphErrorCode.UNKNOWN_VARIABLE, "Unknown variable: " + variableId, request.node().id(), null);
			}
			Object value = request.inputs().get(VALUE_PORT);
			try {
				request.runtime().writeVariable(variableId, value);
			} catch (RuntimeValueConstraintException exception) {
				return error(GraphErrorCode.VALUE_LIMIT_EXCEEDED, exception.getMessage(), request.node().id(), VALUE_PORT);
			} catch (IllegalArgumentException exception) {
				return error(GraphErrorCode.INVALID_VARIABLE_VALUE, exception.getMessage(), request.node().id(), VALUE_PORT);
			}
			return NodeExecutionResult.control(Map.of(VALUE_PORT, value), List.of(CONTROL_OUT_PORT));
		}
	) {
		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(VARIABLE_ID_CONTROL);
		}
	};

	public static final NodeType<EmptyConfig> LATCH = new SimpleNodeType<>(
		"mcng:latch",
		"Latch",
		NodeKind.COMPUTE,
		NodeControlMode.OPTIONAL,
		List.of(
			PortDefinition.controlInput("storeA", "Store A"),
			PortDefinition.inputGeneric("valueA", "Value A", false, VALUE_GENERIC_GROUP),
			PortDefinition.controlInput("storeB", "Store B"),
			PortDefinition.inputGeneric("valueB", "Value B", false, VALUE_GENERIC_GROUP)
		),
		List.of(
			PortDefinition.controlOutput("outA", "Out A"),
			PortDefinition.controlOutput("outB", "Out B"),
			PortDefinition.outputGeneric("value", "Value", VALUE_GENERIC_GROUP)
		),
		EmptyConfig.INSTANCE,
		EmptyConfig.CODEC,
		request -> {
			Object current = request.runtime().readLocalState(request.node().id(), LATCH_SLOT);
			if (request.trigger().kind() == ExecutionTriggerKind.CONTROL) {
				List<PortId> activated = new java.util.ArrayList<>(2);
				if (request.isControlInputActive(STORE_A_PORT)) {
					current = request.inputs().get(VALUE_A_PORT);
					request.runtime().writeLocalState(request.node().id(), LATCH_SLOT, current);
					activated.add(CONTROL_OUT_A_PORT);
				}
				if (request.isControlInputActive(STORE_B_PORT)) {
					current = request.inputs().get(VALUE_B_PORT);
					request.runtime().writeLocalState(request.node().id(), LATCH_SLOT, current);
					activated.add(CONTROL_OUT_B_PORT);
				}
				return NodeExecutionResult.control(activated);
			}
			return NodeExecutionResult.of(Map.of(VALUE_PORT, current));
		}
	) {
		@Override
		public List<ControlRoute> controlRoutes(NodeInstance node) {
			return List.of(
				new ControlRoute(STORE_A_PORT, CONTROL_OUT_A_PORT),
				new ControlRoute(STORE_B_PORT, CONTROL_OUT_B_PORT)
			);
		}
	};

	public static final NodeType<EmptyConfig> WHILE = new SimpleNodeType<>(
		"mcng:while",
		"While",
		NodeKind.COMPUTE,
		NodeControlMode.REQUIRED,
		List.of(
			PortDefinition.controlInput("in", "In"),
			PortDefinition.input("flag", "Condition", MCNGPortTypes.BOOLEAN, true, PortInlineWidget.booleanToggle(false))
		),
		List.of(
			PortDefinition.controlOutput("body", "Body"),
			PortDefinition.controlOutput("done", "Done")
		),
		EmptyConfig.INSTANCE,
		new WhileConfigCodec(),
		request -> Boolean.TRUE.equals(request.inputs().get(BOOLEAN_PORT))
			? NodeExecutionResult.control(List.of(BODY_PORT))
			: NodeExecutionResult.control(List.of(DONE_PORT))
	);

	private BuiltinNodeTypes() {
	}

	public static void registerAll(NodeTypeRegistry registry) {
		BuiltinNodeRegistrar.registerAll(registry);
	}

	public static ResolvedPortType resolveDynamicPortType(NodeInstance node, PortDefinition definition, DynamicPortTypeResolverContext context) {
		if (definition.channel() != com.github.squi2rel.mcng.core.PortChannel.DATA) {
			return null;
		}
		if (NUMERIC_CONSTANT.id().equals(node.typeId()) && definition.id().equals(VALUE_PORT) && definition.direction() == PortDirection.OUTPUT) {
			return numericResolved(definition, NumericTypes.parseLiteral(NodeConfigValues.readNumericTextControlValue(node.config(), NUMERIC_CONSTANT_VALUE_CONTROL)).type());
		}
		if (ADD.id().equals(node.typeId()) || SUBTRACT.id().equals(node.typeId()) || MULTIPLY.id().equals(node.typeId())) {
			if (definition.direction() == PortDirection.INPUT && (definition.id().equals(LEFT_PORT) || definition.id().equals(RIGHT_PORT))) {
				PortType<?> type = inferNumericInputType(context, node.id(), definition.id());
				return type != null ? numericResolved(definition, type) : numericUnresolved(definition);
			}
			if (definition.direction() == PortDirection.OUTPUT && definition.id().equals(VALUE_PORT)) {
				PortType<?> leftType = inferNumericInputType(context, node.id(), LEFT_PORT);
				PortType<?> rightType = inferNumericInputType(context, node.id(), RIGHT_PORT);
				if (leftType == null || rightType == null) {
					return numericUnresolved(definition);
				}
				return numericResolved(definition, NumericTypes.widen(leftType, rightType));
			}
		}
		if (LESS_THAN.id().equals(node.typeId())) {
			if (definition.direction() == PortDirection.INPUT && (definition.id().equals(LEFT_PORT) || definition.id().equals(RIGHT_PORT))) {
				PortType<?> type = inferNumericInputType(context, node.id(), definition.id());
				return type != null ? numericResolved(definition, type) : numericUnresolved(definition);
			}
		}
		if (CAST.id().equals(node.typeId())) {
			if (definition.direction() == PortDirection.INPUT && definition.id().equals(VALUE_PORT)) {
				PortType<?> type = inferNumericInputType(context, node.id(), VALUE_PORT);
				return type != null ? numericResolved(definition, type) : numericUnresolved(definition);
			}
			if (definition.direction() == PortDirection.OUTPUT && definition.id().equals(VALUE_PORT)) {
				String mode = NodeConfigValues.readCycleControlValue(node.config(), CAST_MODE_CONTROL);
				if (!MODE_AUTO.equals(mode)) {
					return numericResolved(definition, portTypeForMode(mode, true));
				}
				PortType<?> downstream = inferNumericOutputType(context, node.id(), VALUE_PORT);
				if (downstream != null) {
					return numericResolved(definition, downstream);
				}
				PortType<?> inputType = inferNumericInputType(context, node.id(), VALUE_PORT);
				return inputType != null ? numericResolved(definition, inputType) : numericUnresolved(definition);
			}
		}
		if (ROUND.id().equals(node.typeId())) {
			if (definition.direction() == PortDirection.INPUT && definition.id().equals(VALUE_PORT)) {
				PortType<?> type = inferNumericInputType(context, node.id(), VALUE_PORT);
				return type != null ? numericResolved(definition, type) : numericUnresolved(definition);
			}
			if (definition.direction() == PortDirection.OUTPUT && definition.id().equals(VALUE_PORT)) {
				return numericResolved(definition, portTypeForMode(NodeConfigValues.readCycleControlValue(node.config(), ROUND_MODE_CONTROL), false));
			}
		}
		if ((GET_VARIABLE.id().equals(node.typeId()) || SET_VARIABLE.id().equals(node.typeId())) && definition.id().equals(VALUE_PORT)) {
			PortType<?> type = context.variableType(NodeConfigValues.readTextControlValue(node.config(), VARIABLE_ID_CONTROL));
			return type != null ? new ResolvedPortType(definition, type, false, false) : new ResolvedPortType(definition, MCNGPortTypes.ANY, true, false);
		}
		return null;
	}

	private static NodeExecutionResult error(GraphErrorCode code, String message, NodeId nodeId, PortId portId) {
		return new NodeExecutionResult(Map.of(), List.of(GraphError.of(code, message, nodeId, portId)), List.of());
	}

	private interface Executor<C> {
		NodeExecutionResult execute(NodeExecutionRequest<C> request);
	}

	private static class SimpleNodeType<C> implements NodeType<C> {
		private final String id;
		private final String displayName;
		private final NodeKind kind;
		private final NodeControlMode controlMode;
		private final List<PortDefinition> inputs;
		private final List<PortDefinition> outputs;
		private final C defaultConfig;
		private final NodeConfigCodec<C> configCodec;
		private final Executor<C> executor;

		private SimpleNodeType(
			String id,
			String displayName,
			NodeKind kind,
			NodeControlMode controlMode,
			List<PortDefinition> inputs,
			List<PortDefinition> outputs,
			C defaultConfig,
			NodeConfigCodec<C> configCodec,
			Executor<C> executor
		) {
			this.id = id;
			this.displayName = displayName;
			this.kind = kind;
			this.controlMode = controlMode;
			this.inputs = List.copyOf(inputs);
			this.outputs = List.copyOf(outputs);
			this.defaultConfig = defaultConfig;
			this.configCodec = configCodec;
			this.executor = executor;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public String displayName() {
			return displayName;
		}

		@Override
		public NodeKind kind() {
			return kind;
		}

		@Override
		public NodeControlMode controlMode() {
			return controlMode;
		}

		@Override
		public List<PortDefinition> inputs() {
			return inputs;
		}

		@Override
		public List<PortDefinition> outputs() {
			return outputs;
		}

		@Override
		public C defaultConfig() {
			return defaultConfig;
		}

		@Override
		public NodeConfigCodec<C> configCodec() {
			return configCodec;
		}

		@Override
		public NodeExecutionResult execute(NodeExecutionRequest<C> request) {
			return executor.execute(request);
		}
	}

	public record NumericConstantConfig(String value) {
	}

	public record BooleanConstantConfig(boolean value) {
	}

	public record StringConstantConfig(String value) {
	}

	public record TypeConstantConfig(String typeId) {
	}

	public record ManualTriggerConfig(String label, boolean uppercase, String style) {
	}

	public record CastConfig(String mode) {
	}

	public record RoundConfig(String mode) {
	}

	public record MergeConfig(int inputCount) {
	}

	public record GateConfig(boolean initialOpen) {
	}

	public record SequenceConfig(int outputCount) {
	}

	public record VariableReferenceConfig(String variableId) {
	}

	public record ImagePreviewConfig(String filePath) {
	}

	public enum EmptyConfig {
		INSTANCE;

		public static final NodeConfigCodec<EmptyConfig> CODEC = new NodeConfigCodec<>() {
			@Override
			public JsonObject toJson(EmptyConfig config) {
				return new JsonObject();
			}

			@Override
			public EmptyConfig fromJson(JsonObject json) {
				return INSTANCE;
			}
		};
	}

	private static final class NumericConstantCodec implements NodeConfigCodec<NumericConstantConfig> {
		@Override
		public JsonObject toJson(NumericConstantConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(NUMERIC_VALUE_KEY, NumericTypes.normalizeLiteralText(config.value()));
			return json;
		}

		@Override
		public NumericConstantConfig fromJson(JsonObject json) {
			return new NumericConstantConfig(NodeConfigValues.readNumericTextControlValue(json, NUMERIC_CONSTANT_VALUE_CONTROL));
		}
	}

	private static final class BooleanConstantCodec implements NodeConfigCodec<BooleanConstantConfig> {
		@Override
		public JsonObject toJson(BooleanConstantConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(BOOLEAN_VALUE_KEY, config.value());
			return json;
		}

		@Override
		public BooleanConstantConfig fromJson(JsonObject json) {
			return new BooleanConstantConfig(NodeConfigValues.readBooleanControlValue(json, BOOLEAN_CONSTANT_VALUE_CONTROL));
		}
	}

	private static final class StringConstantCodec implements NodeConfigCodec<StringConstantConfig> {
		@Override
		public JsonObject toJson(StringConstantConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty("value", config.value());
			return json;
		}

		@Override
		public StringConstantConfig fromJson(JsonObject json) {
			return new StringConstantConfig(json.has("value") ? json.get("value").getAsString() : "");
		}
	}

	private static final class TypeConstantCodec implements NodeConfigCodec<TypeConstantConfig> {
		@Override
		public JsonObject toJson(TypeConstantConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(TYPE_ID_KEY, config.typeId());
			return json;
		}

		@Override
		public TypeConstantConfig fromJson(JsonObject json) {
			return new TypeConstantConfig(NodeConfigValues.readTextControlValue(json, TYPE_CONSTANT_TYPE_ID_CONTROL).trim());
		}
	}

	private static final class ManualTriggerConfigCodec implements NodeConfigCodec<ManualTriggerConfig> {
		@Override
		public JsonObject toJson(ManualTriggerConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty("label", config.label());
			json.addProperty("uppercase", config.uppercase());
			json.addProperty("style", config.style());
			return json;
		}

		@Override
		public ManualTriggerConfig fromJson(JsonObject json) {
			return new ManualTriggerConfig(
				NodeConfigValues.readTextControlValue(json, MANUAL_TRIGGER_LABEL_CONTROL),
				NodeConfigValues.readBooleanControlValue(json, MANUAL_TRIGGER_UPPERCASE_CONTROL),
				NodeConfigValues.readCycleControlValue(json, MANUAL_TRIGGER_STYLE_CONTROL)
			);
		}
	}

	private static final class CastConfigCodec implements NodeConfigCodec<CastConfig> {
		@Override
		public JsonObject toJson(CastConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(CAST_MODE_KEY, config.mode());
			return json;
		}

		@Override
		public CastConfig fromJson(JsonObject json) {
			return new CastConfig(NodeConfigValues.readCycleControlValue(json, CAST_MODE_CONTROL));
		}
	}

	private static final class RoundConfigCodec implements NodeConfigCodec<RoundConfig> {
		@Override
		public JsonObject toJson(RoundConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(ROUND_MODE_KEY, config.mode());
			return json;
		}

		@Override
		public RoundConfig fromJson(JsonObject json) {
			return new RoundConfig(NodeConfigValues.readCycleControlValue(json, ROUND_MODE_CONTROL));
		}
	}

	private static final class MergeConfigCodec implements NodeConfigCodec<MergeConfig> {
		@Override
		public JsonObject toJson(MergeConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(INPUT_COUNT_KEY, normalizeDynamicPortCount(config.inputCount()));
			return json;
		}

		@Override
		public MergeConfig fromJson(JsonObject json) {
			return new MergeConfig(readDynamicPortCount(json, MERGE_INPUT_COUNT_CONTROL));
		}
	}

	private static final class GateConfigCodec implements NodeConfigCodec<GateConfig> {
		@Override
		public JsonObject toJson(GateConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(INITIAL_OPEN_KEY, config.initialOpen());
			return json;
		}

		@Override
		public GateConfig fromJson(JsonObject json) {
			return new GateConfig(NodeConfigValues.readBooleanControlValue(json, GATE_INITIAL_OPEN_CONTROL));
		}
	}

	private static final class SequenceConfigCodec implements NodeConfigCodec<SequenceConfig> {
		@Override
		public JsonObject toJson(SequenceConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(OUTPUT_COUNT_KEY, normalizeDynamicPortCount(config.outputCount()));
			return json;
		}

		@Override
		public SequenceConfig fromJson(JsonObject json) {
			return new SequenceConfig(readDynamicPortCount(json, SEQUENCE_OUTPUT_COUNT_CONTROL));
		}
	}

	private static final class VariableReferenceConfigCodec implements NodeConfigCodec<VariableReferenceConfig> {
		@Override
		public JsonObject toJson(VariableReferenceConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(VARIABLE_ID_KEY, config.variableId());
			return json;
		}

		@Override
		public VariableReferenceConfig fromJson(JsonObject json) {
			return new VariableReferenceConfig(NodeConfigValues.readTextControlValue(json, VARIABLE_ID_CONTROL).trim());
		}
	}

	private static final class ImagePreviewConfigCodec implements NodeConfigCodec<ImagePreviewConfig> {
		@Override
		public JsonObject toJson(ImagePreviewConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty(IMAGE_PREVIEW_FILE_PATH_KEY, config.filePath());
			return json;
		}

		@Override
		public ImagePreviewConfig fromJson(JsonObject json) {
			String filePath = json.has(IMAGE_PREVIEW_FILE_PATH_KEY) ? json.get(IMAGE_PREVIEW_FILE_PATH_KEY).getAsString() : "";
			return new ImagePreviewConfig(filePath);
		}
	}

	private static final class WhileConfigCodec implements NodeConfigCodec<EmptyConfig> {
		@Override
		public JsonObject toJson(EmptyConfig config) {
			return new JsonObject();
		}

		@Override
		public EmptyConfig fromJson(JsonObject json) {
			return EmptyConfig.INSTANCE;
		}
	}

	private static List<NodeEditorControl.Option> buildDynamicPortCountOptions() {
		ArrayList<NodeEditorControl.Option> options = new ArrayList<>();
		for (int count = DYNAMIC_PORT_MIN; count <= DYNAMIC_PORT_MAX; count++) {
			String label = String.valueOf(count);
			options.add(new NodeEditorControl.Option(label, label));
		}
		return List.copyOf(options);
	}

	private static int readDynamicPortCount(JsonObject json, NodeEditorControl.CycleControl control) {
		return normalizeDynamicPortCount(Integer.parseInt(NodeConfigValues.readCycleControlValue(json, control)));
	}

	private static int normalizeDynamicPortCount(int count) {
		return Math.max(DYNAMIC_PORT_MIN, Math.min(DYNAMIC_PORT_MAX, count));
	}

	private static int readMergeInputCount(JsonObject json) {
		return readDynamicPortCount(json, MERGE_INPUT_COUNT_CONTROL);
	}

	private static int readSequenceOutputCount(JsonObject json) {
		return readDynamicPortCount(json, SEQUENCE_OUTPUT_COUNT_CONTROL);
	}

	private static List<PortDefinition> mergeInputs(int count) {
		ArrayList<PortDefinition> ports = new ArrayList<>(count);
		for (int index = 1; index <= count; index++) {
			ports.add(PortDefinition.controlInput("in" + index, "In " + index));
		}
		return List.copyOf(ports);
	}

	private static List<PortDefinition> sequenceOutputs(int count) {
		ArrayList<PortDefinition> ports = new ArrayList<>(count);
		for (int index = 1; index <= count; index++) {
			ports.add(PortDefinition.controlOutput("out" + index, "Out " + index));
		}
		return List.copyOf(ports);
	}

	private static List<PortId> sequenceOutputIds(int count) {
		ArrayList<PortId> ports = new ArrayList<>(count);
		for (int index = 1; index <= count; index++) {
			ports.add(new PortId("out" + index));
		}
		return List.copyOf(ports);
	}

	private static boolean currentBooleanState(Object state, boolean fallback) {
		return state instanceof Boolean booleanState ? booleanState : fallback;
	}

	private static boolean isPrimaryControlTriggered(NodeExecutionRequest<?> request) {
		return request.isControlInputActive(CONTROL_IN_PORT) || !request.hasConnectedControlInput(CONTROL_IN_PORT);
	}

	private static PortType<?> resolveCastTargetType(NodeExecutionRequest<CastConfig> request, String mode) {
		if (!MODE_AUTO.equals(mode)) {
			return portTypeForMode(mode, true);
		}
		return request.outputType(VALUE_PORT).filter(NumericTypes::isNumericType).orElseGet(() -> NumericTypes.typeOf(request.inputs().get(VALUE_PORT)));
	}

	private static PortType<?> inferNumericInputType(DynamicPortTypeResolverContext context, NodeId nodeId, PortId portId) {
		boolean hasIncomingEdge = false;
		for (EdgeDefinition edge : context.edges()) {
			if (!edge.toNodeId().equals(nodeId) || !edge.toPortId().equals(portId)) {
				continue;
			}
			hasIncomingEdge = true;
			ResolvedPortType source = context.resolve(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
			if (NumericTypes.isNumericType(source.effectiveType())) {
				return source.effectiveType();
			}
		}
		if (hasIncomingEdge) {
			return null;
		}

		Object inlineValue;
		try {
			inlineValue = context.readInlineInputValue(nodeId, portId);
		} catch (IllegalArgumentException exception) {
			return null;
		}
		return inlineValue != null && NumericTypes.isNumericValue(inlineValue) ? NumericTypes.typeOf(inlineValue) : null;
	}

	private static PortType<?> inferNumericOutputType(DynamicPortTypeResolverContext context, NodeId nodeId, PortId portId) {
		for (EdgeDefinition edge : context.edges()) {
			if (!edge.fromNodeId().equals(nodeId) || !edge.fromPortId().equals(portId)) {
				continue;
			}
			ResolvedPortType target = context.resolve(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
			if (NumericTypes.isNumericType(target.effectiveType())) {
				return target.effectiveType();
			}
		}
		return null;
	}

	private static ResolvedPortType numericResolved(PortDefinition definition, PortType<?> type) {
		return new ResolvedPortType(definition, type, false, true);
	}

	private static ResolvedPortType numericUnresolved(PortDefinition definition) {
		return new ResolvedPortType(definition, MCNGPortTypes.ANY, false, true);
	}

	private static PortType<?> portTypeForMode(String mode, boolean allowAuto) {
		return switch (mode) {
			case MODE_INT -> MCNGPortTypes.INT;
			case MODE_LONG -> MCNGPortTypes.LONG;
			case MODE_DOUBLE -> MCNGPortTypes.DOUBLE;
			case MODE_AUTO -> {
				if (!allowAuto) {
					throw new IllegalArgumentException("Auto mode is not allowed here");
				}
				yield MCNGPortTypes.ANY;
			}
			default -> throw new IllegalArgumentException("Unknown numeric mode: " + mode);
		};
	}

	private static boolean numericLessThan(Object left, Object right) {
		Objects.requireNonNull(left, "left");
		Objects.requireNonNull(right, "right");
		if (!NumericTypes.isNumericValue(left) || !NumericTypes.isNumericValue(right)) {
			throw new IllegalArgumentException("Less Than expects numeric values");
		}
		if (left instanceof Double || right instanceof Double) {
			return ((Number) left).doubleValue() < ((Number) right).doubleValue();
		}
		return ((Number) left).longValue() < ((Number) right).longValue();
	}

	private static boolean valuesEqual(Object left, Object right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		if (NumericTypes.isNumericValue(left) && NumericTypes.isNumericValue(right)) {
			if (left instanceof Double || right instanceof Double) {
				return ((Number) left).doubleValue() == ((Number) right).doubleValue();
			}
			return ((Number) left).longValue() == ((Number) right).longValue();
		}
		return Objects.equals(left, right);
	}

	private static void appendPresentValue(List<Object> values, Map<PortId, Object> inputs, PortId portId) {
		if (inputs.containsKey(portId)) {
			values.add(RuntimeValueUtils.copyIfContainer(inputs.get(portId)));
		}
	}

	private static int numericIndex(Object value) {
		return (Integer) NumericTypes.cast(value, MCNGPortTypes.INT);
	}
}
