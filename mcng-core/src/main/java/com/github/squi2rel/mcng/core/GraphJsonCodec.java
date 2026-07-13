package com.github.squi2rel.mcng.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GraphJsonCodec {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String toJson(GraphDocument document) {
		JsonObject root = new JsonObject();
		root.addProperty("version", document.version());
		root.add("rootScope", scopeToJson(document.rootScope()));

		JsonArray definitions = new JsonArray();
		for (DocumentNodeDefinition definition : document.definitions()) {
			JsonObject definitionJson = new JsonObject();
			definitionJson.addProperty("id", definition.id());
			definitionJson.addProperty("displayName", definition.displayName());
			definitionJson.add("scope", scopeToJson(definition.scope()));
			definitions.add(definitionJson);
		}
		root.add("definitions", definitions);

		return GSON.toJson(root);
	}

	public GraphDocument fromJson(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		int version = root.get("version").getAsInt();
		GraphScope rootScope = scopeFromJson(root.getAsJsonObject("rootScope"));
		List<DocumentNodeDefinition> definitions = new ArrayList<>();
		if (root.has("definitions") && root.get("definitions").isJsonArray()) {
			for (JsonElement definitionElement : root.getAsJsonArray("definitions")) {
				JsonObject definitionJson = definitionElement.getAsJsonObject();
				definitions.add(new DocumentNodeDefinition(
					definitionJson.get("id").getAsString(),
					definitionJson.get("displayName").getAsString(),
					scopeFromJson(definitionJson.getAsJsonObject("scope"))
				));
			}
		}
		return new GraphDocument(version, rootScope, definitions);
	}

	private JsonObject scopeToJson(GraphScope scope) {
		JsonObject scopeJson = new JsonObject();
		scopeJson.add("graph", graphToJson(scope.graph()));
		scopeJson.add("layout", layoutToJson(scope.layout()));
		scopeJson.add("variables", variablesToJson(scope.variables()));
		JsonArray subgraphs = new JsonArray();
		for (SubgraphDefinition subgraph : scope.subgraphs()) {
			JsonObject subgraphJson = new JsonObject();
			subgraphJson.addProperty("id", subgraph.id());
			subgraphJson.addProperty("displayName", subgraph.displayName());
			subgraphJson.add("scope", scopeToJson(subgraph.scope()));
			subgraphs.add(subgraphJson);
		}
		scopeJson.add("subgraphs", subgraphs);
		return scopeJson;
	}

	private GraphScope scopeFromJson(JsonObject scopeJson) {
		GraphDefinition graph = graphFromJson(scopeJson.getAsJsonObject("graph"));
		GraphLayout layout = layoutFromJson(scopeJson.has("layout") ? scopeJson.getAsJsonObject("layout") : new JsonObject());
		List<GraphVariableDefinition> variables = variablesFromJson(scopeJson.has("variables") ? scopeJson.getAsJsonArray("variables") : new JsonArray());
		List<SubgraphDefinition> subgraphs = new ArrayList<>();
		if (scopeJson.has("subgraphs") && scopeJson.get("subgraphs").isJsonArray()) {
			for (JsonElement subgraphElement : scopeJson.getAsJsonArray("subgraphs")) {
				JsonObject subgraphJson = subgraphElement.getAsJsonObject();
				subgraphs.add(new SubgraphDefinition(
					subgraphJson.get("id").getAsString(),
					subgraphJson.get("displayName").getAsString(),
					scopeFromJson(subgraphJson.getAsJsonObject("scope"))
				));
			}
		}
		return new GraphScope(graph, layout, variables, subgraphs);
	}

	private JsonArray variablesToJson(List<GraphVariableDefinition> variables) {
		JsonArray array = new JsonArray();
		for (GraphVariableDefinition variable : variables) {
			JsonObject variableJson = new JsonObject();
			variableJson.addProperty("id", variable.id());
			variableJson.addProperty("displayName", variable.displayName());
			variableJson.addProperty("typeId", variable.typeId());
			variableJson.add("defaultValue", variable.defaultValue().deepCopy());
			array.add(variableJson);
		}
		return array;
	}

	private List<GraphVariableDefinition> variablesFromJson(JsonArray array) {
		List<GraphVariableDefinition> variables = new ArrayList<>();
		for (JsonElement variableElement : array) {
			JsonObject variableJson = variableElement.getAsJsonObject();
			variables.add(new GraphVariableDefinition(
				variableJson.get("id").getAsString(),
				variableJson.get("displayName").getAsString(),
				variableJson.get("typeId").getAsString(),
				variableJson.has("defaultValue") ? variableJson.get("defaultValue") : null
			));
		}
		return variables;
	}

	private JsonObject graphToJson(GraphDefinition graph) {
		JsonObject graphJson = new JsonObject();
		JsonArray nodes = new JsonArray();
		for (NodeInstance node : graph.nodes()) {
			JsonObject nodeJson = new JsonObject();
			nodeJson.addProperty("id", node.id().value());
			nodeJson.addProperty("type", node.typeId());
			nodeJson.add("config", node.config().deepCopy());
			nodes.add(nodeJson);
		}
		graphJson.add("nodes", nodes);

		JsonArray edges = new JsonArray();
		for (EdgeDefinition edge : graph.edges()) {
			JsonObject edgeJson = new JsonObject();
			edgeJson.addProperty("fromNode", edge.fromNodeId().value());
			edgeJson.addProperty("fromPort", edge.fromPortId().value());
			edgeJson.addProperty("toNode", edge.toNodeId().value());
			edgeJson.addProperty("toPort", edge.toPortId().value());
			edges.add(edgeJson);
		}
		graphJson.add("edges", edges);
		return graphJson;
	}

	private GraphDefinition graphFromJson(JsonObject graphJson) {
		List<NodeInstance> nodes = new ArrayList<>();
		for (JsonElement nodeElement : graphJson.getAsJsonArray("nodes")) {
			JsonObject nodeJson = nodeElement.getAsJsonObject();
			JsonObject config = nodeJson.has("config") && nodeJson.get("config").isJsonObject()
				? nodeJson.getAsJsonObject("config").deepCopy()
				: new JsonObject();
			nodes.add(new NodeInstance(
				new NodeId(nodeJson.get("id").getAsString()),
				nodeJson.get("type").getAsString(),
				config
			));
		}

		List<EdgeDefinition> edges = new ArrayList<>();
		for (JsonElement edgeElement : graphJson.getAsJsonArray("edges")) {
			JsonObject edgeJson = edgeElement.getAsJsonObject();
			edges.add(new EdgeDefinition(
				new NodeId(edgeJson.get("fromNode").getAsString()),
				new PortId(edgeJson.get("fromPort").getAsString()),
				new NodeId(edgeJson.get("toNode").getAsString()),
				new PortId(edgeJson.get("toPort").getAsString())
			));
		}
		return new GraphDefinition(nodes, edges);
	}

	private JsonObject layoutToJson(GraphLayout layout) {
		JsonObject layoutJson = new JsonObject();
		JsonObject positions = new JsonObject();
		for (Map.Entry<NodeId, NodePosition> entry : layout.nodePositions().entrySet()) {
			JsonObject position = new JsonObject();
			position.addProperty("x", entry.getValue().x());
			position.addProperty("y", entry.getValue().y());
			positions.add(entry.getKey().value(), position);
		}
		layoutJson.add("nodePositions", positions);
		JsonObject sizes = new JsonObject();
		for (Map.Entry<NodeId, NodeSize> entry : layout.nodeSizes().entrySet()) {
			JsonObject size = new JsonObject();
			size.addProperty("width", entry.getValue().width());
			size.addProperty("height", entry.getValue().height());
			sizes.add(entry.getKey().value(), size);
		}
		if (!sizes.isEmpty()) {
			layoutJson.add("nodeSizes", sizes);
		}
		return layoutJson;
	}

	private GraphLayout layoutFromJson(JsonObject layoutJson) {
		Map<NodeId, NodePosition> positions = new LinkedHashMap<>();
		JsonObject positionJson = layoutJson.has("nodePositions") ? layoutJson.getAsJsonObject("nodePositions") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : positionJson.entrySet()) {
			JsonObject value = entry.getValue().getAsJsonObject();
			positions.put(new NodeId(entry.getKey()), new NodePosition(value.get("x").getAsDouble(), value.get("y").getAsDouble()));
		}
		Map<NodeId, NodeSize> sizes = new LinkedHashMap<>();
		JsonObject sizeJson = layoutJson.has("nodeSizes") ? layoutJson.getAsJsonObject("nodeSizes") : new JsonObject();
		for (Map.Entry<String, JsonElement> entry : sizeJson.entrySet()) {
			JsonObject value = entry.getValue().getAsJsonObject();
			sizes.put(new NodeId(entry.getKey()), new NodeSize(value.get("width").getAsInt(), value.get("height").getAsInt()));
		}
		return new GraphLayout(positions, sizes);
	}
}
