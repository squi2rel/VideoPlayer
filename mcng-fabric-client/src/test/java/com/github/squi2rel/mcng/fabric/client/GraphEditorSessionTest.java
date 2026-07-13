package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeDefinition;
import com.github.squi2rel.mcng.core.DocumentNodeDefinitionKind;
import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.EdgeDefinition;
import com.github.squi2rel.mcng.core.GraphBuilder;
import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.GraphScope;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortChannel;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.SubgraphDefinition;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.ManualTriggerConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorSessionTest {
	@Test
	void subgraphEditorPropagatesParentTypeIntoInnerGenericChain() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			document(DocumentNodeDefinitionKind.SUBGRAPH, "subgraph"),
			new TestHost()
		);

		assertTrue(session.enterDefinition(new NodeId("subgraphNode")));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("in"), new PortId("value"), PortDirection.OUTPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("identity"), new PortId("value"), PortDirection.INPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("identity"), new PortId("value"), PortDirection.OUTPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("out"), new PortId("value"), PortDirection.INPUT));
	}

	@Test
	void customNodeEditorDoesNotPullTypeBackFromParentInstance() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			document(DocumentNodeDefinitionKind.CUSTOM_NODE, "custom"),
			new TestHost()
		);

		assertTrue(session.enterDefinition(new NodeId("customNode")));
		assertEquals(MCNGPortTypes.ANY, session.effectivePortType(new NodeId("in"), new PortId("value"), PortDirection.OUTPUT));
		assertEquals(MCNGPortTypes.ANY, session.effectivePortType(new NodeId("identity"), new PortId("value"), PortDirection.INPUT));
	}

	@Test
	void copyAndPasteSelectionUsesLocalClipboard() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			rootDocument(),
			new TestHost()
		);

		session.selectNodes(List.of(new NodeId("left"), new NodeId("right")), false);
		assertTrue(session.copySelectionToLocalClipboard());
		assertTrue(session.hasLocalClipboard());
		assertTrue(session.pasteLocalClipboard(300, 180));

		assertEquals(5, session.nodes().size());
		assertEquals(2, session.selectedNodeIds().size());
		assertTrue(session.selectedNodeIds().stream().allMatch(nodeId -> !nodeId.equals(new NodeId("left")) && !nodeId.equals(new NodeId("right"))));
	}

	@Test
	void removingSelectedNodesDoesNotAutoSelectAnotherNode() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			rootDocument(),
			new TestHost()
		);

		session.selectNode(new NodeId("left"));
		session.removeSelectedNodes();

		assertTrue(session.selectedNodeIds().isEmpty());
		assertTrue(session.selectedNodeId().isEmpty());
		assertNull(session.pendingConnection());
		assertEquals(2, session.nodes().size());
		assertEquals(1, session.edges().size());
	}

	@Test
	void clearingOutputPortRemovesAllOutgoingEdges() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("4.0"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 140));
		builder.addEdge(source, "value", add, "left");
		builder.addEdge(source, "value", debug, "value");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		session.clearPortConnections(source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT);

		assertTrue(session.edges().isEmpty());
	}

	@Test
	void clearingInputPortRemovesIncomingEdges() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("4.0"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.addEdge(source, "value", add, "left");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		session.clearPortConnections(add, BuiltinNodeTypes.LEFT_PORT, PortDirection.INPUT);

		assertTrue(session.edges().isEmpty());
	}

	@Test
	void freshAddNodeInfersNumericTypesFromInlineDefaults() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(add, BuiltinNodeTypes.LEFT_PORT, PortDirection.INPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(add, BuiltinNodeTypes.RIGHT_PORT, PortDirection.INPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(add, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
	}

	@Test
	void definitionBoundaryNumericInlineDefaultsInferAsDouble() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			customInlineNumericDocument(false),
			new TestHost()
		);

		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("customNode"), new PortId("in"), PortDirection.INPUT));
	}

	@Test
	void definitionBoundaryConnectionTypeOverridesNumericInlineInference() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			customInlineNumericDocument(true),
			new TestHost()
		);

		assertEquals(MCNGPortTypes.LONG, session.effectivePortType(new NodeId("customNode"), new PortId("in"), PortDirection.INPUT));
	}

	@Test
	void pastedSubgraphNodeGetsNewDefinitionId() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			document(DocumentNodeDefinitionKind.SUBGRAPH, "subgraph"),
			new TestHost()
		);

		session.selectNode(new NodeId("subgraphNode"));
		assertTrue(session.copySelectionToLocalClipboard());
		assertTrue(session.pasteLocalClipboard(260, 140));

		assertEquals(2, session.document().subgraphs().size());
		NodeInstance original = session.node(new NodeId("subgraphNode"));
		NodeInstance pasted = session.selectedNodeIds().stream()
			.map(session::node)
			.filter(node -> !node.id().equals(new NodeId("subgraphNode")))
			.findFirst()
			.orElseThrow();
		assertTrue(DocumentNodeTypes.isSubgraphType(pasted.typeId()));
		assertFalse(pasted.typeId().equals(original.typeId()));
	}

	@Test
	void pastedCustomNodeKeepsOriginalDefinitionId() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			document(DocumentNodeDefinitionKind.CUSTOM_NODE, "custom"),
			new TestHost()
		);

		session.selectNode(new NodeId("customNode"));
		assertTrue(session.copySelectionToLocalClipboard());
		assertTrue(session.pasteLocalClipboard(260, 140));

		assertEquals(1, session.definitions().size());
		NodeInstance original = session.node(new NodeId("customNode"));
		NodeInstance pasted = session.selectedNodeIds().stream()
			.map(session::node)
			.filter(node -> !node.id().equals(new NodeId("customNode")))
			.findFirst()
			.orElseThrow();
		assertEquals(original.typeId(), pasted.typeId());
	}

	@Test
	void helperNodesCannotBePastedIntoRootGraph() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			document(DocumentNodeDefinitionKind.SUBGRAPH, "subgraph"),
			new TestHost()
		);

		assertTrue(session.enterDefinition(new NodeId("subgraphNode")));
		session.selectNode(new NodeId("in"));
		assertTrue(session.copySelectionToLocalClipboard());
		assertTrue(session.exitToBreadcrumb(null));
		assertFalse(session.pasteLocalClipboard(40, 40));
	}

	@Test
	void autoCastConnectedToGenericNodeDoesNotOverflowDuringTypeResolution() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphDefinition graph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("source"), BuiltinNodeTypes.NUMERIC_CONSTANT.id(), BuiltinNodeTypes.NUMERIC_CONSTANT.configCodec().toJson(new BuiltinNodeTypes.NumericConstantConfig("4.0"))),
				new NodeInstance(new NodeId("cast"), BuiltinNodeTypes.CAST.id(), BuiltinNodeTypes.CAST.configCodec().toJson(new BuiltinNodeTypes.CastConfig("auto"))),
				new NodeInstance(new NodeId("debug"), BuiltinNodeTypes.DEBUG_OUTPUT.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE))
			),
			List.of(
				new EdgeDefinition(new NodeId("source"), new PortId("value"), new NodeId("cast"), new PortId("value")),
				new EdgeDefinition(new NodeId("cast"), new PortId("value"), new NodeId("debug"), new PortId("value"))
			)
		);
		GraphDocument document = GraphDocument.of(graph, new GraphLayout(Map.of()));
		GraphEditorSession session = new GraphEditorSession(
			registry,
			portTypes,
			new GraphJsonCodec(),
			document,
			new TestHost()
		);

		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("cast"), new PortId("value"), PortDirection.OUTPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("debug"), new PortId("value"), PortDirection.INPUT));
		assertEquals(MCNGPortTypes.DOUBLE, session.effectivePortType(new NodeId("debug"), new PortId("value"), PortDirection.OUTPUT));
	}

	@Test
	void reroutePortsResolveAsControlWhenConnectedToControlFlow() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(trigger, "out", reroute, "value");
		builder.addEdge(reroute, "value", debug, "in");
		builder.addEdge(trigger, "message", debug, "value");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		assertEquals(PortChannel.CONTROL, session.effectivePortChannel(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(PortChannel.CONTROL, session.effectivePortChannel(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertEquals(MCNGPortTypes.ANY, session.effectivePortType(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
	}

	@Test
	void rerouteChainResolvesAsControlAcrossMultipleNodes() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId rerouteA = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId rerouteB = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 20));
		builder.addEdge(trigger, "out", rerouteA, "value");
		builder.addEdge(rerouteA, "value", rerouteB, "value");
		builder.addEdge(rerouteB, "value", debug, "in");
		builder.addEdge(trigger, "message", debug, "value");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		assertEquals(PortChannel.CONTROL, session.effectivePortChannel(rerouteA, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(PortChannel.CONTROL, session.effectivePortChannel(rerouteA, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertEquals(PortChannel.CONTROL, session.effectivePortChannel(rerouteB, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(PortChannel.CONTROL, session.effectivePortChannel(rerouteB, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
	}

	@Test
	void editorRejectsMixingDataIntoControlRerouteChain() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(trigger, "out", reroute, "value");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		assertTrue(session.toggleConnectionCandidate(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertFalse(session.toggleConnectionCandidate(debug, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(1, session.edges().size());
	}

	@Test
	void editorCanReverseRerouteChainWhenAnchoredLater() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("3"), new NodePosition(20, 20));
		NodeId rerouteA = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId rerouteB = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 20));

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		assertTrue(session.toggleConnectionCandidate(rerouteA, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertTrue(session.toggleConnectionCandidate(rerouteB, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertTrue(session.edges().stream().anyMatch(edge ->
			edge.fromNodeId().equals(rerouteA)
				&& edge.toNodeId().equals(rerouteB)
				&& edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)
				&& edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)
		));

		assertTrue(session.toggleConnectionCandidate(source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertTrue(session.toggleConnectionCandidate(rerouteB, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(2, session.edges().size());
		assertTrue(session.edges().stream().anyMatch(edge ->
			edge.fromNodeId().equals(source)
				&& edge.toNodeId().equals(rerouteB)
				&& edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)
				&& edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)
		));
		assertTrue(session.edges().stream().anyMatch(edge ->
			edge.fromNodeId().equals(rerouteB)
				&& edge.toNodeId().equals(rerouteA)
				&& edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)
				&& edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)
		));

		assertTrue(session.toggleConnectionCandidate(rerouteA, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertTrue(session.toggleConnectionCandidate(debug, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(3, session.edges().size());
		assertTrue(session.edges().stream().anyMatch(edge ->
			edge.fromNodeId().equals(rerouteA)
				&& edge.toNodeId().equals(debug)
				&& edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)
				&& edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)
		));
		assertEquals(MCNGPortTypes.INT, session.effectivePortType(rerouteA, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT));
		assertEquals(MCNGPortTypes.INT, session.effectivePortType(rerouteB, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
	}

	@Test
	void editorCanFlipRerouteToAcceptInputOnRightSide() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("3.0"), new NodePosition(20, 20));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		assertTrue(session.toggleConnectionCandidate(source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertTrue(session.toggleConnectionCandidate(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT, true));
		assertEquals(1, session.edges().size());
		assertEquals(RerouteOrientation.RIGHT_TO_LEFT, session.rerouteOrientation(reroute));
	}

	@Test
	void editorCanFlipVerticalRerouteToAcceptInputOnBottomSide() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("3.0"), new NodePosition(20, 220));
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 80));

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		session.resizeReroute(reroute, new NodePosition(220, 80), new com.github.squi2rel.mcng.core.NodeSize(16, 92), RerouteOrientation.TOP_TO_BOTTOM);
		assertEquals(RerouteOrientation.TOP_TO_BOTTOM, session.rerouteOrientation(reroute));
		assertTrue(session.toggleConnectionCandidate(source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT));
		assertTrue(session.toggleConnectionCandidate(reroute, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT, NodeWidget.PortSide.BOTTOM));
		assertEquals(RerouteOrientation.BOTTOM_TO_TOP, session.rerouteOrientation(reroute));
	}

	@Test
	void shrinkingDynamicPortsRemovesInvalidEdges() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId sequence = builder.addNode(BuiltinNodeTypes.SEQUENCE, new BuiltinNodeTypes.SequenceConfig(4), new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(420, 20));
		builder.addEdge(trigger, "out", sequence, "in");
		builder.addEdge(sequence, "out4", debug, "in");
		builder.addEdge(trigger, "message", debug, "value");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		NodeInstance sequenceNode = session.node(sequence);
		assertEquals(4, session.nodeType(sequenceNode).outputs(sequenceNode).size());
		assertTrue(session.edges().stream().anyMatch(edge -> edge.fromNodeId().equals(sequence) && edge.fromPortId().equals(new PortId("out4"))));

		session.updateControlValue(sequence, "outputCount", new JsonPrimitive("2"));

		sequenceNode = session.node(sequence);
		assertEquals(2, session.nodeType(sequenceNode).outputs(sequenceNode).size());
		assertFalse(session.edges().stream().anyMatch(edge -> edge.fromNodeId().equals(sequence) && edge.fromPortId().equals(new PortId("out4"))));
	}

	@Test
	void executingNodeHighlightsConnectedRerouteChain() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", false, "plain"), new NodePosition(20, 20));
		NodeId rerouteA = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId rerouteB = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(520, 20));
		builder.addEdge(trigger, "out", rerouteA, "value");
		builder.addEdge(rerouteA, "value", rerouteB, "value");
		builder.addEdge(rerouteB, "value", debug, "in");
		builder.addEdge(trigger, "message", debug, "value");

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);

		session.triggerSelectedEvent();
		session.tickExecution(1);

		assertTrue(session.isExecuting(trigger));
		assertTrue(session.isExecuting(rerouteA));
		assertTrue(session.isExecuting(rerouteB));
	}

	@Test
	void compositeMoveProducesSingleUndoStep() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			rootDocument(),
			new TestHost()
		);

		NodeId left = new NodeId("left");
		NodePosition original = session.positions().get(left);
		session.beginCompositeEdit();
		session.moveNodes(Map.of(left, new NodePosition(80, 40)));
		session.moveNodes(Map.of(left, new NodePosition(140, 60)));
		session.endCompositeEdit();

		assertEquals(new NodePosition(140, 60), session.positions().get(left));
		assertTrue(session.canUndo());
		assertTrue(session.undo());
		assertEquals(original, session.positions().get(left));
		assertFalse(session.canUndo());
		assertTrue(session.canRedo());
		assertTrue(session.redo());
		assertEquals(new NodePosition(140, 60), session.positions().get(left));
	}

	@Test
	void undoRestoresDefinitionContextAndSelection() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			document(DocumentNodeDefinitionKind.SUBGRAPH, "subgraph"),
			new TestHost()
		);

		assertTrue(session.enterDefinition(new NodeId("subgraphNode")));
		session.addNode(BuiltinNodeTypes.STRING_CONSTANT.id(), 120, 140);
		NodeId createdNodeId = session.selectedNodeId().orElseThrow();
		assertTrue(session.exitToBreadcrumb(null));
		assertFalse(session.isInsideDefinition());

		assertTrue(session.undo());
		assertTrue(session.isInsideDefinition());
		assertEquals("subgraph", session.currentDefinitionId());
		assertEquals(new NodeId("in"), session.selectedNodeId().orElseThrow());
		assertFalse(session.nodes().stream().anyMatch(node -> node.id().equals(createdNodeId)));

		assertTrue(session.redo());
		assertTrue(session.isInsideDefinition());
		assertEquals("subgraph", session.currentDefinitionId());
		assertEquals(createdNodeId, session.selectedNodeId().orElseThrow());
	}

	@Test
	void replaceDocumentCanBeUndoneAndRedone() {
		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			rootDocument(),
			new TestHost()
		);

		GraphDocument replacement = GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()));
		assertTrue(session.replaceDocument(replacement));
		assertTrue(session.nodes().isEmpty());

		assertTrue(session.undo());
		assertEquals(3, session.nodes().size());
		assertTrue(session.redo());
		assertTrue(session.nodes().isEmpty());
	}

	private GraphDocument document(DocumentNodeDefinitionKind kind, String definitionId) {
		String inputTypeId = kind == DocumentNodeDefinitionKind.SUBGRAPH
			? DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID
			: DocumentNodeTypes.GRAPH_INPUT_TYPE_ID;
		String outputTypeId = kind == DocumentNodeDefinitionKind.SUBGRAPH
			? DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID
			: DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID;
		GraphScope scope = GraphScope.of(
			new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("in"), inputTypeId, portConfig("In")),
					new NodeInstance(new NodeId("identity"), BuiltinNodeTypes.IDENTITY.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE)),
					new NodeInstance(new NodeId("out"), outputTypeId, portConfig("Out"))
				),
				List.of(
					new EdgeDefinition(new NodeId("in"), new PortId("value"), new NodeId("identity"), new PortId("value")),
					new EdgeDefinition(new NodeId("identity"), new PortId("value"), new NodeId("out"), new PortId("value"))
				)
			),
			new GraphLayout(Map.of(
				new NodeId("in"), new NodePosition(20, 20),
				new NodeId("identity"), new NodePosition(180, 20),
				new NodeId("out"), new NodePosition(340, 20)
			))
		);

		NodeId rootNodeId = new NodeId(definitionId + "Node");
		GraphDefinition rootGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("source"), BuiltinNodeTypes.NUMERIC_CONSTANT.id(), BuiltinNodeTypes.NUMERIC_CONSTANT.configCodec().toJson(new BuiltinNodeTypes.NumericConstantConfig("4.0"))),
				new NodeInstance(new NodeId("debug"), BuiltinNodeTypes.DEBUG_OUTPUT.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE)),
				new NodeInstance(
					rootNodeId,
					kind == DocumentNodeDefinitionKind.SUBGRAPH
						? DocumentNodeTypes.subgraphTypeId(definitionId)
						: DocumentNodeTypes.definitionTypeId(definitionId),
					new JsonObject()
				)
			),
			List.of(
				new EdgeDefinition(new NodeId("source"), new PortId("value"), new NodeId("debug"), new PortId("value")),
				new EdgeDefinition(new NodeId("debug"), new PortId("value"), rootNodeId, new PortId("in"))
			)
		);
		GraphLayout rootLayout = new GraphLayout(Map.of(
			new NodeId("source"), new NodePosition(20, 20),
			new NodeId("debug"), new NodePosition(220, 20),
			rootNodeId, new NodePosition(420, 20)
		));
		if (kind == DocumentNodeDefinitionKind.SUBGRAPH) {
			return GraphDocument.of(new GraphScope(rootGraph, rootLayout, List.of(), List.of(
				new SubgraphDefinition(definitionId, "Definition", scope)
			)), List.of());
		}
		return GraphDocument.of(new GraphScope(rootGraph, rootLayout, List.of(), List.of()), List.of(
			new DocumentNodeDefinition(definitionId, "Definition", scope)
		));
	}

	private GraphDocument rootDocument() {
		GraphDefinition rootGraph = new GraphDefinition(
			List.of(
				new NodeInstance(new NodeId("left"), BuiltinNodeTypes.NUMERIC_CONSTANT.id(), BuiltinNodeTypes.NUMERIC_CONSTANT.configCodec().toJson(new BuiltinNodeTypes.NumericConstantConfig("2.0"))),
				new NodeInstance(new NodeId("right"), BuiltinNodeTypes.NUMERIC_CONSTANT.id(), BuiltinNodeTypes.NUMERIC_CONSTANT.configCodec().toJson(new BuiltinNodeTypes.NumericConstantConfig("3.0"))),
				new NodeInstance(new NodeId("add"), BuiltinNodeTypes.ADD.id(), BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE))
			),
			List.of(
				new EdgeDefinition(new NodeId("left"), new PortId("value"), new NodeId("add"), BuiltinNodeTypes.LEFT_PORT),
				new EdgeDefinition(new NodeId("right"), new PortId("value"), new NodeId("add"), BuiltinNodeTypes.RIGHT_PORT)
			)
		);
		return GraphDocument.of(rootGraph, new GraphLayout(Map.of(
			new NodeId("left"), new NodePosition(20, 20),
			new NodeId("right"), new NodePosition(20, 120),
			new NodeId("add"), new NodePosition(220, 70)
		)));
	}

	private GraphDocument customInlineNumericDocument(boolean connectLongSource) {
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

		GraphDefinition rootGraph;
		GraphLayout rootLayout;
		if (connectLongSource) {
			rootGraph = new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("source"), BuiltinNodeTypes.NUMERIC_CONSTANT.id(), BuiltinNodeTypes.NUMERIC_CONSTANT.configCodec().toJson(new BuiltinNodeTypes.NumericConstantConfig("2147483648"))),
					new NodeInstance(new NodeId("customNode"), DocumentNodeTypes.definitionTypeId("custom_inline"), new JsonObject())
				),
				List.of(
					new EdgeDefinition(new NodeId("source"), new PortId("value"), new NodeId("customNode"), new PortId("in"))
				)
			);
			rootLayout = new GraphLayout(Map.of(
				new NodeId("source"), new NodePosition(20, 20),
				new NodeId("customNode"), new NodePosition(220, 20)
			));
		} else {
			rootGraph = new GraphDefinition(
				List.of(
					new NodeInstance(new NodeId("customNode"), DocumentNodeTypes.definitionTypeId("custom_inline"), new JsonObject())
				),
				List.of()
			);
			rootLayout = new GraphLayout(Map.of(
				new NodeId("customNode"), new NodePosition(220, 20)
			));
		}

		return GraphDocument.of(
			rootGraph,
			rootLayout,
			List.of(new DocumentNodeDefinition("custom_inline", "Custom Inline", GraphScope.of(definitionGraph, definitionLayout)))
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

	private static NodeTypeRegistry registry() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeTypes.registerAll(registry);
		return registry;
	}

	private static PortTypeRegistry portTypes() {
		return MCNGPortTypes.createRegistry();
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
