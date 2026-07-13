package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class DocumentGraphFlattener {
	private static final String FLAT_NODE_PREFIX = "$flat$";
	private static final String GLOBAL_SLOT_PREFIX = "$global$";
	private static final String LOCAL_SLOT_PREFIX = "$local$";

	private DocumentGraphFlattener() {
	}

	static FlattenedDocument flatten(GraphDocument document) {
		Objects.requireNonNull(document, "document");

		List<GraphError> errors = new ArrayList<>();
		VariableTables variables = collectVariables(document, errors);

		FlattenScopeResult root = flattenScope(
			document.rootScope(),
			ScopeBoundaryKind.ROOT,
			List.of(),
			null,
			variables,
			errors
		);

		Map<String, FlattenedDefinition> definitions = new LinkedHashMap<>();
		for (DocumentNodeDefinition definition : document.definitions()) {
			FlattenScopeResult flattened = flattenScope(
				definition.scope(),
				ScopeBoundaryKind.CUSTOM,
				List.of(),
				definition.id(),
				variables,
				errors
			);
			definitions.put(definition.id(), new FlattenedDefinition(
				new GraphDefinition(flattened.nodes(), flattened.edges()),
				copyScopePaths(flattened.scopePaths())
			));
		}

		return new FlattenedDocument(
			new GraphDefinition(root.nodes(), root.edges()),
			copyScopePaths(root.scopePaths()),
			Map.copyOf(definitions),
			List.copyOf(variables.slots()),
			List.copyOf(errors)
		);
	}

	private static VariableTables collectVariables(GraphDocument document, List<GraphError> errors) {
		Map<List<String>, Map<String, VariableSlot>> globalScopes = new LinkedHashMap<>();
		Map<String, Map<List<String>, Map<String, VariableSlot>>> localScopes = new LinkedHashMap<>();
		List<VariableSlot> slots = new ArrayList<>();

		collectGlobalVariables(document.rootScope(), List.of(), globalScopes, slots, errors);
		for (DocumentNodeDefinition definition : document.definitions()) {
			Map<List<String>, Map<String, VariableSlot>> definitionScopes = new LinkedHashMap<>();
			collectLocalVariables(definition.id(), definition.scope(), List.of(), definitionScopes, slots, errors);
			localScopes.put(definition.id(), definitionScopes);
		}
		return new VariableTables(globalScopes, localScopes, slots);
	}

	private static void collectGlobalVariables(
		GraphScope scope,
		List<String> path,
		Map<List<String>, Map<String, VariableSlot>> scopes,
		List<VariableSlot> slots,
		List<GraphError> errors
	) {
		Map<String, VariableSlot> locals = new LinkedHashMap<>();
		for (GraphVariableDefinition variable : scope.variables()) {
			VariableSlot slot = new VariableSlot(
				new GraphVariableDefinition(globalSlotId(path, variable.id()), variable.displayName(), variable.typeId(), variable.defaultValue()),
				VariableStorage.GLOBAL
			);
			if (locals.putIfAbsent(variable.id(), slot) != null) {
				errors.add(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, "Duplicate variable id: " + variable.id(), null, null));
				continue;
			}
			slots.add(slot);
		}
		scopes.put(List.copyOf(path), Map.copyOf(locals));
		for (SubgraphDefinition subgraph : scope.subgraphs()) {
			collectGlobalVariables(subgraph.scope(), append(path, subgraph.id()), scopes, slots, errors);
		}
	}

	private static void collectLocalVariables(
		String definitionId,
		GraphScope scope,
		List<String> path,
		Map<List<String>, Map<String, VariableSlot>> scopes,
		List<VariableSlot> slots,
		List<GraphError> errors
	) {
		Map<String, VariableSlot> locals = new LinkedHashMap<>();
		for (GraphVariableDefinition variable : scope.variables()) {
			VariableSlot slot = new VariableSlot(
				new GraphVariableDefinition(localSlotId(definitionId, path, variable.id()), variable.displayName(), variable.typeId(), variable.defaultValue()),
				VariableStorage.FRAME_LOCAL
			);
			if (locals.putIfAbsent(variable.id(), slot) != null) {
				errors.add(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, "Duplicate variable id: " + variable.id(), null, null));
				continue;
			}
			slots.add(slot);
		}
		scopes.put(List.copyOf(path), Map.copyOf(locals));
		for (SubgraphDefinition subgraph : scope.subgraphs()) {
			collectLocalVariables(definitionId, subgraph.scope(), append(path, subgraph.id()), scopes, slots, errors);
		}
	}

	private static FlattenScopeResult flattenScope(
		GraphScope scope,
		ScopeBoundaryKind boundaryKind,
		List<String> scopePath,
		String definitionId,
		VariableTables variables,
		List<GraphError> errors
	) {
		Map<NodeId, NodeInstance> flatNodesByOriginal = new LinkedHashMap<>();
		Map<NodeId, List<String>> scopePaths = new LinkedHashMap<>();
		Map<NodeId, FlattenScopeResult> childScopes = new LinkedHashMap<>();
		Map<String, SubgraphDefinition> subgraphsById = scope.subgraphs().stream()
			.collect(LinkedHashMap::new, (map, subgraph) -> map.put(subgraph.id(), subgraph), Map::putAll);

		for (NodeInstance node : scope.graph().nodes()) {
			if (DocumentNodeTypes.isSubgraphType(node.typeId())) {
				SubgraphDefinition subgraph = subgraphsById.get(DocumentNodeTypes.subgraphIdFromTypeId(node.typeId()));
				if (subgraph == null) {
					errors.add(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, "Unknown subgraph: " + node.typeId(), node.id(), null));
					continue;
				}
				childScopes.put(node.id(), flattenScope(
					subgraph.scope(),
					ScopeBoundaryKind.SUBGRAPH,
					append(scopePath, subgraph.id()),
					definitionId,
					variables,
					errors
				));
				continue;
			}

			NodeId flatNodeId = flattenNodeId(scopePath, node.id());
			NodeInstance flattened = rewriteNode(node, flatNodeId, definitionId, scopePath, variables);
			flatNodesByOriginal.put(node.id(), flattened);
			scopePaths.put(flatNodeId, List.copyOf(scopePath));
		}

		List<NodeInstance> nodes = new ArrayList<>(flatNodesByOriginal.values());
		List<EdgeDefinition> edges = new ArrayList<>();
		for (FlattenScopeResult child : childScopes.values()) {
			for (NodeInstance node : child.nodes()) {
				if (!child.boundaryNodeIds().contains(node.id())) {
					nodes.add(node);
				}
			}
			for (EdgeDefinition edge : child.edges()) {
				if (!child.boundaryNodeIds().contains(edge.fromNodeId()) && !child.boundaryNodeIds().contains(edge.toNodeId())) {
					edges.add(edge);
				}
			}
			child.scopePaths().forEach((nodeId, path) -> {
				if (!child.boundaryNodeIds().contains(nodeId)) {
					scopePaths.put(nodeId, path);
				}
			});
		}

		for (EdgeDefinition edge : scope.graph().edges()) {
			List<FlatPortRef> sources = expandedSource(edge.fromNodeId(), edge.fromPortId(), flatNodesByOriginal, childScopes);
			List<FlatPortRef> targets = expandedTarget(edge.toNodeId(), edge.toPortId(), flatNodesByOriginal, childScopes);
			for (FlatPortRef source : sources) {
				for (FlatPortRef target : targets) {
					edges.add(new EdgeDefinition(source.nodeId(), source.portId(), target.nodeId(), target.portId()));
				}
			}
		}

		Map<PortId, List<FlatPortRef>> inputTargets = new LinkedHashMap<>();
		Map<PortId, List<FlatPortRef>> outputSources = new LinkedHashMap<>();
		Set<NodeId> boundaryNodeIds = new LinkedHashSet<>();
		for (NodeInstance node : scope.graph().nodes()) {
			if (!boundaryKind.matches(node.typeId())) {
				continue;
			}
			NodeInstance flatNode = flatNodesByOriginal.get(node.id());
			if (flatNode == null) {
				continue;
			}
			boundaryNodeIds.add(flatNode.id());
			PortId boundaryPortId = new PortId(node.id().value());
			if (boundaryKind.isInput(node.typeId())) {
				inputTargets.put(
					boundaryPortId,
					edges.stream()
						.filter(edge -> edge.fromNodeId().equals(flatNode.id()))
						.map(edge -> new FlatPortRef(edge.toNodeId(), edge.toPortId()))
						.toList()
				);
			}
			if (boundaryKind.isOutput(node.typeId())) {
				outputSources.put(
					boundaryPortId,
					edges.stream()
						.filter(edge -> edge.toNodeId().equals(flatNode.id()))
						.map(edge -> new FlatPortRef(edge.fromNodeId(), edge.fromPortId()))
						.toList()
				);
			}
		}

		return new FlattenScopeResult(
			List.copyOf(nodes),
			List.copyOf(edges),
			copyScopePaths(scopePaths),
			Set.copyOf(boundaryNodeIds),
			Map.copyOf(inputTargets),
			Map.copyOf(outputSources)
		);
	}

	private static List<FlatPortRef> expandedSource(
		NodeId originalNodeId,
		PortId originalPortId,
		Map<NodeId, NodeInstance> flatNodesByOriginal,
		Map<NodeId, FlattenScopeResult> childScopes
	) {
		FlattenScopeResult child = childScopes.get(originalNodeId);
		if (child != null) {
			return child.outputSources().getOrDefault(originalPortId, List.of());
		}
		NodeInstance node = flatNodesByOriginal.get(originalNodeId);
		if (node == null) {
			return List.of();
		}
		return List.of(new FlatPortRef(node.id(), originalPortId));
	}

	private static List<FlatPortRef> expandedTarget(
		NodeId originalNodeId,
		PortId originalPortId,
		Map<NodeId, NodeInstance> flatNodesByOriginal,
		Map<NodeId, FlattenScopeResult> childScopes
	) {
		FlattenScopeResult child = childScopes.get(originalNodeId);
		if (child != null) {
			return child.inputTargets().getOrDefault(originalPortId, List.of());
		}
		NodeInstance node = flatNodesByOriginal.get(originalNodeId);
		if (node == null) {
			return List.of();
		}
		return List.of(new FlatPortRef(node.id(), originalPortId));
	}

	private static NodeInstance rewriteNode(
		NodeInstance source,
		NodeId flatNodeId,
		String definitionId,
		List<String> scopePath,
		VariableTables variables
	) {
		JsonObject config = source.config().deepCopy();
		if (BuiltinNodeTypes.GET_VARIABLE.id().equals(source.typeId()) || BuiltinNodeTypes.SET_VARIABLE.id().equals(source.typeId())) {
			String variableId = config.has("variableId") ? config.get("variableId").getAsString().trim() : "";
			String slotId = variables.resolve(definitionId, scopePath, variableId);
			if (slotId != null) {
				config.addProperty("variableId", slotId);
			}
		}
		return new NodeInstance(flatNodeId, source.typeId(), config);
	}

	private static NodeId flattenNodeId(List<String> scopePath, NodeId original) {
		if (scopePath.isEmpty()) {
			return original;
		}
		return new NodeId(FLAT_NODE_PREFIX + String.join("/", scopePath) + "/" + original.value());
	}

	private static String globalSlotId(List<String> path, String variableId) {
		return path.isEmpty()
			? GLOBAL_SLOT_PREFIX + variableId
			: GLOBAL_SLOT_PREFIX + String.join("/", path) + "::" + variableId;
	}

	private static String localSlotId(String definitionId, List<String> path, String variableId) {
		return path.isEmpty()
			? LOCAL_SLOT_PREFIX + definitionId + "::" + variableId
			: LOCAL_SLOT_PREFIX + definitionId + "/" + String.join("/", path) + "::" + variableId;
	}

	private static List<String> append(List<String> path, String id) {
		List<String> result = new ArrayList<>(path);
		result.add(id);
		return List.copyOf(result);
	}

	private static Map<NodeId, List<String>> copyScopePaths(Map<NodeId, List<String>> source) {
		Map<NodeId, List<String>> copy = new LinkedHashMap<>();
		source.forEach((nodeId, path) -> copy.put(nodeId, List.copyOf(path)));
		return Map.copyOf(copy);
	}

	record FlattenedDocument(
		GraphDefinition rootGraph,
		Map<NodeId, List<String>> rootScopePaths,
		Map<String, FlattenedDefinition> definitions,
		List<VariableSlot> variables,
		List<GraphError> errors
	) {
	}

	record FlattenedDefinition(GraphDefinition graph, Map<NodeId, List<String>> scopePaths) {
	}

	record VariableSlot(GraphVariableDefinition definition, VariableStorage storage) {
	}

	enum VariableStorage {
		GLOBAL,
		FRAME_LOCAL
	}

	private record FlatPortRef(NodeId nodeId, PortId portId) {
	}

	private record FlattenScopeResult(
		List<NodeInstance> nodes,
		List<EdgeDefinition> edges,
		Map<NodeId, List<String>> scopePaths,
		Set<NodeId> boundaryNodeIds,
		Map<PortId, List<FlatPortRef>> inputTargets,
		Map<PortId, List<FlatPortRef>> outputSources
	) {
	}

	private record VariableTables(
		Map<List<String>, Map<String, VariableSlot>> globalScopes,
		Map<String, Map<List<String>, Map<String, VariableSlot>>> localScopes,
		List<VariableSlot> slots
	) {
		private String resolve(String definitionId, List<String> scopePath, String variableId) {
			if (definitionId != null) {
				Map<List<String>, Map<String, VariableSlot>> locals = localScopes.getOrDefault(definitionId, Map.of());
				for (int depth = scopePath.size(); depth >= 0; depth--) {
					VariableSlot slot = locals.getOrDefault(scopePath.subList(0, depth), Map.of()).get(variableId);
					if (slot != null) {
						return slot.definition().id();
					}
				}
				VariableSlot globalRoot = globalScopes.getOrDefault(List.of(), Map.of()).get(variableId);
				return globalRoot != null ? globalRoot.definition().id() : null;
			}

			for (int depth = scopePath.size(); depth >= 0; depth--) {
				VariableSlot slot = globalScopes.getOrDefault(scopePath.subList(0, depth), Map.of()).get(variableId);
				if (slot != null) {
					return slot.definition().id();
				}
			}
			return null;
		}
	}

	private enum ScopeBoundaryKind {
		ROOT,
		CUSTOM,
		SUBGRAPH;

		private boolean matches(String typeId) {
			return switch (this) {
				case ROOT -> false;
				case CUSTOM -> DocumentNodeTypes.GRAPH_INPUT_TYPE_ID.equals(typeId)
					|| DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID.equals(typeId)
					|| DocumentNodeTypes.FLOW_INPUT_TYPE_ID.equals(typeId)
					|| DocumentNodeTypes.FLOW_OUTPUT_TYPE_ID.equals(typeId);
				case SUBGRAPH -> DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID.equals(typeId)
					|| DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID.equals(typeId)
					|| DocumentNodeTypes.SUBGRAPH_FLOW_INPUT_TYPE_ID.equals(typeId)
					|| DocumentNodeTypes.SUBGRAPH_FLOW_OUTPUT_TYPE_ID.equals(typeId);
			};
		}

		private boolean isInput(String typeId) {
			return switch (this) {
				case ROOT -> false;
				case CUSTOM -> DocumentNodeTypes.GRAPH_INPUT_TYPE_ID.equals(typeId) || DocumentNodeTypes.FLOW_INPUT_TYPE_ID.equals(typeId);
				case SUBGRAPH -> DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID.equals(typeId) || DocumentNodeTypes.SUBGRAPH_FLOW_INPUT_TYPE_ID.equals(typeId);
			};
		}

		private boolean isOutput(String typeId) {
			return switch (this) {
				case ROOT -> false;
				case CUSTOM -> DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID.equals(typeId) || DocumentNodeTypes.FLOW_OUTPUT_TYPE_ID.equals(typeId);
				case SUBGRAPH -> DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID.equals(typeId) || DocumentNodeTypes.SUBGRAPH_FLOW_OUTPUT_TYPE_ID.equals(typeId);
			};
		}
	}
}
