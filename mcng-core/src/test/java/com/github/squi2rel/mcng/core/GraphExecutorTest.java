package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.ManualTriggerConfig;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.StringConstantConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GraphExecutorTest {
	private static final PortType<Integer> INTEGER = new PortType<>("test:integer", Integer.class);

	@Test
	void executesDataFlowGraphInDependencyOrder() {
		NodeTypeRegistry registry = registry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes());

		NodeId left = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("2.0"), new NodePosition(20, 20));
		NodeId right = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("3.0"), new NodePosition(20, 90));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 50));

		builder.addEdge(left, "value", add, "left");
		builder.addEdge(right, "value", add, "right");

		ExecutionResult result = new GraphExecutor(registry, portTypes()).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of(left, right, add), result.visitedNodes());
		assertEquals(5.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void eventTriggerStaysInsideReachableSubgraph() {
		NodeTypeRegistry registry = registry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes());

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId detached = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("ignored"), new NodePosition(20, 180));

		builder.addEdge(trigger, "message", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes()).triggerEvent(builder.buildGraph(), trigger, 7.0, context);

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of(trigger, debug), result.visitedNodes());
		assertFalse(result.visitedNodes().contains(detached));
		assertEquals(List.of("pulse #7"), context.messages);
	}

	@Test
	void cycleDetectionFailsExecution() {
		NodeTypeRegistry registry = registry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes());

		NodeId first = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		NodeId second = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));

		builder.addEdge(first, "value", second, "left");
		builder.addEdge(second, "value", first, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes()).execute(builder.buildGraph(), new RecordingContext());

		assertFalse(result.success());
		assertEquals(GraphErrorCode.CYCLE_DETECTED, result.errors().getFirst().code());
	}

	@Test
	void typeMismatchFailsValidation() {
		NodeTypeRegistry registry = registry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes());

		NodeId stringValue = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("oops"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));

		assertTrue(
			assertThrowsTypeMismatch(builder, stringValue, add),
			"GraphBuilder should reject incompatible ports"
		);
	}

	@Test
	void implicitConverterAllowsConnectingAndExecutingDifferentPortTypes() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		PortTypeRegistry portTypes = portTypes();
		portTypes.registerType(INTEGER, 0xFF66AAFF);
		portTypes.registerConverter(INTEGER, MCNGPortTypes.DOUBLE, Integer::doubleValue);

		NodeType<IntConfig> intSource = new TestNodeType<>(
			"test:int_source",
			"Int Source",
			NodeKind.COMPUTE,
			List.of(),
			List.of(PortDefinition.output("value", "Value", INTEGER)),
			new IntConfig(7),
			new IntConfigCodec(),
			request -> NodeExecutionResult.of(Map.of(BuiltinNodeTypes.VALUE_PORT, request.config().value()))
		);
		NodeType<BuiltinNodeTypes.EmptyConfig> doubleDebug = new TestNodeType<>(
			"test:double_debug",
			"Double Debug",
			NodeKind.COMPUTE,
			List.of(PortDefinition.input("value", "Value", MCNGPortTypes.DOUBLE, true)),
			List.of(),
			BuiltinNodeTypes.EmptyConfig.INSTANCE,
			BuiltinNodeTypes.EmptyConfig.CODEC,
			request -> {
				request.context().publishDebug(request.node().id(), String.valueOf(request.inputs().get(BuiltinNodeTypes.VALUE_PORT)));
				return NodeExecutionResult.of(Map.of());
			}
		);
		registry.register(intSource);
		registry.register(doubleDebug);

		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId source = builder.addNode(intSource, new IntConfig(7), new NodePosition(20, 20));
		NodeId sink = builder.addNode(doubleDebug, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.addEdge(source, "value", sink, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), context);

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of(source, sink), result.visitedNodes());
		assertEquals(List.of("7.0"), context.messages);
	}

	@Test
	void unconnectedInlineInputsSatisfyRequiredPorts() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		builder.setInlineInput(add, "left", NodeConfigValues.numberValue(2.0));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(3.0));

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of(add), result.visitedNodes());
		assertEquals(5.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void connectedEdgeOverridesInlineInputValue() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("7.0"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.setInlineInput(add, "left", NodeConfigValues.numberValue(2.0));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(3.0));
		builder.addEdge(source, "value", add, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(10.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void numericFamilyInputAcceptsLongConnectionDespiteInlineLiteralType() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("2147483648"), new NodePosition(20, 20));
		NodeId addWithDoubleInline = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId addWithIntInline = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 140));
		builder.setInlineInput(addWithDoubleInline, "left", NodeConfigValues.doubleValue(1.0));
		builder.setInlineInput(addWithDoubleInline, "right", NodeConfigValues.intValue(2));
		builder.setInlineInput(addWithIntInline, "left", NodeConfigValues.intValue(1));
		builder.setInlineInput(addWithIntInline, "right", NodeConfigValues.intValue(2));

		builder.addEdge(source, "value", addWithDoubleInline, "left");
		builder.addEdge(source, "value", addWithIntInline, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(2147483650L, result.outputs().get(addWithDoubleInline).get(BuiltinNodeTypes.VALUE_PORT));
		assertEquals(2147483650L, result.outputs().get(addWithIntInline).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void missingInlineInputUsesWidgetDefaultValue() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(0.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void invalidInlineInputReportsDedicatedErrorAndSkipsExecution() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		builder.setInlineInput(add, "left", NodeConfigValues.stringValue("oops"));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(3.0));

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertFalse(result.success());
		assertEquals(List.of(), result.visitedNodes());
		assertEquals(GraphErrorCode.INVALID_INLINE_INPUT, result.errors().getFirst().code());
	}

	@Test
	void booleanInputAcceptsNumberThroughConverter() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("2.0"), new NodePosition(20, 20));
		NodeId not = builder.addNode(BuiltinNodeTypes.NOT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.addEdge(source, "value", not, "flag");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(false, result.outputs().get(not).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void convertTypeAllowsConverterBackedValuesToFlowIntoNumericNodes() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId boolValue = builder.addNode(BuiltinNodeTypes.BOOLEAN_CONSTANT, new BuiltinNodeTypes.BooleanConstantConfig(true), new NodePosition(20, 20));
		NodeId type = builder.addNode(BuiltinNodeTypes.TYPE_CONSTANT, new BuiltinNodeTypes.TypeConstantConfig(MCNGPortTypes.INT.id()), new NodePosition(20, 120));
		NodeId convert = builder.addNode(BuiltinNodeTypes.CONVERT_TYPE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 70));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		builder.setInlineInput(add, "right", NodeConfigValues.intValue(2));
		builder.addEdge(boolValue, "value", convert, "value");
		builder.addEdge(type, "value", convert, "type");
		builder.addEdge(convert, "value", add, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(3, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void listNodesCanBuildAndReadIndexedValues() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId first = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("4"), new NodePosition(20, 20));
		NodeId second = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("6"), new NodePosition(20, 120));
		NodeId list = builder.addNode(BuiltinNodeTypes.LIST_CREATE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 70));
		NodeId get = builder.addNode(BuiltinNodeTypes.LIST_GET, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 70));
		builder.setInlineInput(get, "index", NodeConfigValues.intValue(1));
		builder.setInlineInput(add, "right", NodeConfigValues.intValue(1));
		builder.addEdge(first, "value", list, "first");
		builder.addEdge(second, "value", list, "second");
		builder.addEdge(list, "value", get, "list");
		builder.addEdge(get, "value", add, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(7, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void mapNodesCanStoreAndReadValues() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("9"), new NodePosition(20, 20));
		NodeId put = builder.addNode(BuiltinNodeTypes.MAP_PUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId get = builder.addNode(BuiltinNodeTypes.MAP_GET, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 20));
		builder.setInlineInput(put, "key", NodeConfigValues.stringValue("answer"));
		builder.setInlineInput(get, "key", NodeConfigValues.stringValue("answer"));
		builder.setInlineInput(add, "right", NodeConfigValues.intValue(1));
		builder.addEdge(source, "value", put, "value");
		builder.addEdge(put, "value", get, "map");
		builder.addEdge(get, "value", add, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(10, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void typeOfRecognizesCollectionOutputs() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId value = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("4"), new NodePosition(20, 20));
		NodeId list = builder.addNode(BuiltinNodeTypes.LIST_CREATE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId typeOf = builder.addNode(BuiltinNodeTypes.TYPE_OF, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(value, "value", list, "first");
		builder.addEdge(list, "value", typeOf, "value");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success());
		assertEquals(MCNGPortTypes.LIST, result.outputs().get(typeOf).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void genericIdentityPropagatesConcreteTypeThroughExecution() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("4.0"), new NodePosition(20, 20));
		NodeId identity = builder.addNode(BuiltinNodeTypes.IDENTITY, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(6.0));
		builder.addEdge(source, "value", identity, "value");
		builder.addEdge(identity, "value", add, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success());
		assertEquals(10.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void genericDebugOutputLocksToFirstConcreteConnectionType() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		NodeId concat = builder.addNode(BuiltinNodeTypes.CONCAT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("2.0"), new NodePosition(20, 120));
		builder.setInlineInput(concat, "right", NodeConfigValues.stringValue("!"));
		builder.addEdge(debug, "value", concat, "left");

		assertTrue(
			assertThrowsTypeMismatch(builder, source, debug, "value", "value"),
			"First concrete generic connection should lock Debug Output to string"
		);
	}

	@Test
	void genericChainResolvesAcrossRerouteAndDebugOutput() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("5.0"), new NodePosition(20, 20));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 20));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(1.0));
		builder.addEdge(source, "value", reroute, "value");
		builder.addEdge(reroute, "value", debug, "value");
		builder.addEdge(debug, "value", add, "left");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), context);

		assertTrue(result.success());
		assertEquals(List.of(source, debug, add), result.visitedNodes());
		assertEquals(List.of("5.0"), context.messages);
		assertEquals(6.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void longRerouteChainDoesNotExecuteIntermediateNodes() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("5.0"), new NodePosition(20, 20));
		NodeId rerouteA = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(180, 20));
		NodeId rerouteB = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(280, 20));
		NodeId rerouteC = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(380, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(480, 20));
		builder.addEdge(source, "value", rerouteA, "value");
		builder.addEdge(rerouteA, "value", rerouteB, "value");
		builder.addEdge(rerouteB, "value", rerouteC, "value");
		builder.addEdge(rerouteC, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), context);

		assertTrue(result.success());
		assertEquals(List.of(source, debug), result.visitedNodes());
		assertEquals(List.of("5.0"), context.messages);
	}

	@Test
	void rerouteCanPassControlFlow() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(trigger, "out", reroute, "value");
		builder.addEdge(reroute, "value", debug, "in");
		builder.addEdge(trigger, "message", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 7.0, context);

		assertTrue(result.success());
		assertEquals(List.of(trigger, debug), result.visitedNodes());
		assertEquals(List.of("pulse #7"), context.messages);
	}

	@Test
	void rerouteChainCanPassControlFlowAcrossMultipleNodes() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId rerouteA = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId rerouteB = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 20));
		builder.addEdge(trigger, "out", rerouteA, "value");
		builder.addEdge(rerouteA, "value", rerouteB, "value");
		builder.addEdge(rerouteB, "value", debug, "in");
		builder.addEdge(trigger, "message", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 7.0, context);

		assertTrue(result.success());
		assertEquals(List.of(trigger, debug), result.visitedNodes());
		assertEquals(List.of("pulse #7"), context.messages);
	}

	@Test
	void rerouteRejectsMixedDataAndControlChain() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(trigger, "out", reroute, "value");

		try {
			builder.addEdge(reroute, "value", debug, "value");
			fail("Mixed reroute chain should be rejected");
		} catch (IllegalArgumentException exception) {
			assertTrue(exception.getMessage().contains("Reroute cannot mix data and control edges"));
		}
	}

	@Test
	void rerouteRejectsMixedDataAndControlAcrossMultipleNodes() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId rerouteA = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId rerouteB = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 20));
		builder.addEdge(trigger, "out", rerouteA, "value");
		builder.addEdge(rerouteA, "value", rerouteB, "value");

		try {
			builder.addEdge(rerouteB, "value", debug, "value");
			fail("Mixed reroute component should be rejected");
		} catch (IllegalArgumentException exception) {
			assertTrue(exception.getMessage().contains("Reroute cannot mix data and control edges"));
		}
	}

	@Test
	void executesDocumentCustomNodeDefinition() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		DocumentNodeDefinition definition = new DocumentNodeDefinition(
			"custom_add",
			"Custom Add",
			DocumentNodeDefinitionKind.CUSTOM_NODE,
			new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("in"), DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, portConfig("Left")),
					new NodeInstance(new NodeId("add"), BuiltinNodeTypes.ADD.id(), NodeConfigValues.copyWithInlineInput(new JsonObject(), BuiltinNodeTypes.RIGHT_PORT, NodeConfigValues.numberValue(2.0))),
					new NodeInstance(new NodeId("out"), DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID, portConfig("Value"))
				),
				List.of(
					new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("add"), BuiltinNodeTypes.LEFT_PORT),
					new EdgeDefinition(new NodeId("add"), BuiltinNodeTypes.VALUE_PORT, new NodeId("out"), new PortId("value"))
				)
			),
			new GraphLayout(Map.of(
				new NodeId("in"), new NodePosition(20, 20),
				new NodeId("add"), new NodePosition(180, 20),
				new NodeId("out"), new NodePosition(360, 20)
			))
		);
		GraphDocument document = GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()), List.of(definition));
		NodeTypeRegistry resolved = DocumentNodeTypes.createResolvedRegistry(registry, portTypes, document);

		GraphBuilder builder = new GraphBuilder(resolved, portTypes);
		NodeType<?> customNode = resolved.getOrThrow(DocumentNodeTypes.definitionTypeId("custom_add"));
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("5.0"), new NodePosition(20, 20));
		NodeId custom = builder.addNode(typed(customNode), customNode.defaultConfig(), new NodePosition(220, 20));
		builder.addEdge(source, "value", custom, "in");

		ExecutionResult result = new GraphExecutor(resolved, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success());
		assertEquals(7.0, result.outputs().get(custom).get(new PortId("out")));
	}

	@Test
	void customDefinitionExposedInlineInputFeedsGraphInput() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		DocumentNodeDefinition definition = new DocumentNodeDefinition(
			"custom_inline_add",
			"Custom Inline Add",
			DocumentNodeDefinitionKind.CUSTOM_NODE,
			new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("in"), DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, portConfig("Left", true)),
					new NodeInstance(new NodeId("add"), BuiltinNodeTypes.ADD.id(), NodeConfigValues.copyWithInlineInput(new JsonObject(), BuiltinNodeTypes.RIGHT_PORT, NodeConfigValues.numberValue(2.0))),
					new NodeInstance(new NodeId("out"), DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID, portConfig("Value"))
				),
				List.of(
					new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("add"), BuiltinNodeTypes.LEFT_PORT),
					new EdgeDefinition(new NodeId("add"), BuiltinNodeTypes.VALUE_PORT, new NodeId("out"), new PortId("value"))
				)
			),
			new GraphLayout(Map.of(
				new NodeId("in"), new NodePosition(20, 20),
				new NodeId("add"), new NodePosition(180, 20),
				new NodeId("out"), new NodePosition(360, 20)
			))
		);
		GraphDocument document = GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()), List.of(definition));
		NodeTypeRegistry resolved = DocumentNodeTypes.createResolvedRegistry(registry, portTypes, document);

		GraphBuilder builder = new GraphBuilder(resolved, portTypes);
		NodeType<?> customNode = resolved.getOrThrow(DocumentNodeTypes.definitionTypeId("custom_inline_add"));
		NodeId custom = builder.addNode(typed(customNode), customNode.defaultConfig(), new NodePosition(220, 20));
		builder.setInlineInput(custom, "in", NodeConfigValues.numberValue(5.0));

		ExecutionResult result = new GraphExecutor(resolved, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success());
		assertEquals(7.0, result.outputs().get(custom).get(new PortId("out")));
	}

	@Test
	void triggersDocumentSubgraphDefinitionAsEventSource() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		SubgraphDefinition subgraph = new SubgraphDefinition(
			"event_subgraph",
			"Event Subgraph",
			GraphScope.of(
				new GraphDefinition(
					List.of(
						new NodeInstance(new NodeId("trigger"), BuiltinNodeTypes.MANUAL_TRIGGER.id(), BuiltinNodeTypes.MANUAL_TRIGGER.configCodec().toJson(new ManualTriggerConfig("pulse", false, "plain"))),
						new NodeInstance(new NodeId("out"), DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID, portConfig("Message"))
					),
					List.of(
						new EdgeDefinition(new NodeId("trigger"), BuiltinNodeTypes.MESSAGE_PORT, new NodeId("out"), new PortId("value"))
					)
				),
				new GraphLayout(Map.of(
					new NodeId("trigger"), new NodePosition(20, 20),
					new NodeId("out"), new NodePosition(220, 20)
				))
			)
		);
		GraphDocument document = GraphDocument.of(
			new GraphScope(
				new GraphDefinition(
					List.of(
						new NodeInstance(new NodeId("subgraph"), DocumentNodeTypes.subgraphTypeId("event_subgraph"), new JsonObject()),
						new NodeInstance(new NodeId("debug"), BuiltinNodeTypes.DEBUG_OUTPUT.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE))
					),
					List.of(
						new EdgeDefinition(new NodeId("subgraph"), new PortId("out"), new NodeId("debug"), new PortId("value"))
					)
				),
				new GraphLayout(Map.of(
					new NodeId("subgraph"), new NodePosition(20, 20),
					new NodeId("debug"), new NodePosition(220, 20)
				)),
				List.of(),
				List.of(subgraph)
			),
			List.of()
		);
		CompiledGraphDocument compiled = new GraphExecutor(registry, portTypes).compile(document);
		NodeId trigger = compiled.rootEventSourceNodeIds().getFirst();

		RecordingContext context = new RecordingContext();
		ExecutionResult result = compiled.startEventExecution(trigger, 7.0, context).runToCompletion().toExecutionResult();

		assertTrue(result.success());
		assertEquals(List.of("event_subgraph"), compiled.scopePathForNode(trigger));
		assertEquals(List.of("pulse #7"), context.messages);
	}

	@Test
	void genericTypesPropagateAcrossDocumentDefinitionBoundaries() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		DocumentNodeDefinition definition = new DocumentNodeDefinition(
			"passthrough",
			"Passthrough",
			DocumentNodeDefinitionKind.CUSTOM_NODE,
			new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("in"), DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, portConfig("Value")),
					new NodeInstance(new NodeId("out"), DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID, portConfig("Value"))
				),
				List.of(
					new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("out"), new PortId("value"))
				)
			),
			new GraphLayout(Map.of(
				new NodeId("in"), new NodePosition(20, 20),
				new NodeId("out"), new NodePosition(220, 20)
			))
		);
		GraphDocument document = GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()), List.of(definition));
		NodeTypeRegistry resolved = DocumentNodeTypes.createResolvedRegistry(registry, portTypes, document);

		GraphBuilder builder = new GraphBuilder(resolved, portTypes);
		NodeType<?> passthroughNode = resolved.getOrThrow(DocumentNodeTypes.definitionTypeId("passthrough"));
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("5.0"), new NodePosition(20, 20));
		NodeId passthrough = builder.addNode(typed(passthroughNode), passthroughNode.defaultConfig(), new NodePosition(220, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(3.0));
		builder.addEdge(source, "value", passthrough, "in");
		builder.addEdge(passthrough, "out", add, "left");

		ExecutionResult result = new GraphExecutor(resolved, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success());
		assertEquals(8.0, result.outputs().get(add).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void internalDefinitionErrorsAreReportedOnOuterNode() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		DocumentNodeDefinition definition = new DocumentNodeDefinition(
			"broken_debug",
			"Broken Debug",
			DocumentNodeDefinitionKind.CUSTOM_NODE,
			new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("debug"), BuiltinNodeTypes.DEBUG_OUTPUT.id(), new JsonObject())
				),
				List.of()
			),
			new GraphLayout(Map.of(
				new NodeId("debug"), new NodePosition(20, 20)
			))
		);
		GraphDocument document = GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()), List.of(definition));
		NodeTypeRegistry resolved = DocumentNodeTypes.createResolvedRegistry(registry, portTypes, document);

		GraphBuilder builder = new GraphBuilder(resolved, portTypes);
		NodeType<?> brokenNode = resolved.getOrThrow(DocumentNodeTypes.definitionTypeId("broken_debug"));
		NodeId broken = builder.addNode(typed(brokenNode), brokenNode.defaultConfig(), new NodePosition(20, 20));

		ExecutionResult result = new GraphExecutor(resolved, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertFalse(result.success());
		assertEquals(GraphErrorCode.MISSING_REQUIRED_INPUT, result.errors().getFirst().code());
		assertEquals(broken, result.errors().getFirst().nodeId());
	}

	@Test
	void controlFlowExecutesOnlyTheSelectedIfBranch() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId branch = builder.addNode(BuiltinNodeTypes.IF, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId trueValue = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("true-path"), new NodePosition(220, 120));
		NodeId falseValue = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("false-path"), new NodePosition(220, 220));
		NodeId trueDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 120));
		NodeId falseDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 220));
		builder.setInlineInput(branch, "flag", NodeConfigValues.booleanValue(true));
		builder.addEdge(trigger, "out", branch, "in");
		builder.addEdge(branch, "true", trueDebug, "in");
		builder.addEdge(branch, "false", falseDebug, "in");
		builder.addEdge(trueValue, "value", trueDebug, "value");
		builder.addEdge(falseValue, "value", falseDebug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success());
		assertEquals(List.of("true-path"), context.messages);
	}

	@Test
	void sequenceActivatesOutputsInConfiguredOrder() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId sequence = builder.addNode(BuiltinNodeTypes.SEQUENCE, new BuiltinNodeTypes.SequenceConfig(3), new NodePosition(220, 20));
		NodeId first = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("one"), new NodePosition(220, 140));
		NodeId second = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("two"), new NodePosition(220, 240));
		NodeId third = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("three"), new NodePosition(220, 340));
		NodeId debugA = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(440, 140));
		NodeId debugB = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(440, 240));
		NodeId debugC = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(440, 340));
		builder.addEdge(trigger, "out", sequence, "in");
		builder.addEdge(sequence, "out1", debugA, "in");
		builder.addEdge(sequence, "out2", debugB, "in");
		builder.addEdge(sequence, "out3", debugC, "in");
		builder.addEdge(first, "value", debugA, "value");
		builder.addEdge(second, "value", debugB, "value");
		builder.addEdge(third, "value", debugC, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of("one", "two", "three"), context.messages);
	}

	@Test
	void mergeSupportsExpandedInputCountAndForwardsAnyActiveInput() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId triggerA = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId triggerB = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 180));
		NodeId merge = builder.addNode(BuiltinNodeTypes.MERGE, new BuiltinNodeTypes.MergeConfig(4), new NodePosition(220, 100));
		NodeId value = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("merged"), new NodePosition(220, 300));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 100));
		builder.addEdge(triggerA, "out", merge, "in1");
		builder.addEdge(triggerB, "out", merge, "in4");
		builder.addEdge(merge, "out", debug, "in");
		builder.addEdge(value, "value", debug, "value");

		RecordingContext firstContext = new RecordingContext();
		ExecutionResult firstResult = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), triggerA, 1.0, firstContext);
		RecordingContext secondContext = new RecordingContext();
		ExecutionResult secondResult = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), triggerB, 2.0, secondContext);

		assertTrue(firstResult.success(), String.valueOf(firstResult.errors()));
		assertTrue(secondResult.success(), String.valueOf(secondResult.errors()));
		assertEquals(List.of("merged"), firstContext.messages);
		assertEquals(List.of("merged"), secondContext.messages);
	}

	@Test
	void gateCanOpenPassCloseAndExposePulledState() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId sequence = builder.addNode(BuiltinNodeTypes.SEQUENCE, new BuiltinNodeTypes.SequenceConfig(4), new NodePosition(220, 20));
		NodeId gate = builder.addNode(BuiltinNodeTypes.GATE, new BuiltinNodeTypes.GateConfig(false), new NodePosition(420, 20));
		NodeId passDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 20));
		NodeId stateDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 160));
		builder.addEdge(trigger, "out", sequence, "in");
		builder.addEdge(sequence, "out1", gate, "open");
		builder.addEdge(sequence, "out2", gate, "in");
		builder.addEdge(gate, "out", passDebug, "in");
		builder.addEdge(gate, "state", passDebug, "value");
		builder.addEdge(sequence, "out3", gate, "close");
		builder.addEdge(sequence, "out4", stateDebug, "in");
		builder.addEdge(gate, "state", stateDebug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of("true", "false"), context.messages);
	}

	@Test
	void onceBlocksRepeatedTriggersUntilReset() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId sequence = builder.addNode(BuiltinNodeTypes.SEQUENCE, new BuiltinNodeTypes.SequenceConfig(4), new NodePosition(220, 20));
		NodeId merge = builder.addNode(BuiltinNodeTypes.MERGE, new BuiltinNodeTypes.MergeConfig(2), new NodePosition(420, 20));
		NodeId once = builder.addNode(BuiltinNodeTypes.ONCE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		NodeId value = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("hit"), new NodePosition(420, 160));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 20));
		builder.addEdge(trigger, "out", sequence, "in");
		builder.addEdge(sequence, "out1", merge, "in1");
		builder.addEdge(sequence, "out2", once, "reset");
		builder.addEdge(sequence, "out4", merge, "in2");
		builder.addEdge(merge, "out", once, "in");
		builder.addEdge(once, "out", debug, "in");
		builder.addEdge(value, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of("hit", "hit"), context.messages);
	}

	@Test
	void flipFlopAlternatesOutputsAndResetReturnsToA() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId sequence = builder.addNode(BuiltinNodeTypes.SEQUENCE, new BuiltinNodeTypes.SequenceConfig(4), new NodePosition(220, 20));
		NodeId merge = builder.addNode(BuiltinNodeTypes.MERGE, new BuiltinNodeTypes.MergeConfig(3), new NodePosition(420, 20));
		NodeId flipFlop = builder.addNode(BuiltinNodeTypes.FLIP_FLOP, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 20));
		NodeId aValue = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("A"), new NodePosition(420, 160));
		NodeId bValue = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("B"), new NodePosition(420, 260));
		NodeId debugA = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(840, 120));
		NodeId debugB = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(840, 260));
		builder.addEdge(trigger, "out", sequence, "in");
		builder.addEdge(sequence, "out1", merge, "in1");
		builder.addEdge(sequence, "out2", merge, "in2");
		builder.addEdge(sequence, "out3", flipFlop, "reset");
		builder.addEdge(sequence, "out4", merge, "in3");
		builder.addEdge(merge, "out", flipFlop, "in");
		builder.addEdge(flipFlop, "a", debugA, "in");
		builder.addEdge(flipFlop, "b", debugB, "in");
		builder.addEdge(aValue, "value", debugA, "value");
		builder.addEdge(bValue, "value", debugB, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of("A", "B", "A"), context.messages);
	}

	@Test
	void setAndGetVariableNodesShareRuntimeState() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		List<GraphVariableDefinition> variables = List.of(new GraphVariableDefinition("count", "Count", MCNGPortTypes.INT.id(), NodeConfigValues.intValue(0)));

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId five = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("5"), new NodePosition(220, 20));
		NodeId setCount = builder.addNode(BuiltinNodeTypes.SET_VARIABLE, new BuiltinNodeTypes.VariableReferenceConfig("count"), new NodePosition(220, 120));
		NodeId getCount = builder.addNode(BuiltinNodeTypes.GET_VARIABLE, new BuiltinNodeTypes.VariableReferenceConfig("count"), new NodePosition(420, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 120));
		builder.addEdge(trigger, "out", setCount, "in");
		builder.addEdge(five, "value", setCount, "value");
		builder.addEdge(setCount, "out", debug, "in");
		builder.addEdge(getCount, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), variables, trigger, 1.0, context);

		assertTrue(result.success());
		assertEquals(List.of("5"), context.messages);
	}

	@Test
	void variableTypesParticipateInExecutionValidation() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		List<GraphVariableDefinition> variables = List.of(new GraphVariableDefinition("text", "Text", MCNGPortTypes.STRING.id(), NodeConfigValues.stringValue("hello")));

		NodeId getText = builder.addNode(BuiltinNodeTypes.GET_VARIABLE, new BuiltinNodeTypes.VariableReferenceConfig("text"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.setInlineInput(add, "right", NodeConfigValues.intValue(1));
		builder.addEdge(getText, "value", add, "left");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), variables, new RecordingContext());

		assertFalse(result.success());
		assertEquals(GraphErrorCode.TYPE_MISMATCH, result.errors().getFirst().code());
		assertEquals(add, result.errors().getFirst().nodeId());
	}

	@Test
	void latchControlWriteActivatesOnlyMatchingControlOutput() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId stored = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("5"), new NodePosition(220, 20));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(stored, "value", latch, "valueA");

		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, new RecordingContext());

		assertTrue(result.success());
		assertEquals(List.of(BuiltinNodeTypes.CONTROL_OUT_A_PORT), result.activatedControlOutputs().get(latch));
		assertTrue(result.outputs().containsKey(latch));
		assertFalse(result.outputs().get(latch).containsKey(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void latchStoredValueIsPulledWhenDownstreamRequestsIt() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId stored = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("5"), new NodePosition(220, 20));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 20));
		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(stored, "value", latch, "valueA");
		builder.addEdge(latch, "outA", debug, "in");
		builder.addEdge(latch, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success());
		assertEquals(List.of("5"), context.messages);
		assertEquals(List.of(BuiltinNodeTypes.CONTROL_OUT_A_PORT), result.activatedControlOutputs().get(latch));
		assertEquals(5, result.outputs().get(latch).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void latchValueLoopReadsPreviousStoredValueBeforeWritingNewOne() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId seed = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("1"), new NodePosition(220, 20));
		NodeId increment = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("1"), new NodePosition(220, 120));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 70));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(860, 70));
		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(seed, "value", latch, "valueA");
		builder.addEdge(latch, "outA", latch, "storeB");
		builder.addEdge(latch, "value", add, "left");
		builder.addEdge(increment, "value", add, "right");
		builder.addEdge(add, "value", latch, "valueB");
		builder.addEdge(latch, "outB", debug, "in");
		builder.addEdge(latch, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success());
		assertFalse(result.errors().stream().anyMatch(error -> error.code() == GraphErrorCode.CYCLE_DETECTED));
		assertEquals(List.of("2"), context.messages);
		assertEquals(2, result.outputs().get(latch).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void latchOnlyEvaluatesTheActiveWriteBranch() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId left = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("5"), new NodePosition(220, 20));
		NodeId right = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("9"), new NodePosition(220, 120));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(left, "value", latch, "valueA");
		builder.addEdge(right, "value", latch, "valueB");

		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, new RecordingContext());

		assertTrue(result.success());
		assertTrue(result.visitedNodes().contains(left));
		assertFalse(result.visitedNodes().contains(right));
	}

	@Test
	void latchSecondWritePathUsesSharedValueOutput() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId stored = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("beta"), new NodePosition(220, 20));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 20));
		builder.addEdge(trigger, "out", latch, "storeB");
		builder.addEdge(stored, "value", latch, "valueB");
		builder.addEdge(latch, "outB", debug, "in");
		builder.addEdge(latch, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success());
		assertEquals(List.of("beta"), context.messages);
		assertEquals(List.of(BuiltinNodeTypes.CONTROL_OUT_B_PORT), result.activatedControlOutputs().get(latch));
		assertEquals("beta", result.outputs().get(latch).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void latchWriteInputsShareOneGenericType() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId number = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, numericConstant("5"), new NodePosition(20, 20));
		NodeId text = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("oops"), new NodePosition(20, 120));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(240, 70));
		builder.addEdge(number, "value", latch, "valueA");

		assertTrue(
			assertThrowsTypeMismatch(builder, text, latch, "value", "valueB"),
			"Latch value inputs should share a single concrete generic type"
		);
	}

	@Test
	void latchIndependentRoutesCanConnectToWhileWithoutControlCycle() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId seed = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("seed"), new NodePosition(220, 20));
		NodeId update = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("loop"), new NodePosition(220, 120));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		NodeId loop = builder.addNode(BuiltinNodeTypes.WHILE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 70));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(860, 70));
		builder.setInlineInput(loop, "flag", NodeConfigValues.booleanValue(false));
		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(seed, "value", latch, "valueA");
		builder.addEdge(latch, "outA", loop, "in");
		builder.addEdge(loop, "body", latch, "storeB");
		builder.addEdge(update, "value", latch, "valueB");
		builder.addEdge(loop, "done", debug, "in");
		builder.addEdge(latch, "value", debug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), trigger, 1.0, context);

		assertTrue(result.success());
		assertEquals(List.of("seed"), context.messages);
		assertFalse(result.errors().stream().anyMatch(error -> error.code() == GraphErrorCode.CONTROL_CYCLE_DETECTED));
		assertFalse(result.visitedNodes().contains(update));
	}

	@Test
	void latchSameRouteLoopStillReportsControlCycle() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId value = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("1"), new NodePosition(220, 20));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		NodeId loop = builder.addNode(BuiltinNodeTypes.WHILE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 20));
		builder.setInlineInput(loop, "flag", NodeConfigValues.booleanValue(true));
		builder.addEdge(value, "value", latch, "valueB");
		builder.addEdge(latch, "outB", loop, "in");
		builder.addEdge(loop, "body", latch, "storeB");

		CompiledGraphDocument compiled = CompiledGraphDocument.compile(
			registry,
			portTypes,
			GraphDocument.of(builder.buildGraph(), new GraphLayout(Map.of()), List.of(), List.of())
		);

		assertTrue(compiled.compileErrors().stream().anyMatch(error -> error.code() == GraphErrorCode.CONTROL_CYCLE_DETECTED));
	}

	@Test
	void whileNodeRunsSubgraphBodyUntilConditionTurnsFalse() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		List<GraphVariableDefinition> variables = List.of(new GraphVariableDefinition("count", "Count", MCNGPortTypes.INT.id(), NodeConfigValues.intValue(0)));
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId getCount = builder.addNode(BuiltinNodeTypes.GET_VARIABLE, new BuiltinNodeTypes.VariableReferenceConfig("count"), new NodePosition(220, 20));
		NodeId limit = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("3"), new NodePosition(220, 120));
		NodeId lessThan = builder.addNode(BuiltinNodeTypes.LESS_THAN, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		NodeId one = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("1"), new NodePosition(420, 180));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 180));
		NodeId setCount = builder.addNode(BuiltinNodeTypes.SET_VARIABLE, new BuiltinNodeTypes.VariableReferenceConfig("count"), new NodePosition(820, 180));
		NodeId loop = builder.addNode(BuiltinNodeTypes.WHILE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 70));
		NodeId finalDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(860, 70));
		builder.addEdge(trigger, "out", loop, "in");
		builder.addEdge(getCount, "value", lessThan, "left");
		builder.addEdge(limit, "value", lessThan, "right");
		builder.addEdge(lessThan, "value", loop, "flag");
		builder.addEdge(loop, "body", setCount, "in");
		builder.addEdge(getCount, "value", add, "left");
		builder.addEdge(one, "value", add, "right");
		builder.addEdge(add, "value", setCount, "value");
		builder.addEdge(loop, "done", finalDebug, "in");
		builder.addEdge(getCount, "value", finalDebug, "value");

		RecordingContext context = new RecordingContext();
		ExecutionResult result = new GraphExecutor(registry, portTypes).triggerEvent(builder.buildGraph(), variables, trigger, 1.0, context);

		assertTrue(result.success());
		assertEquals(List.of("3"), context.messages);
	}

	@Test
	void executionSessionCanStepAndCancelInfiniteWhile() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId loop = builder.addNode(BuiltinNodeTypes.WHILE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.setInlineInput(loop, "flag", NodeConfigValues.booleanValue(true));
		builder.addEdge(trigger, "out", loop, "in");

		CompiledGraphDocument compiled = CompiledGraphDocument.compile(
			registry,
			portTypes,
			GraphDocument.of(builder.buildGraph(), new GraphLayout(Map.of()), List.of(), List.of())
		);
		GraphExecutionSession session = compiled.startEventExecution(trigger, 1.0, new RecordingContext());

		ExecutionSnapshot first = session.step(1);
		ExecutionSnapshot second = session.step(1);

		assertTrue(first.running());
		assertTrue(second.running());
		assertTrue(second.visitedNodes().contains(loop));

		session.cancel();
		assertTrue(session.snapshot().cancelled());
	}

	@Test
	void whileLoopRecomputesDataReroutesAfterLatchWrites() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId limit = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("3"), new NodePosition(220, 20));
		NodeId increment = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("1"), new NodePosition(220, 140));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 80));
		NodeId lessThan = builder.addNode(BuiltinNodeTypes.LESS_THAN, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 20));
		NodeId loop = builder.addNode(BuiltinNodeTypes.WHILE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(820, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(620, 140));
		NodeId inputReroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 140));
		NodeId debugReroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(980, 140));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(1160, 80));

		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(trigger, "number", latch, "valueA");
		builder.addEdge(latch, "outA", loop, "in");
		builder.addEdge(latch, "value", lessThan, "left");
		builder.addEdge(limit, "value", lessThan, "right");
		builder.addEdge(lessThan, "value", loop, "flag");
		builder.addEdge(loop, "body", latch, "storeB");
		builder.addEdge(latch, "value", inputReroute, "value");
		builder.addEdge(inputReroute, "value", add, "left");
		builder.addEdge(increment, "value", add, "right");
		builder.addEdge(add, "value", latch, "valueB");
		builder.addEdge(loop, "body", debug, "in");
		builder.addEdge(latch, "value", debugReroute, "value");
		builder.addEdge(debugReroute, "value", debug, "value");

		CompiledGraphDocument compiled = CompiledGraphDocument.compile(
			registry,
			portTypes,
			GraphDocument.of(builder.buildGraph(), new GraphLayout(Map.of()), List.of(), List.of())
		);
		RecordingContext context = new RecordingContext();
		GraphExecutionSession session = compiled.startEventExecution(trigger, 1.0, context);

		for (int step = 0; step < 60 && session.status() == ExecutionSessionStatus.RUNNING; step++) {
			session.step(1);
		}

		ExecutionSnapshot snapshot = session.snapshot();
		assertFalse(snapshot.running());
		assertTrue(snapshot.errors().isEmpty());
		assertEquals(List.of("2", "3"), context.messages);
		assertEquals(3L, snapshot.outputs().get(latch).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void mutatingCompositeInputDoesNotAffectUpstreamOutputCache() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		NodeType<BuiltinNodeTypes.EmptyConfig> mutateList = new TestNodeType<>(
			"test:mutate_list",
			"Mutate List",
			NodeKind.COMPUTE,
			List.of(PortDefinition.input("list", "List", MCNGPortTypes.LIST, true)),
			List.of(PortDefinition.output("value", "Value", MCNGPortTypes.LIST)),
			BuiltinNodeTypes.EmptyConfig.INSTANCE,
			BuiltinNodeTypes.EmptyConfig.CODEC,
			request -> {
				@SuppressWarnings("unchecked")
				List<Object> values = (List<Object>) request.inputs().get(new PortId("list"));
				values.add("tail");
				return NodeExecutionResult.of(Map.of(BuiltinNodeTypes.VALUE_PORT, values));
			}
		);
		registry.register(mutateList);

		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId first = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("left"), new NodePosition(20, 20));
		NodeId second = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("right"), new NodePosition(20, 120));
		NodeId list = builder.addNode(BuiltinNodeTypes.LIST_CREATE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 70));
		NodeId mutate = builder.addNode(mutateList, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		builder.addEdge(first, "value", list, "first");
		builder.addEdge(second, "value", list, "second");
		builder.addEdge(list, "value", mutate, "list");

		ExecutionResult result = new GraphExecutor(registry, portTypes).execute(builder.buildGraph(), new RecordingContext());

		assertTrue(result.success(), String.valueOf(result.errors()));
		assertEquals(List.of("left", "right"), result.outputs().get(list).get(BuiltinNodeTypes.VALUE_PORT));
		assertEquals(List.of("left", "right", "tail"), result.outputs().get(mutate).get(BuiltinNodeTypes.VALUE_PORT));
	}

	@Test
	void executionOptionsCanRejectOversizedCompositeOutputs() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId first = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("left"), new NodePosition(20, 20));
		NodeId second = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("right"), new NodePosition(20, 120));
		NodeId list = builder.addNode(BuiltinNodeTypes.LIST_CREATE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 70));
		builder.addEdge(first, "value", list, "first");
		builder.addEdge(second, "value", list, "second");

		GraphExecutionOptions options = new GraphExecutionOptions(new RuntimeValueLimits(2, 8));
		ExecutionResult result = new GraphExecutor(registry, portTypes, options).execute(builder.buildGraph(), new RecordingContext());

		assertFalse(result.success());
		assertEquals(GraphErrorCode.VALUE_LIMIT_EXCEEDED, result.errors().getFirst().code());
		assertFalse(result.outputs().containsKey(list));
	}

	@Test
	void completedSessionClearsCompositeLocalState() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);

		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId first = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("left"), new NodePosition(220, 20));
		NodeId second = builder.addNode(BuiltinNodeTypes.STRING_CONSTANT, new StringConstantConfig("right"), new NodePosition(220, 120));
		NodeId list = builder.addNode(BuiltinNodeTypes.LIST_CREATE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 70));
		NodeId latch = builder.addNode(BuiltinNodeTypes.LATCH, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(640, 70));
		builder.addEdge(trigger, "out", latch, "storeA");
		builder.addEdge(first, "value", list, "first");
		builder.addEdge(second, "value", list, "second");
		builder.addEdge(list, "value", latch, "valueA");

		CompiledGraphDocument compiled = CompiledGraphDocument.compile(
			registry,
			portTypes,
			GraphDocument.of(builder.buildGraph(), new GraphLayout(Map.of()), List.of(), List.of())
		);
		GraphExecutionSession session = compiled.startEventExecution(trigger, 1.0, new RecordingContext());

		session.runToCompletion();

		assertTrue(session.snapshot().completed());
		assertTrue(reflectMapField(readField(session, "runtimeState"), "localState").isEmpty());
	}

	private boolean assertThrowsTypeMismatch(GraphBuilder builder, NodeId stringValue, NodeId add) {
		return assertThrowsTypeMismatch(builder, stringValue, add, "value", "left");
	}

	private boolean assertThrowsTypeMismatch(GraphBuilder builder, NodeId fromNode, NodeId toNode, String fromPortId, String toPortId) {
		try {
			builder.addEdge(fromNode, fromPortId, toNode, toPortId);
			return false;
		} catch (IllegalArgumentException exception) {
			return exception.getMessage().contains("Incompatible edge types");
		}
	}

	private NodeTypeRegistry registry() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeTypes.registerAll(registry);
		return registry;
	}

	private PortTypeRegistry portTypes() {
		return MCNGPortTypes.createRegistry();
	}

	private static BuiltinNodeTypes.NumericConstantConfig numericConstant(String value) {
		return new BuiltinNodeTypes.NumericConstantConfig(value);
	}

	@SuppressWarnings("unchecked")
	private <C> NodeType<C> typed(NodeType<?> nodeType) {
		return (NodeType<C>) nodeType;
	}

	private JsonObject portConfig(String name) {
		return portConfig(name, false);
	}

	private JsonObject portConfig(String name, boolean inlineInput) {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		if (inlineInput) {
			json.addProperty("inlineInput", true);
		}
		return json;
	}

	private static final class RecordingContext implements NodeExecutionContext {
		private final List<String> messages = new ArrayList<>();

		@Override
		public void publishDebug(NodeId nodeId, String message) {
			messages.add(message);
		}
	}

	private Object readField(Object instance, String fieldName) {
		try {
			Field field = instance.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object value = field.get(instance);
			assertNotNull(value);
			return value;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Failed to read field " + fieldName, exception);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<?, ?> reflectMapField(Object instance, String fieldName) {
		Object value = readField(instance, fieldName);
		if (!(value instanceof Map<?, ?> map)) {
			throw new AssertionError("Field " + fieldName + " is not a map");
		}
		return map;
	}

	private record IntConfig(int value) {
	}

	private record TestNodeType<C>(
		String id,
		String displayName,
		NodeKind kind,
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		C defaultConfig,
		NodeConfigCodec<C> configCodec,
		Executor<C> executor
	) implements NodeType<C> {
		@Override
		public NodeExecutionResult execute(NodeExecutionRequest<C> request) {
			return executor.execute(request);
		}
	}

	private interface Executor<C> {
		NodeExecutionResult execute(NodeExecutionRequest<C> request);
	}

	private static final class IntConfigCodec implements NodeConfigCodec<IntConfig> {
		@Override
		public JsonObject toJson(IntConfig config) {
			JsonObject json = new JsonObject();
			json.addProperty("value", config.value());
			return json;
		}

		@Override
		public IntConfig fromJson(JsonObject json) {
			return new IntConfig(json.get("value").getAsInt());
		}
	}
}
