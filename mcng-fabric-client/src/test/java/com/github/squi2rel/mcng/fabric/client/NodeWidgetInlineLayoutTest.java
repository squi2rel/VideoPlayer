package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphBuilder;
import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphErrorCode;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.GraphScope;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortInlineWidget;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.SubgraphDefinition;
import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.EdgeDefinition;
import com.github.squi2rel.mcng.core.DocumentNodeDefinition;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.ManualTriggerConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NodeWidgetInlineLayoutTest {
	@Test
	void fullDetailIncludesPortRowsAndPureControlRows() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", true, "boxed"), new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget widget = widget(session, trigger, 1.0);

		assertEquals(NodeRenderDetailLevel.FULL, widget.detailLevel());
		assertEquals(6, widget.rows().size());
		assertTrue(widget.rows().stream().anyMatch(row -> row instanceof NodeWidget.BooleanControlRowWidget));
		assertTrue(widget.rows().stream().anyMatch(row -> row instanceof NodeWidget.CycleControlRowWidget));
	}

	@Test
	void mixedControlAndDataPortsExposeAChannelSeparator() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget widget = widget(session, debug, 1.0);

		assertEquals(1, widget.channelSeparators().size());
	}

	@Test
	void connectedInputHidesItsInlineField() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("7.0"), new NodePosition(20, 20));
		var add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.addEdge(source, "value", add, "left");

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget widget = widget(session, add, 1.0);
		var inputRows = widget.rows().stream()
			.filter(NodeWidget.InputPortRowWidget.class::isInstance)
			.map(NodeWidget.InputPortRowWidget.class::cast)
			.toList();

		assertEquals(2, inputRows.size());
		assertEquals("left", inputRows.getFirst().port().definition().id().value());
		assertNull(inputRows.getFirst().fieldBounds());
		assertEquals("right", inputRows.get(1).port().definition().id().value());
		assertNotNull(inputRows.get(1).fieldBounds());
	}

	@Test
	void minimumZoomKeepsLayoutButHidesInteractiveContent() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget fullWidget = widget(session, trigger, 1.0);
		NodeWidget minimalWidget = widget(session, trigger, GraphViewportState.MIN_ZOOM);

		assertEquals(NodeRenderDetailLevel.FULL, fullWidget.detailLevel());
		assertEquals(NodeRenderDetailLevel.MINIMAL, minimalWidget.detailLevel());
		assertEquals(fullWidget.height(), minimalWidget.height());
		assertEquals(fullWidget.rows().size(), minimalWidget.rows().size());
		assertEquals(fullWidget.ports().size(), minimalWidget.ports().size());
		for (int index = 0; index < fullWidget.ports().size(); index++) {
			assertEquals(fullWidget.ports().get(index).centerX(), minimalWidget.ports().get(index).centerX());
			assertEquals(fullWidget.ports().get(index).centerY(), minimalWidget.ports().get(index).centerY());
		}
	}

	@Test
	void booleanInlinePortUsesInteractiveFieldBounds() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var not = builder.addNode(BuiltinNodeTypes.NOT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget widget = widget(session, not, 1.0);
		var inputRow = widget.rows().stream()
			.filter(NodeWidget.InputPortRowWidget.class::isInstance)
			.map(NodeWidget.InputPortRowWidget.class::cast)
			.findFirst()
			.orElseThrow();

		assertEquals("flag", inputRow.port().definition().id().value());
		assertNotNull(inputRow.fieldBounds());
	}

	@Test
	void sessionMarksNodesThatHaveExecutionErrors() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		builder.setInlineInput(add, "left", NodeConfigValues.stringValue("oops"));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(7.0));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		session.executeGraph();
		var snapshot = session.tickExecution(8);
		var result = snapshot.toExecutionResult();

		assertFalse(result.success());
		assertEquals(GraphErrorCode.INVALID_INLINE_INPUT, result.errors().getFirst().code());
		assertTrue(session.hasError(add));
	}

	@Test
	void rerouteUsesCompactSingleRowLayoutWithoutHeader() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		var identity = builder.addNode(BuiltinNodeTypes.IDENTITY, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(120, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget rerouteWidget = widget(session, reroute, 1.0);
		NodeWidget identityWidget = widget(session, identity, 1.0);

		assertEquals(0, rerouteWidget.headerHeight());
		assertEquals(0, rerouteWidget.rows().size());
		assertTrue(rerouteWidget.width() < identityWidget.width());
		assertEquals(2, rerouteWidget.ports().size());
		assertEquals(rerouteWidget.ports().getFirst().centerY(), rerouteWidget.ports().getLast().centerY());
	}

	@Test
	void canvasPortLookupDistinguishesDirectionWhenIdsMatch() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		NodeWidget rerouteWidget = widget(session, reroute, 1.0);

		var inputPort = GraphCanvasComponent.findPortWidget(rerouteWidget, new PortId("value"), PortDirection.INPUT);
		var outputPort = GraphCanvasComponent.findPortWidget(rerouteWidget, new PortId("value"), PortDirection.OUTPUT);

		assertNotNull(inputPort);
		assertNotNull(outputPort);
		assertTrue(inputPort.centerX() < outputPort.centerX());
		assertEquals(PortDirection.INPUT, inputPort.definition().direction());
		assertEquals(PortDirection.OUTPUT, outputPort.definition().direction());
	}

	@Test
	void flippedRerouteSwapsInputAndOutputSides() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("7.0"), new NodePosition(20, 20));
		var reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(160, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		assertTrue(session.toggleConnectionCandidate(source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertTrue(session.toggleConnectionCandidate(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT, true));

		NodeWidget rerouteWidget = widget(session, reroute, 1.0);
		var inputPort = GraphCanvasComponent.findPortWidget(rerouteWidget, new PortId("value"), PortDirection.INPUT);
		var outputPort = GraphCanvasComponent.findPortWidget(rerouteWidget, new PortId("value"), PortDirection.OUTPUT);

		assertNotNull(inputPort);
		assertNotNull(outputPort);
		assertTrue(inputPort.centerX() > outputPort.centerX());
	}

	@Test
	void verticalRerouteUsesTopAndBottomPorts() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		session.resizeReroute(reroute, new NodePosition(20, 20), new com.github.squi2rel.mcng.core.NodeSize(16, 92), RerouteOrientation.TOP_TO_BOTTOM);
		NodeWidget rerouteWidget = widget(session, reroute, 1.0);
		var inputPort = GraphCanvasComponent.findPortWidget(rerouteWidget, new PortId("value"), PortDirection.INPUT);
		var outputPort = GraphCanvasComponent.findPortWidget(rerouteWidget, new PortId("value"), PortDirection.OUTPUT);

		assertNotNull(inputPort);
		assertNotNull(outputPort);
		assertEquals(16, rerouteWidget.width());
		assertEquals(92, rerouteWidget.height());
		assertEquals(NodeWidget.PortSide.TOP, inputPort.side());
		assertEquals(NodeWidget.PortSide.BOTTOM, outputPort.side());
		assertEquals(inputPort.centerX(), outputPort.centerX());
		assertTrue(inputPort.centerY() < outputPort.centerY());
	}

	@Test
	void subgraphNodePutsNameFieldOnTopAsFullWidthRow() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = session(registry, portTypes, subgraphDocument());
		NodeWidget widget = widget(session, new NodeId("subgraphNode"), 1.0);

		assertEquals(22, widget.headerHeight());
		assertFalse(widget.rows().isEmpty());
		assertTrue(widget.rows().getFirst() instanceof NodeWidget.TextControlRowWidget);

		NodeWidget.TextControlRowWidget nameRow = (NodeWidget.TextControlRowWidget) widget.rows().getFirst();
		assertFalse(nameRow.labelVisible());
		assertEquals(DocumentNodeTypes.DEFINITION_NAME_CONTROL_KEY, nameRow.control().key());
		assertEquals(widget.y(), nameRow.y());
		assertEquals(widget.headerHeight(), nameRow.height());
		assertEquals(widget.x() + widget.edgePadding(), nameRow.fieldBounds().x());
		assertEquals(widget.width() - (widget.edgePadding() * 2), nameRow.fieldBounds().width());
		assertTrue(nameRow.fieldBounds().y() >= widget.y());
		assertTrue(nameRow.fieldBounds().y() + nameRow.fieldBounds().height() <= widget.y() + widget.headerHeight());

		NodeWidget.InputPortRowWidget inputRow = widget.rows().stream()
			.skip(1)
			.filter(NodeWidget.InputPortRowWidget.class::isInstance)
			.map(NodeWidget.InputPortRowWidget.class::cast)
			.findFirst()
			.orElseThrow();
		assertTrue(nameRow.y() < inputRow.y());
	}

	@Test
	void customNodePutsNameFieldIntoHeaderAsFullWidthRow() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = session(registry, portTypes, customDocument());
		NodeWidget widget = widget(session, new NodeId("customNode"), 1.0);

		assertEquals(22, widget.headerHeight());
		assertFalse(widget.rows().isEmpty());
		assertTrue(widget.rows().getFirst() instanceof NodeWidget.TextControlRowWidget);

		NodeWidget.TextControlRowWidget nameRow = (NodeWidget.TextControlRowWidget) widget.rows().getFirst();
		assertFalse(nameRow.labelVisible());
		assertEquals(DocumentNodeTypes.DEFINITION_NAME_CONTROL_KEY, nameRow.control().key());
		assertEquals(widget.y(), nameRow.y());
		assertEquals(widget.headerHeight(), nameRow.height());
		assertEquals(widget.x() + widget.edgePadding(), nameRow.fieldBounds().x());
		assertEquals(widget.width() - (widget.edgePadding() * 2), nameRow.fieldBounds().width());

		NodeWidget.InputPortRowWidget inputRow = widget.rows().stream()
			.skip(1)
			.filter(NodeWidget.InputPortRowWidget.class::isInstance)
			.map(NodeWidget.InputPortRowWidget.class::cast)
			.findFirst()
			.orElseThrow();
		assertTrue(nameRow.y() < inputRow.y());
	}

	@Test
	void customNodeShowsNumericInlineFieldWhenGraphInputEnablesIt() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = session(registry, portTypes, customInlineNumericDocument());
		NodeWidget widget = widget(session, new NodeId("customNode"), 1.0);

		NodeWidget.InputPortRowWidget inputRow = widget.rows().stream()
			.filter(NodeWidget.InputPortRowWidget.class::isInstance)
			.map(NodeWidget.InputPortRowWidget.class::cast)
			.findFirst()
			.orElseThrow();

		assertNotNull(inputRow.fieldBounds());
		assertNotNull(inputRow.port().definition().inlineWidget());
		assertEquals(PortInlineWidget.Kind.NUMERIC_TEXT, inputRow.port().definition().inlineWidget().kind());
	}

	@Test
	void subgraphNodeShowsBooleanInlineToggleWhenGraphInputEnablesIt() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = session(registry, portTypes, subgraphInlineBooleanDocument());
		NodeWidget widget = widget(session, new NodeId("subgraphNode"), 1.0);

		NodeWidget.InputPortRowWidget inputRow = widget.rows().stream()
			.filter(NodeWidget.InputPortRowWidget.class::isInstance)
			.map(NodeWidget.InputPortRowWidget.class::cast)
			.findFirst()
			.orElseThrow();

		assertNotNull(inputRow.fieldBounds());
		assertNotNull(inputRow.port().definition().inlineWidget());
		assertEquals(PortInlineWidget.Kind.BOOLEAN_TOGGLE, inputRow.port().definition().inlineWidget().kind());
	}

	private GraphEditorSession session(NodeTypeRegistry registry, PortTypeRegistry portTypes, GraphDocument document) {
		return new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), document, new TestHost());
	}

	private NodeWidget widget(GraphEditorSession session, NodeId nodeId, double zoom) {
		var node = session.node(nodeId);
		GraphLayout layout = new GraphLayout(session.positions(), session.sizes());
		GraphViewportState viewport = new GraphViewportState();
		if (zoom != 1.0) {
			viewport.zoomAt(zoom - 1.0, 0, 0);
		}
		return new NodeWidget(node, session.nodeType(node), session, layout, viewport);
	}

	private NodeTypeRegistry registry() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeTypes.registerAll(registry);
		return registry;
	}

	private GraphDocument subgraphDocument() {
		GraphDefinition subgraphGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("in"), DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID, portConfig("Input")),
				new NodeInstance(new NodeId("out"), DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID, portConfig("Output"))
			),
			List.of(new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("out"), new PortId("value")))
		);
		GraphLayout subgraphLayout = new GraphLayout(Map.of(
			new NodeId("in"), new NodePosition(20, 20),
			new NodeId("out"), new NodePosition(220, 20)
		));
		GraphDefinition rootGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("subgraphNode"), DocumentNodeTypes.subgraphTypeId("subgraph"), new JsonObject())
			),
			List.of()
		);
		GraphLayout rootLayout = new GraphLayout(Map.of(
			new NodeId("subgraphNode"), new NodePosition(20, 20)
		));
		return GraphDocument.of(
			GraphScope.of(
				rootGraph,
				rootLayout,
				List.of(),
				List.of(new SubgraphDefinition("subgraph", "Subgraph", GraphScope.of(subgraphGraph, subgraphLayout)))
			),
			List.of()
		);
	}

	private GraphDocument customDocument() {
		GraphDefinition definitionGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("in"), DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, portConfig("Input")),
				new NodeInstance(new NodeId("out"), DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID, portConfig("Output"))
			),
			List.of(new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("out"), new PortId("value")))
		);
		GraphLayout definitionLayout = new GraphLayout(Map.of(
			new NodeId("in"), new NodePosition(20, 20),
			new NodeId("out"), new NodePosition(220, 20)
		));
		GraphDefinition rootGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("customNode"), DocumentNodeTypes.definitionTypeId("custom"), new JsonObject())
			),
			List.of()
		);
		GraphLayout rootLayout = new GraphLayout(Map.of(
			new NodeId("customNode"), new NodePosition(20, 20)
		));
		return GraphDocument.of(
			GraphScope.of(rootGraph, rootLayout),
			List.of(new DocumentNodeDefinition("custom", "Custom", GraphScope.of(definitionGraph, definitionLayout)))
		);
	}

	private GraphDocument customInlineNumericDocument() {
		GraphDefinition definitionGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("in"), DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, portConfig("Input", true)),
				new NodeInstance(new NodeId("add"), BuiltinNodeTypes.ADD.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE)),
				new NodeInstance(new NodeId("out"), DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID, portConfig("Output"))
			),
			List.of(
				new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("add"), BuiltinNodeTypes.LEFT_PORT),
				new EdgeDefinition(new NodeId("add"), BuiltinNodeTypes.VALUE_PORT, new NodeId("out"), new PortId("value"))
			)
		);
		GraphLayout definitionLayout = new GraphLayout(Map.of(
			new NodeId("in"), new NodePosition(20, 20),
			new NodeId("add"), new NodePosition(180, 20),
			new NodeId("out"), new NodePosition(360, 20)
		));
		GraphDefinition rootGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("customNode"), DocumentNodeTypes.definitionTypeId("custom_inline"), new JsonObject())
			),
			List.of()
		);
		GraphLayout rootLayout = new GraphLayout(Map.of(
			new NodeId("customNode"), new NodePosition(20, 20)
		));
		return GraphDocument.of(
			GraphScope.of(rootGraph, rootLayout),
			List.of(new DocumentNodeDefinition("custom_inline", "Custom Inline", GraphScope.of(definitionGraph, definitionLayout)))
		);
	}

	private GraphDocument subgraphInlineBooleanDocument() {
		GraphDefinition subgraphGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("in"), DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID, portConfig("Enabled", true)),
				new NodeInstance(new NodeId("not"), BuiltinNodeTypes.NOT.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE)),
				new NodeInstance(new NodeId("out"), DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID, portConfig("Output"))
			),
			List.of(
				new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("not"), BuiltinNodeTypes.BOOLEAN_PORT),
				new EdgeDefinition(new NodeId("not"), BuiltinNodeTypes.VALUE_PORT, new NodeId("out"), new PortId("value"))
			)
		);
		GraphLayout subgraphLayout = new GraphLayout(Map.of(
			new NodeId("in"), new NodePosition(20, 20),
			new NodeId("not"), new NodePosition(180, 20),
			new NodeId("out"), new NodePosition(340, 20)
		));
		GraphDefinition rootGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("subgraphNode"), DocumentNodeTypes.subgraphTypeId("subgraph_inline_bool"), new JsonObject())
			),
			List.of()
		);
		GraphLayout rootLayout = new GraphLayout(Map.of(
			new NodeId("subgraphNode"), new NodePosition(20, 20)
		));
		return GraphDocument.of(
			GraphScope.of(
				rootGraph,
				rootLayout,
				List.of(),
				List.of(new SubgraphDefinition("subgraph_inline_bool", "Inline Bool", GraphScope.of(subgraphGraph, subgraphLayout)))
			),
			List.of()
		);
	}

	private static JsonObject portConfig(String name) {
		return portConfig(name, false);
	}

	private static JsonObject portConfig(String name, boolean inlineInput) {
		JsonObject json = new JsonObject();
		json.addProperty("name", name);
		if (inlineInput) {
			json.addProperty("inlineInput", true);
		}
		return json;
	}

	private static final class TestHost implements GraphEditorHost {
		@Override
		public void onDocumentChanged(GraphDocument document) {
		}

		@Override
		public void copyToClipboard(String value) {
		}

		@Override
		public String readClipboard() {
			return "";
		}

		@Override
		public void showMessage(String message) {
		}
	}
}
