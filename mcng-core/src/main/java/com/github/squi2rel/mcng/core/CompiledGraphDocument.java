package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CompiledGraphDocument {
	private final GraphDocument document;
	private final PortTypeRegistry portTypes;
	private final GraphExecutionOptions options;
	private final NodeTypeRegistry resolvedRegistry;
	private final CompiledGraphPlan rootPlan;
	private final Map<String, DefinitionPlan> definitions;
	private final Map<String, VariableBinding> variableBindings;
	private final List<GraphError> compileErrors;

	private CompiledGraphDocument(
		GraphDocument document,
		PortTypeRegistry portTypes,
		GraphExecutionOptions options,
		NodeTypeRegistry resolvedRegistry,
		CompiledGraphPlan rootPlan,
		Map<String, DefinitionPlan> definitions,
		Map<String, VariableBinding> variableBindings,
		List<GraphError> compileErrors
	) {
		this.document = document;
		this.portTypes = portTypes;
		this.options = options;
		this.resolvedRegistry = resolvedRegistry;
		this.rootPlan = rootPlan;
		this.definitions = Map.copyOf(definitions);
		this.variableBindings = Map.copyOf(variableBindings);
		this.compileErrors = List.copyOf(compileErrors);
	}

	public static CompiledGraphDocument compile(NodeTypeRegistry baseRegistry, PortTypeRegistry portTypes, GraphDocument document) {
		return compile(baseRegistry, portTypes, document, GraphExecutionOptions.DEFAULT);
	}

	public static CompiledGraphDocument compile(NodeTypeRegistry baseRegistry, PortTypeRegistry portTypes, GraphDocument document, GraphExecutionOptions options) {
		Objects.requireNonNull(baseRegistry, "baseRegistry");
		Objects.requireNonNull(portTypes, "portTypes");
		Objects.requireNonNull(document, "document");
		Objects.requireNonNull(options, "options");

		NodeTypeRegistry resolvedRegistry = DocumentNodeTypes.createResolvedRegistry(baseRegistry, portTypes, document);
		DocumentGraphFlattener.FlattenedDocument flattened = DocumentGraphFlattener.flatten(document);
		Map<String, VariableBinding> variableBindings = new LinkedHashMap<>();
		for (DocumentGraphFlattener.VariableSlot slot : flattened.variables()) {
			PortType<?> type = portTypes.findType(slot.definition().typeId()).orElse(null);
			if (type != null) {
				variableBindings.put(slot.definition().id(), new VariableBinding(slot.definition(), type, slot.storage()));
			}
		}
		Function<String, PortType<?>> variableTypeResolver = variableId -> {
			VariableBinding binding = variableBindings.get(variableId);
			return binding != null ? binding.type() : null;
		};

		Map<String, DocumentNodeDefinition> definitionsById = document.definitions().stream()
			.collect(Collectors.toMap(DocumentNodeDefinition::id, definition -> definition, (left, right) -> left, LinkedHashMap::new));
		Map<String, SubgraphDefinition> subgraphsById = new LinkedHashMap<>();
		collectSubgraphs(document.rootScope(), subgraphsById);
		for (DocumentNodeDefinition definition : document.definitions()) {
			collectSubgraphs(definition.scope(), subgraphsById);
		}
		Map<String, DefinitionPlan> definitionPlans = new LinkedHashMap<>();
		List<GraphError> compileErrors = new ArrayList<>(flattened.errors());

		for (DocumentNodeDefinition definition : document.definitions()) {
			DocumentNodeTypes.DefinitionRuntimeInfo info = DocumentNodeTypes.definitionRuntimeInfo(definition, definitionsById, subgraphsById, resolvedRegistry, portTypes);
			if (info.invalid()) {
				compileErrors.add(GraphError.of(
					GraphErrorCode.INVALID_DOCUMENT_DEFINITION,
					info.error(),
					null,
					null
				));
				continue;
			}

			DocumentGraphFlattener.FlattenedDefinition flattenedDefinition = flattened.definitions().get(definition.id());
			if (flattenedDefinition == null) {
				compileErrors.add(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, "Missing flattened definition: " + definition.id(), null, null));
				continue;
			}
			CompiledGraphPlan graphPlan = compileGraph(flattenedDefinition.graph(), resolvedRegistry, portTypes, variableTypeResolver, flattenedDefinition.scopePaths());
			compileErrors.addAll(graphPlan.errors());
			definitionPlans.put(definition.id(), new DefinitionPlan(info, graphPlan));
		}

		CompiledGraphPlan rootPlan = compileGraph(flattened.rootGraph(), resolvedRegistry, portTypes, variableTypeResolver, flattened.rootScopePaths());
		compileErrors.addAll(rootPlan.errors());

		return new CompiledGraphDocument(
			document,
			portTypes,
			options,
			resolvedRegistry,
			rootPlan,
			definitionPlans,
			variableBindings,
			compileErrors
		);
	}

	public NodeTypeRegistry resolvedRegistry() {
		return resolvedRegistry;
	}

	public PortTypeRegistry portTypes() {
		return portTypes;
	}

	public GraphExecutionOptions options() {
		return options;
	}

	public List<GraphVariableDefinition> variables() {
		return variableBindings.values().stream().map(VariableBinding::definition).toList();
	}

	public List<GraphError> compileErrors() {
		return compileErrors;
	}

	public boolean valid() {
		return compileErrors.isEmpty();
	}

	public List<String> scopePathForNode(NodeId nodeId) {
		return rootPlan.scopePath(nodeId);
	}

	public List<String> scopePathForDefinitionNode(String definitionId, NodeId nodeId) {
		DefinitionPlan definitionPlan = definitions.get(definitionId);
		return definitionPlan == null ? List.of() : definitionPlan.graphPlan().scopePath(nodeId);
	}

	public List<NodeId> rootEventSourceNodeIds() {
		return rootPlan.nodeTypes().entrySet().stream()
			.filter(entry -> entry.getValue().kind() == NodeKind.EVENT_SOURCE)
			.map(Map.Entry::getKey)
			.toList();
	}

	public GraphExecutionSession startExecution(NodeExecutionContext context) {
		return GraphExecutionSession.startDataflow(this, context);
	}

	public GraphExecutionSession startEventExecution(NodeId sourceNodeId, Object payload, NodeExecutionContext context) {
		return GraphExecutionSession.startEvent(this, sourceNodeId, payload, context);
	}

	GraphDocument document() {
		return document;
	}

	CompiledGraphPlan rootPlan() {
		return rootPlan;
	}

	Map<String, DefinitionPlan> definitions() {
		return definitions;
	}

	PortType<?> variableType(String id) {
		VariableBinding binding = variableBindings.get(id);
		return binding != null ? binding.type() : null;
	}

	DocumentGraphFlattener.VariableStorage variableStorage(String id) {
		VariableBinding binding = variableBindings.get(id);
		return binding != null ? binding.storage() : null;
	}

	GraphVariableDefinition variableDefinition(String id) {
		VariableBinding binding = variableBindings.get(id);
		return binding != null ? binding.definition() : null;
	}

	private static CompiledGraphPlan compileGraph(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		Function<String, PortType<?>> variableTypeResolver,
		Map<NodeId, List<String>> scopePaths
	) {
		NormalizedGraph normalized = normalizeGraph(graph, registry);
		graph = normalized.graph();
		Map<NodeId, NodeInstance> nodes = new LinkedHashMap<>();
		Map<NodeId, NodeType<?>> nodeTypes = new LinkedHashMap<>();
		List<GraphError> errors = new ArrayList<>(normalized.errors());

		for (NodeInstance node : graph.nodes()) {
			if (nodes.putIfAbsent(node.id(), node) != null) {
				errors.add(GraphError.of(GraphErrorCode.DUPLICATE_NODE_ID, "Duplicate node id: " + node.id(), node.id(), null));
				continue;
			}

			NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
			if (nodeType == null) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_NODE_TYPE, "Unknown node type: " + node.typeId(), node.id(), null));
				continue;
			}
			nodeTypes.put(node.id(), nodeType);
		}

		Map<NodeId, List<EdgeDefinition>> incomingDataEdges = emptyEdgeMap(nodes.keySet());
		Map<NodeId, List<EdgeDefinition>> outgoingDataEdges = emptyEdgeMap(nodes.keySet());
		Map<NodeId, List<EdgeDefinition>> incomingControlEdges = emptyEdgeMap(nodes.keySet());
		Map<NodeId, List<EdgeDefinition>> outgoingControlEdges = emptyEdgeMap(nodes.keySet());
		Map<NodeId, Set<PortId>> connectedControlInputs = nodes.keySet().stream()
			.collect(Collectors.toMap(nodeId -> nodeId, ignored -> new LinkedHashSet<>(), (left, right) -> left, LinkedHashMap::new));
		boolean hasControlEdges = false;
		boolean hasRequiredControlNodes = nodeTypes.values().stream().anyMatch(nodeType -> nodeType.controlMode() == NodeControlMode.REQUIRED);

		for (EdgeDefinition edge : graph.edges()) {
			NodeInstance fromNode = nodes.get(edge.fromNodeId());
			NodeInstance toNode = nodes.get(edge.toNodeId());
			if (fromNode == null) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_NODE, "Unknown source node: " + edge.fromNodeId(), edge.fromNodeId(), edge.fromPortId()));
				continue;
			}
			if (toNode == null) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_NODE, "Unknown target node: " + edge.toNodeId(), edge.toNodeId(), edge.toPortId()));
				continue;
			}

			NodeType<?> fromNodeType = nodeTypes.get(edge.fromNodeId());
			NodeType<?> toNodeType = nodeTypes.get(edge.toNodeId());
			if (fromNodeType == null || toNodeType == null) {
				continue;
			}

			PortDefinition fromPort = findPort(fromNodeType.outputs(fromNode), edge.fromPortId());
			PortDefinition toPort = findPort(toNodeType.inputs(toNode), edge.toPortId());
			if (fromPort == null) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_PORT, "Unknown output port: " + edge.fromPortId(), edge.fromNodeId(), edge.fromPortId()));
				continue;
			}
			if (toPort == null) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_PORT, "Unknown input port: " + edge.toPortId(), edge.toNodeId(), edge.toPortId()));
				continue;
			}
			PortChannel fromChannel;
			PortChannel toChannel;
			try {
				fromChannel = GraphPortTypeResolver.resolveChannel(graph, registry, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
				toChannel = GraphPortTypeResolver.resolveChannel(graph, registry, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
			} catch (IllegalArgumentException exception) {
				errors.add(GraphError.of(GraphErrorCode.CHANNEL_MISMATCH, exception.getMessage(), edge.toNodeId(), edge.toPortId()));
				continue;
			}

			if (fromChannel != toChannel) {
				errors.add(GraphError.of(
					GraphErrorCode.CHANNEL_MISMATCH,
					"Cannot connect " + fromChannel + " port to " + toChannel + " port",
					edge.toNodeId(),
					edge.toPortId()
				));
				continue;
			}

			if (fromChannel == PortChannel.CONTROL) {
				hasControlEdges = true;
				incomingControlEdges.get(edge.toNodeId()).add(edge);
				outgoingControlEdges.get(edge.fromNodeId()).add(edge);
				connectedControlInputs.get(edge.toNodeId()).add(edge.toPortId());
				continue;
			}

			ResolvedPortType sourceResolution = GraphPortTypeResolver.resolve(graph, registry, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT, variableTypeResolver);
			ResolvedPortType targetResolution = GraphPortTypeResolver.resolve(graph, registry, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT, variableTypeResolver);
			if (!GraphPortTypeResolver.canConnect(sourceResolution, targetResolution, portTypes)) {
				errors.add(GraphError.of(
					GraphErrorCode.TYPE_MISMATCH,
					"Cannot connect " + sourceResolution.effectiveType() + " to " + targetResolution.effectiveType(),
					edge.toNodeId(),
					edge.toPortId()
				));
				continue;
			}

			incomingDataEdges.get(edge.toNodeId()).add(edge);
			outgoingDataEdges.get(edge.fromNodeId()).add(edge);
		}

		// Allow latch writebacks to read the previous stored value without being rejected as a data cycle.
		Map<NodeId, List<EdgeDefinition>> incomingTopoDataEdges = emptyEdgeMap(nodes.keySet());
		Map<NodeId, List<EdgeDefinition>> outgoingTopoDataEdges = emptyEdgeMap(nodes.keySet());
		for (Map.Entry<NodeId, List<EdgeDefinition>> entry : outgoingDataEdges.entrySet()) {
			for (EdgeDefinition edge : entry.getValue()) {
				if (isLatchWriteDataEdge(edge, nodeTypes)) {
					continue;
				}
				incomingTopoDataEdges.get(edge.toNodeId()).add(edge);
				outgoingTopoDataEdges.get(edge.fromNodeId()).add(edge);
			}
		}

		List<NodeId> dataOrder = topologicalOrder(nodes.keySet(), incomingTopoDataEdges, outgoingTopoDataEdges, GraphErrorCode.CYCLE_DETECTED, errors);
		List<NodeId> controlOrder = validateControlGraph(graph, registry, nodes.keySet(), nodeTypes, graph.edges(), errors);
		Map<NodeId, Integer> dataOrderIndex = new LinkedHashMap<>();
		for (int index = 0; index < dataOrder.size(); index++) {
			dataOrderIndex.put(dataOrder.get(index), index);
		}
		Set<NodeId> persistentNodes = nodes.keySet().stream()
			.filter(nodeId -> {
				NodeType<?> nodeType = nodeTypes.get(nodeId);
				return nodeType != null
					&& (nodeType.kind() == NodeKind.EVENT_SOURCE
					|| !incomingControlEdges.getOrDefault(nodeId, List.of()).isEmpty()
					|| !outgoingControlEdges.getOrDefault(nodeId, List.of()).isEmpty());
			})
			.collect(Collectors.toCollection(LinkedHashSet::new));

		return new CompiledGraphPlan(
			graph,
			nodes,
			nodeTypes,
			incomingDataEdges,
			outgoingDataEdges,
			incomingControlEdges,
			outgoingControlEdges,
			connectedControlInputs.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue()), (left, right) -> left, LinkedHashMap::new)),
			dataOrder,
			dataOrderIndex,
			controlOrder,
			persistentNodes,
			hasControlEdges,
			hasRequiredControlNodes,
			scopePaths,
			List.copyOf(errors)
		);
	}

	private static NormalizedGraph normalizeGraph(GraphDefinition graph, NodeTypeRegistry registry) {
		Set<NodeId> rerouteNodeIds = graph.nodes().stream()
			.filter(node -> BuiltinNodeTypes.REROUTE.id().equals(node.typeId()))
			.map(NodeInstance::id)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (rerouteNodeIds.isEmpty()) {
			return new NormalizedGraph(graph, List.of());
		}

		List<GraphError> errors = new ArrayList<>();
		List<NodeInstance> nodes = graph.nodes().stream()
			.filter(node -> !rerouteNodeIds.contains(node.id()))
			.toList();
		List<EdgeDefinition> edges = new ArrayList<>();
		Set<EdgeKey> seenEdges = new LinkedHashSet<>();

		for (EdgeDefinition edge : graph.edges()) {
			boolean fromReroute = rerouteNodeIds.contains(edge.fromNodeId());
			boolean toReroute = rerouteNodeIds.contains(edge.toNodeId());
			if (!fromReroute && !toReroute) {
				addEdge(edges, seenEdges, edge.fromNodeId(), edge.fromPortId(), edge.toNodeId(), edge.toPortId());
				continue;
			}
			if (fromReroute && !BuiltinNodeTypes.VALUE_PORT.equals(edge.fromPortId())) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_PORT, "Unknown output port: " + edge.fromPortId(), edge.fromNodeId(), edge.fromPortId()));
			}
			if (toReroute && !BuiltinNodeTypes.VALUE_PORT.equals(edge.toPortId())) {
				errors.add(GraphError.of(GraphErrorCode.UNKNOWN_PORT, "Unknown input port: " + edge.toPortId(), edge.toNodeId(), edge.toPortId()));
			}
		}

		Set<NodeId> remaining = new LinkedHashSet<>(rerouteNodeIds);
		while (!remaining.isEmpty()) {
			NodeId root = remaining.iterator().next();
			RerouteComponent component = collectRerouteComponent(graph, rerouteNodeIds, root);
			remaining.removeAll(component.rerouteNodeIds());

			PortChannel channel;
			try {
				channel = GraphPortTypeResolver.resolveChannel(graph, registry, root, BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT);
			} catch (IllegalArgumentException exception) {
				errors.add(GraphError.of(GraphErrorCode.CHANNEL_MISMATCH, exception.getMessage(), root, BuiltinNodeTypes.VALUE_PORT));
				continue;
			}

			List<PortRef> sources = component.anchors().stream()
				.filter(anchor -> anchor.direction() == PortDirection.OUTPUT)
				.distinct()
				.toList();
			List<PortRef> targets = component.anchors().stream()
				.filter(anchor -> anchor.direction() == PortDirection.INPUT)
				.distinct()
				.toList();
			if (sources.size() > 1) {
				errors.add(GraphError.of(
					GraphErrorCode.INVALID_EDGE_DIRECTION,
					channel == PortChannel.CONTROL
						? "Reroute control chain cannot have multiple upstream sources"
						: "Reroute data chain cannot have multiple upstream sources",
					root,
					BuiltinNodeTypes.VALUE_PORT
				));
				continue;
			}
			if (sources.isEmpty() || targets.isEmpty()) {
				continue;
			}

			PortRef source = sources.getFirst();
			for (PortRef target : targets) {
				addEdge(edges, seenEdges, source.nodeId(), source.portId(), target.nodeId(), target.portId());
			}
		}

		return new NormalizedGraph(new GraphDefinition(nodes, edges), List.copyOf(errors));
	}

	private static RerouteComponent collectRerouteComponent(GraphDefinition graph, Set<NodeId> rerouteNodeIds, NodeId root) {
		Set<NodeId> componentNodeIds = new LinkedHashSet<>();
		Set<PortRef> anchors = new LinkedHashSet<>();
		ArrayDeque<NodeId> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			NodeId current = queue.removeFirst();
			if (!componentNodeIds.add(current)) {
				continue;
			}
			for (EdgeDefinition edge : graph.edges()) {
				PortRef anchor = rerouteAnchor(edge, current);
				if (anchor == null) {
					continue;
				}
				if (rerouteNodeIds.contains(anchor.nodeId())) {
					queue.add(anchor.nodeId());
					continue;
				}
				anchors.add(anchor);
			}
		}
		return new RerouteComponent(Set.copyOf(componentNodeIds), List.copyOf(anchors));
	}

	private static PortRef rerouteAnchor(EdgeDefinition edge, NodeId rerouteNodeId) {
		if (edge.fromNodeId().equals(rerouteNodeId) && BuiltinNodeTypes.VALUE_PORT.equals(edge.fromPortId())) {
			return new PortRef(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
		}
		if (edge.toNodeId().equals(rerouteNodeId) && BuiltinNodeTypes.VALUE_PORT.equals(edge.toPortId())) {
			return new PortRef(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
		}
		return null;
	}

	private static void addEdge(List<EdgeDefinition> edges, Set<EdgeKey> seenEdges, NodeId fromNodeId, PortId fromPortId, NodeId toNodeId, PortId toPortId) {
		EdgeKey key = new EdgeKey(fromNodeId, fromPortId, toNodeId, toPortId);
		if (!seenEdges.add(key)) {
			return;
		}
		edges.add(new EdgeDefinition(fromNodeId, fromPortId, toNodeId, toPortId));
	}

	private static void collectSubgraphs(GraphScope scope, Map<String, SubgraphDefinition> subgraphs) {
		for (SubgraphDefinition subgraph : scope.subgraphs()) {
			subgraphs.put(subgraph.id(), subgraph);
			collectSubgraphs(subgraph.scope(), subgraphs);
		}
	}

	private static Map<NodeId, List<EdgeDefinition>> emptyEdgeMap(Set<NodeId> nodeIds) {
		return nodeIds.stream().collect(Collectors.toMap(nodeId -> nodeId, ignored -> new ArrayList<>(), (left, right) -> left, LinkedHashMap::new));
	}

	private static List<NodeId> topologicalOrder(
		Set<NodeId> nodeIds,
		Map<NodeId, List<EdgeDefinition>> incomingEdges,
		Map<NodeId, List<EdgeDefinition>> outgoingEdges,
		GraphErrorCode cycleCode,
		List<GraphError> errors
	) {
		Map<NodeId, Integer> indegree = new LinkedHashMap<>();
		nodeIds.forEach(nodeId -> indegree.put(nodeId, incomingEdges.getOrDefault(nodeId, List.of()).size()));

		ArrayDeque<NodeId> queue = new ArrayDeque<>();
		for (Map.Entry<NodeId, Integer> entry : indegree.entrySet()) {
			if (entry.getValue() == 0) {
				queue.add(entry.getKey());
			}
		}

		List<NodeId> order = new ArrayList<>();
		while (!queue.isEmpty()) {
			NodeId current = queue.removeFirst();
			order.add(current);

			for (EdgeDefinition edge : outgoingEdges.getOrDefault(current, List.of())) {
				int next = indegree.computeIfPresent(edge.toNodeId(), (ignored, value) -> value - 1);
				if (next == 0) {
					queue.add(edge.toNodeId());
				}
			}
		}

		if (order.size() != nodeIds.size()) {
			errors.add(GraphError.of(cycleCode, cycleCode == GraphErrorCode.CYCLE_DETECTED ? "Graph contains a cycle" : "Control graph contains a cycle", null, null));
		}
		return order;
	}

	private static boolean isLatchWriteDataEdge(EdgeDefinition edge, Map<NodeId, NodeType<?>> nodeTypes) {
		NodeType<?> targetType = nodeTypes.get(edge.toNodeId());
		return targetType != null
			&& BuiltinNodeTypes.LATCH.id().equals(targetType.id())
			&& (BuiltinNodeTypes.VALUE_A_PORT.equals(edge.toPortId()) || BuiltinNodeTypes.VALUE_B_PORT.equals(edge.toPortId()));
	}

	private static List<NodeId> validateControlGraph(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		Set<NodeId> nodeIds,
		Map<NodeId, NodeType<?>> nodeTypes,
		List<EdgeDefinition> edges,
		List<GraphError> errors
	) {
		Map<ControlVertex, Set<ControlVertex>> outgoing = new LinkedHashMap<>();
		Map<ControlVertex, Integer> indegree = new LinkedHashMap<>();

		java.util.function.Consumer<ControlVertex> ensureVertex = vertex -> {
			outgoing.computeIfAbsent(vertex, ignored -> new LinkedHashSet<>());
			indegree.putIfAbsent(vertex, 0);
		};
		java.util.function.BiConsumer<ControlVertex, ControlVertex> addEdge = (from, to) -> {
			ensureVertex.accept(from);
			ensureVertex.accept(to);
			if (outgoing.get(from).add(to)) {
				indegree.computeIfPresent(to, (ignored, value) -> value + 1);
			}
		};

		for (Map.Entry<NodeId, NodeType<?>> entry : nodeTypes.entrySet()) {
			NodeId nodeId = entry.getKey();
			NodeType<?> nodeType = entry.getValue();
			NodeInstance node = graph.nodes().stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElse(null);
			if (node == null) {
				continue;
			}
			for (ControlRoute route : nodeType.controlRoutes(node)) {
				ensureVertex.accept(new ControlVertex(nodeId, route.inputPortId(), PortDirection.INPUT));
				ensureVertex.accept(new ControlVertex(nodeId, route.outputPortId(), PortDirection.OUTPUT));
				addEdge.accept(
					new ControlVertex(nodeId, route.inputPortId(), PortDirection.INPUT),
					new ControlVertex(nodeId, route.outputPortId(), PortDirection.OUTPUT)
				);
			}
		}

		for (EdgeDefinition edge : edges) {
			NodeType<?> fromNodeType = nodeTypes.get(edge.fromNodeId());
			NodeType<?> toNodeType = nodeTypes.get(edge.toNodeId());
			if (fromNodeType == null || toNodeType == null) {
				continue;
			}
			PortChannel fromChannel;
			PortChannel toChannel;
			try {
				fromChannel = GraphPortTypeResolver.resolveChannel(graph, registry, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
				toChannel = GraphPortTypeResolver.resolveChannel(graph, registry, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
			} catch (IllegalArgumentException exception) {
				errors.add(GraphError.of(GraphErrorCode.CHANNEL_MISMATCH, exception.getMessage(), edge.toNodeId(), edge.toPortId()));
				continue;
			}
			if (fromChannel != PortChannel.CONTROL || toChannel != PortChannel.CONTROL) {
				continue;
			}
			addEdge.accept(
				new ControlVertex(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT),
				new ControlVertex(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT)
			);
		}

		ArrayDeque<ControlVertex> queue = new ArrayDeque<>();
		for (Map.Entry<ControlVertex, Integer> entry : indegree.entrySet()) {
			if (entry.getValue() == 0) {
				queue.add(entry.getKey());
			}
		}

		List<ControlVertex> order = new ArrayList<>();
		while (!queue.isEmpty()) {
			ControlVertex current = queue.removeFirst();
			order.add(current);
			for (ControlVertex target : outgoing.getOrDefault(current, Set.of())) {
				int next = indegree.computeIfPresent(target, (ignored, value) -> value - 1);
				if (next == 0) {
					queue.add(target);
				}
			}
		}

		if (order.size() != indegree.size()) {
			errors.add(GraphError.of(GraphErrorCode.CONTROL_CYCLE_DETECTED, "Control graph contains a cycle", null, null));
		}

		LinkedHashSet<NodeId> nodeOrder = new LinkedHashSet<>();
		for (ControlVertex vertex : order) {
			nodeOrder.add(vertex.nodeId());
		}
		nodeOrder.addAll(nodeIds);
		return List.copyOf(nodeOrder);
	}

	private static PortDefinition findPort(List<PortDefinition> ports, PortId portId) {
		return ports.stream().filter(port -> port.id().equals(portId)).findFirst().orElse(null);
	}

	private record ControlVertex(NodeId nodeId, PortId portId, PortDirection direction) {
	}

	private record PortRef(NodeId nodeId, PortId portId, PortDirection direction) {
	}

	private record RerouteComponent(Set<NodeId> rerouteNodeIds, List<PortRef> anchors) {
	}

	private record EdgeKey(NodeId fromNodeId, PortId fromPortId, NodeId toNodeId, PortId toPortId) {
	}

	private record NormalizedGraph(GraphDefinition graph, List<GraphError> errors) {
	}

	private record VariableBinding(GraphVariableDefinition definition, PortType<?> type, DocumentGraphFlattener.VariableStorage storage) {
	}

	static final class DefinitionPlan {
		private final DocumentNodeTypes.DefinitionRuntimeInfo info;
		private final CompiledGraphPlan graphPlan;

		private DefinitionPlan(DocumentNodeTypes.DefinitionRuntimeInfo info, CompiledGraphPlan graphPlan) {
			this.info = info;
			this.graphPlan = graphPlan;
		}

		DocumentNodeTypes.DefinitionRuntimeInfo info() {
			return info;
		}

		CompiledGraphPlan graphPlan() {
			return graphPlan;
		}
	}

	static final class CompiledGraphPlan {
		private final GraphDefinition graph;
		private final Map<NodeId, NodeInstance> nodes;
		private final Map<NodeId, NodeType<?>> nodeTypes;
		private final Map<NodeId, List<EdgeDefinition>> incomingDataEdges;
		private final Map<NodeId, List<EdgeDefinition>> outgoingDataEdges;
		private final Map<NodeId, List<EdgeDefinition>> incomingControlEdges;
		private final Map<NodeId, List<EdgeDefinition>> outgoingControlEdges;
		private final Map<NodeId, Set<PortId>> connectedControlInputs;
		private final List<NodeId> dataOrder;
		private final Map<NodeId, Integer> dataOrderIndex;
		private final List<NodeId> controlOrder;
		private final Set<NodeId> persistentNodes;
		private final boolean hasControlEdges;
		private final boolean hasRequiredControlNodes;
		private final Map<NodeId, List<String>> scopePaths;
		private final List<GraphError> errors;

		private CompiledGraphPlan(
			GraphDefinition graph,
			Map<NodeId, NodeInstance> nodes,
			Map<NodeId, NodeType<?>> nodeTypes,
			Map<NodeId, List<EdgeDefinition>> incomingDataEdges,
			Map<NodeId, List<EdgeDefinition>> outgoingDataEdges,
			Map<NodeId, List<EdgeDefinition>> incomingControlEdges,
			Map<NodeId, List<EdgeDefinition>> outgoingControlEdges,
			Map<NodeId, Set<PortId>> connectedControlInputs,
			List<NodeId> dataOrder,
			Map<NodeId, Integer> dataOrderIndex,
			List<NodeId> controlOrder,
			Set<NodeId> persistentNodes,
			boolean hasControlEdges,
			boolean hasRequiredControlNodes,
			Map<NodeId, List<String>> scopePaths,
			List<GraphError> errors
		) {
			this.graph = graph;
			this.nodes = Map.copyOf(nodes);
			this.nodeTypes = Map.copyOf(nodeTypes);
			this.incomingDataEdges = copyEdgeMap(incomingDataEdges);
			this.outgoingDataEdges = copyEdgeMap(outgoingDataEdges);
			this.incomingControlEdges = copyEdgeMap(incomingControlEdges);
			this.outgoingControlEdges = copyEdgeMap(outgoingControlEdges);
			this.connectedControlInputs = Map.copyOf(connectedControlInputs);
			this.dataOrder = List.copyOf(dataOrder);
			this.dataOrderIndex = Map.copyOf(dataOrderIndex);
			this.controlOrder = List.copyOf(controlOrder);
			this.persistentNodes = Set.copyOf(persistentNodes);
			this.hasControlEdges = hasControlEdges;
			this.hasRequiredControlNodes = hasRequiredControlNodes;
			this.scopePaths = copyScopePaths(scopePaths);
			this.errors = List.copyOf(errors);
		}

		GraphDefinition graph() {
			return graph;
		}

		Map<NodeId, NodeInstance> nodes() {
			return nodes;
		}

		Map<NodeId, NodeType<?>> nodeTypes() {
			return nodeTypes;
		}

		Map<NodeId, List<EdgeDefinition>> incomingDataEdges() {
			return incomingDataEdges;
		}

		Map<NodeId, List<EdgeDefinition>> outgoingDataEdges() {
			return outgoingDataEdges;
		}

		Map<NodeId, List<EdgeDefinition>> incomingControlEdges() {
			return incomingControlEdges;
		}

		Map<NodeId, List<EdgeDefinition>> outgoingControlEdges() {
			return outgoingControlEdges;
		}

		Map<NodeId, Set<PortId>> connectedControlInputs() {
			return connectedControlInputs;
		}

		List<NodeId> dataOrder() {
			return dataOrder;
		}

		Map<NodeId, Integer> dataOrderIndex() {
			return dataOrderIndex;
		}

		List<NodeId> controlOrder() {
			return controlOrder;
		}

		Set<NodeId> persistentNodes() {
			return persistentNodes;
		}

		boolean hasControlExecution() {
			return hasControlEdges || hasRequiredControlNodes;
		}

		List<String> scopePath(NodeId nodeId) {
			return scopePaths.getOrDefault(nodeId, List.of());
		}

		List<GraphError> errors() {
			return errors;
		}
	}

	private static Map<NodeId, List<EdgeDefinition>> copyEdgeMap(Map<NodeId, List<EdgeDefinition>> source) {
		Map<NodeId, List<EdgeDefinition>> copy = new LinkedHashMap<>();
		for (Map.Entry<NodeId, List<EdgeDefinition>> entry : source.entrySet()) {
			copy.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return Map.copyOf(copy);
	}

	private static Map<NodeId, List<String>> copyScopePaths(Map<NodeId, List<String>> source) {
		Map<NodeId, List<String>> copy = new LinkedHashMap<>();
		source.forEach((nodeId, path) -> copy.put(nodeId, List.copyOf(path)));
		return Map.copyOf(copy);
	}
}
