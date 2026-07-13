package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.ManualTriggerConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphJsonCodecTest {
	@Test
	void roundTripsGraphDocument() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		BuiltinNodeTypes.registerAll(registry);

		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId constant = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("42.5"), new NodePosition(120, 80));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 80));
		builder.setNodeSize(debug, new NodeSize(280, 160));
		builder.addEdge(constant, "value", debug, "value");

		GraphDocument original = builder.buildDocument();
		GraphJsonCodec codec = new GraphJsonCodec();
		GraphDocument decoded = codec.fromJson(codec.toJson(original));

		assertEquals(original.version(), decoded.version());
		assertEquals(original.graph().nodes().size(), decoded.graph().nodes().size());
		assertEquals(original.graph().edges(), decoded.graph().edges());
		assertEquals(original.layout().nodePositions(), decoded.layout().nodePositions());
		assertEquals(original.layout().nodeSizes(), decoded.layout().nodeSizes());
		assertEquals(
			original.graph().nodes().getFirst().config().get("value").getAsDouble(),
			decoded.graph().nodes().getFirst().config().get("value").getAsDouble()
		);
	}

	@Test
	void loadsLayoutsWithoutNodeSizes() {
		GraphJsonCodec codec = new GraphJsonCodec();
		GraphDocument decoded = codec.fromJson("""
			{
			  "version": 1,
			  "rootScope": {
			    "graph": {
			      "nodes": [],
			      "edges": []
			    },
			    "layout": {
			      "nodePositions": {}
			    },
			    "variables": [],
			    "subgraphs": []
			  },
			  "definitions": []
			}
			""");

		assertEquals(Map.of(), decoded.layout().nodePositions());
		assertEquals(Map.of(), decoded.layout().nodeSizes());
	}

	@Test
	void preservesInlineInputsAndExpandedNodeConfig() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		BuiltinNodeTypes.registerAll(registry);

		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(120, 80));
		NodeId trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", true, "boxed"), new NodePosition(320, 80));
		builder.setInlineInput(add, "left", NodeConfigValues.numberValue(4.0));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(5.0));

		GraphDocument original = builder.buildDocument();
		GraphJsonCodec codec = new GraphJsonCodec();
		GraphDocument decoded = codec.fromJson(codec.toJson(original));

		assertEquals(4.0, decoded.graph().nodes().stream().filter(node -> node.id().equals(add)).findFirst().orElseThrow().config()
			.getAsJsonObject(NodeConfigValues.INLINE_INPUTS_KEY).get("left").getAsDouble());
		assertEquals("boxed", decoded.graph().nodes().stream().filter(node -> node.id().equals(trigger)).findFirst().orElseThrow().config().get("style").getAsString());
		assertEquals(true, decoded.graph().nodes().stream().filter(node -> node.id().equals(trigger)).findFirst().orElseThrow().config().get("uppercase").getAsBoolean());
	}

	@Test
	void preservesImagePreviewConfig() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		BuiltinNodeTypes.registerAll(registry);

		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId preview = builder.addNode(BuiltinNodeTypes.IMAGE_PREVIEW, new BuiltinNodeTypes.ImagePreviewConfig("/tmp/demo.png"), new NodePosition(120, 80));
		builder.setNodeSize(preview, new NodeSize(240, 180));

		GraphDocument original = builder.buildDocument();
		GraphJsonCodec codec = new GraphJsonCodec();
		GraphDocument decoded = codec.fromJson(codec.toJson(original));

		assertEquals(
			"/tmp/demo.png",
			decoded.graph().nodes().stream().filter(node -> node.id().equals(preview)).findFirst().orElseThrow().config().get("filePath").getAsString()
		);
		assertEquals(new NodeSize(240, 180), decoded.layout().nodeSizes().get(preview));
	}

	@Test
	void preservesRerouteOrientationConfigAndSize() {
		NodeId reroute = new NodeId("reroute");
		JsonObject config = BuiltinNodeTypes.EmptyConfig.CODEC.toJson(BuiltinNodeTypes.EmptyConfig.INSTANCE);
		config.addProperty("__rerouteOrientation", "top_to_bottom");

		GraphDocument original = GraphDocument.of(
			new GraphDefinition(
				List.of(new NodeInstance(reroute, BuiltinNodeTypes.REROUTE.id(), config)),
				List.of()
			),
			new GraphLayout(Map.of(reroute, new NodePosition(120, 80)), Map.of(reroute, new NodeSize(16, 92)))
		);
		GraphJsonCodec codec = new GraphJsonCodec();
		GraphDocument decoded = codec.fromJson(codec.toJson(original));

		assertEquals("top_to_bottom", decoded.graph().nodes().getFirst().config().get("__rerouteOrientation").getAsString());
		assertEquals(new NodeSize(16, 92), decoded.layout().nodeSizes().get(reroute));
	}

	@Test
	void roundTripsDocumentDefinitions() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		BuiltinNodeTypes.registerAll(registry);

		DocumentNodeDefinition definition = new DocumentNodeDefinition(
			"custom_add",
			"Custom Add",
			DocumentNodeDefinitionKind.CUSTOM_NODE,
			new GraphDefinition(
				List.of(new NodeInstance(new NodeId("in"), DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, portConfig("Left"))),
				List.of()
			),
			new GraphLayout(Map.of(new NodeId("in"), new NodePosition(10, 20)))
		);

		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		GraphDocument original = GraphDocument.of(builder.buildGraph(), new GraphLayout(Map.of()), List.of(definition));
		GraphJsonCodec codec = new GraphJsonCodec();
		GraphDocument decoded = codec.fromJson(codec.toJson(original));

		assertEquals(1, decoded.definitions().size());
		assertEquals("custom_add", decoded.definitions().getFirst().id());
		assertEquals("Custom Add", decoded.definitions().getFirst().displayName());
		assertEquals(DocumentNodeDefinitionKind.CUSTOM_NODE, decoded.definitions().getFirst().kind());
		assertEquals(DocumentNodeTypes.GRAPH_INPUT_TYPE_ID, decoded.definitions().getFirst().graph().nodes().getFirst().typeId());
		assertEquals("Left", decoded.definitions().getFirst().graph().nodes().getFirst().config().get("name").getAsString());
	}

	private static com.google.gson.JsonObject portConfig(String name) {
		com.google.gson.JsonObject json = new com.google.gson.JsonObject();
		json.addProperty("name", name);
		return json;
	}
}
