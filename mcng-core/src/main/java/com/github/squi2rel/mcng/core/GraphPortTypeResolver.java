package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class GraphPortTypeResolver {
	private static final Function<String, PortType<?>> NO_VARIABLES = ignored -> null;

	private GraphPortTypeResolver() {
	}

	public static ResolvedPortType resolve(GraphDefinition graph, NodeTypeRegistry registry, NodeId nodeId, PortId portId, PortDirection direction) {
		return resolve(graph, registry, nodeId, portId, direction, NO_VARIABLES);
	}

	public static ResolvedPortType resolve(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		NodeId nodeId,
		PortId portId,
		PortDirection direction,
		Function<String, PortType<?>> variableTypeResolver
	) {
		return resolve(graph, registry, nodeId, portId, direction, variableTypeResolver, Set.of());
	}

	private static ResolvedPortType resolve(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		NodeId nodeId,
		PortId portId,
		PortDirection direction,
		Function<String, PortType<?>> variableTypeResolver,
		Set<PortRef> visiting
	) {
		PortRef currentRef = new PortRef(nodeId, portId, direction);
		if (visiting.contains(currentRef)) {
			PortDefinition definition = requirePortDefinition(graph, registry, nodeId, portId, direction);
			if (definition.numericFamily()) {
				return new ResolvedPortType(definition, MCNGPortTypes.ANY, false, true);
			}
			return definition.genericGroupId() != null
				? new ResolvedPortType(definition, definition.type(), true, false)
				: new ResolvedPortType(definition, definition.type(), false, false);
		}

		Set<PortRef> nextVisiting = new LinkedHashSet<>(visiting);
		nextVisiting.add(currentRef);

		NodeInstance node = requireNode(graph, nodeId);
		PortDefinition definition = requirePortDefinition(graph, registry, nodeId, portId, direction);
		if (resolveChannel(graph, registry, nodeId, portId, direction) == PortChannel.CONTROL) {
			return new ResolvedPortType(definition, MCNGPortTypes.ANY, false, false);
		}
		ResolvedPortType dynamic = BuiltinNodeTypes.resolveDynamicPortType(node, definition, new DynamicPortTypeResolverContext() {
			@Override
			public NodeInstance node(NodeId candidate) {
				return requireNode(graph, candidate);
			}

			@Override
			public ResolvedPortType resolve(NodeId candidateNodeId, PortId candidatePortId, PortDirection candidateDirection) {
				return GraphPortTypeResolver.resolve(graph, registry, candidateNodeId, candidatePortId, candidateDirection, variableTypeResolver, nextVisiting);
			}

			@Override
			public Object readInlineInputValue(NodeId candidateNodeId, PortId candidatePortId) {
				PortDefinition candidate = requirePortDefinition(graph, registry, candidateNodeId, candidatePortId, PortDirection.INPUT);
				return NodeConfigValues.readInlineInputValue(requireNode(graph, candidateNodeId).config(), candidate);
			}

			@Override
			public PortType<?> variableType(String variableId) {
				return variableTypeResolver.apply(variableId);
			}

			@Override
			public Iterable<EdgeDefinition> edges() {
				return graph.edges();
			}
		});
		if (dynamic != null) {
			return dynamic;
		}

		if (definition.genericGroupId() == null) {
			if (definition.numericFamily()) {
				PortType<?> inferredInlineType = inferNumericInlineType(node, definition);
				return new ResolvedPortType(definition, inferredInlineType != null ? inferredInlineType : MCNGPortTypes.ANY, false, true);
			}
			return new ResolvedPortType(definition, definition.type(), false, false);
		}

		PortType<?> resolved = resolveGenericGroupType(graph, registry, new GroupKey(nodeId, definition.genericGroupId()), Set.of(), nextVisiting, variableTypeResolver);
		if (resolved == null) {
			return definition.numericFamily()
				? new ResolvedPortType(definition, fallbackNumericType(node, definition), false, true)
				: new ResolvedPortType(definition, definition.type(), true, false);
		}
		return new ResolvedPortType(definition, resolved, false, definition.numericFamily());
	}

	public static PortChannel resolveChannel(GraphDefinition graph, NodeTypeRegistry registry, NodeId nodeId, PortId portId, PortDirection direction) {
		return resolveChannel(graph, registry, new PortRef(nodeId, portId, direction), Set.of());
	}

	private static PortChannel resolveChannel(GraphDefinition graph, NodeTypeRegistry registry, PortRef portRef, Set<PortRef> visiting) {
		PortDefinition definition = requirePortDefinition(graph, registry, portRef.nodeId(), portRef.portId(), portRef.direction());
		NodeInstance node = requireNode(graph, portRef.nodeId());
		if (!isReroute(node)) {
			return definition.channel();
		}
		return resolveRerouteComponentChannel(graph, registry, portRef.nodeId());
	}

	public static boolean canConnect(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		NodeId fromNodeId,
		PortId fromPortId,
		NodeId toNodeId,
		PortId toPortId
	) {
		return canConnect(graph, registry, portTypes, fromNodeId, fromPortId, toNodeId, toPortId, NO_VARIABLES);
	}

	public static boolean canConnect(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		NodeId fromNodeId,
		PortId fromPortId,
		NodeId toNodeId,
		PortId toPortId,
		Function<String, PortType<?>> variableTypeResolver
	) {
		return canConnect(
			resolve(graph, registry, fromNodeId, fromPortId, PortDirection.OUTPUT, variableTypeResolver),
			resolve(graph, registry, toNodeId, toPortId, PortDirection.INPUT, variableTypeResolver),
			portTypes
		);
	}

	public static boolean canConnect(ResolvedPortType from, ResolvedPortType to, PortTypeRegistry portTypes) {
		if (from.unresolvedGeneric() || to.unresolvedGeneric()) {
			return true;
		}
		if (acceptsNumericSource(from, to)) {
			return true;
		}
		if (from.unresolvedNumeric() && to.unresolvedNumeric()) {
			return true;
		}
		if (from.unresolvedNumeric()) {
			return NumericTypes.isNumericType(to.effectiveType()) || to.numericFamily();
		}
		if (to.unresolvedNumeric()) {
			return NumericTypes.isNumericType(from.effectiveType());
		}
		return portTypes.canConnect(from.effectiveType(), to.effectiveType());
	}

	private static boolean acceptsNumericSource(ResolvedPortType from, ResolvedPortType to) {
		return to.definition().direction() == PortDirection.INPUT
			&& to.definition().numericFamily()
			&& (from.unresolvedNumeric() || NumericTypes.isNumericType(from.effectiveType()));
	}

	private static PortType<?> resolveGenericGroupType(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		GroupKey groupKey,
		Set<GroupKey> visiting,
		Set<PortRef> portVisiting,
		Function<String, PortType<?>> variableTypeResolver
	) {
		if (visiting.contains(groupKey)) {
			return null;
		}
		if (isRerouteGroup(graph, registry, groupKey)) {
			return resolveRerouteComponentType(graph, registry, groupKey.nodeId(), visiting, portVisiting, variableTypeResolver);
		}

		Set<GroupKey> visited = new LinkedHashSet<>(visiting);
		visited.add(groupKey);

		for (EdgeDefinition edge : graph.edges()) {
			PortType<?> inferred = inferFromEdge(graph, registry, groupKey, edge, visited, portVisiting, variableTypeResolver);
			if (inferred != null) {
				return inferred;
			}
		}
		PortType<?> inferredInlineType = inferNumericGroupTypeFromInlineInputs(graph, registry, groupKey);
		if (inferredInlineType != null) {
			return inferredInlineType;
		}
		return null;
	}

	private static PortType<?> fallbackNumericType(NodeInstance node, PortDefinition definition) {
		PortType<?> inferredInlineType = inferNumericInlineType(node, definition);
		return inferredInlineType != null ? inferredInlineType : MCNGPortTypes.ANY;
	}

	private static PortType<?> inferNumericGroupTypeFromInlineInputs(GraphDefinition graph, NodeTypeRegistry registry, GroupKey groupKey) {
		NodeInstance node = requireNode(graph, groupKey.nodeId());
		NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
		if (nodeType == null) {
			return null;
		}

		PortType<?> resolved = null;
		for (PortDefinition input : nodeType.inputs(node)) {
			if (!groupKey.groupId().equals(input.genericGroupId())) {
				continue;
			}
			if (hasIncomingEdge(graph, groupKey.nodeId(), input.id())) {
				continue;
			}
			PortType<?> inferredInlineType = inferNumericInlineType(node, input);
			if (inferredInlineType == null) {
				continue;
			}
			resolved = resolved == null ? inferredInlineType : NumericTypes.widen(resolved, inferredInlineType);
		}
		return resolved;
	}

	private static PortType<?> inferNumericInlineType(NodeInstance node, PortDefinition definition) {
		if (definition.direction() != PortDirection.INPUT || !definition.numericFamily() || definition.inlineWidget() == null) {
			return null;
		}
		Object inlineValue;
		try {
			inlineValue = NodeConfigValues.readInlineInputValue(node.config(), definition);
		} catch (IllegalArgumentException exception) {
			return null;
		}
		return inlineValue != null && NumericTypes.isNumericValue(inlineValue) ? NumericTypes.typeOf(inlineValue) : null;
	}

	private static boolean hasIncomingEdge(GraphDefinition graph, NodeId nodeId, PortId portId) {
		for (EdgeDefinition edge : graph.edges()) {
			if (edge.toNodeId().equals(nodeId) && edge.toPortId().equals(portId)) {
				return true;
			}
		}
		return false;
	}

	private static PortChannel resolveRerouteComponentChannel(GraphDefinition graph, NodeTypeRegistry registry, NodeId rerouteNodeId) {
		RerouteComponent component = collectRerouteComponent(graph, registry, rerouteNodeId);
		Set<PortChannel> resolved = new LinkedHashSet<>();
		for (PortRef anchor : component.anchors()) {
			resolved.add(requirePortDefinition(graph, registry, anchor.nodeId(), anchor.portId(), anchor.direction()).channel());
		}
		if (resolved.size() > 1) {
			throw new IllegalArgumentException("Reroute cannot mix data and control edges");
		}
		return resolved.isEmpty() ? PortChannel.DATA : resolved.iterator().next();
	}

	private static PortType<?> resolveRerouteComponentType(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		NodeId rerouteNodeId,
		Set<GroupKey> visiting,
		Set<PortRef> portVisiting,
		Function<String, PortType<?>> variableTypeResolver
	) {
		RerouteComponent component = collectRerouteComponent(graph, registry, rerouteNodeId);
		for (PortRef anchor : component.anchors()) {
			PortDefinition definition = requirePortDefinition(graph, registry, anchor.nodeId(), anchor.portId(), anchor.direction());
			if (definition.channel() != PortChannel.DATA) {
				continue;
			}
			PortType<?> inferred = inferFromAnchor(graph, registry, anchor, visiting, portVisiting, variableTypeResolver);
			if (inferred != null) {
				return inferred;
			}
		}
		return null;
	}

	private static PortType<?> inferFromAnchor(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		PortRef anchor,
		Set<GroupKey> visiting,
		Set<PortRef> portVisiting,
		Function<String, PortType<?>> variableTypeResolver
	) {
		PortDefinition definition = requirePortDefinition(graph, registry, anchor.nodeId(), anchor.portId(), anchor.direction());
		if (definition.genericGroupId() != null) {
			PortType<?> resolved = resolveGenericGroupType(graph, registry, new GroupKey(anchor.nodeId(), definition.genericGroupId()), visiting, portVisiting, variableTypeResolver);
			if (resolved == null || resolved.equals(MCNGPortTypes.ANY)) {
				return null;
			}
			return resolved;
		}
		if (definition.numericFamily() && anchor.direction() == PortDirection.INPUT) {
			return null;
		}
		ResolvedPortType resolved = resolve(graph, registry, anchor.nodeId(), definition.id(), anchor.direction(), variableTypeResolver, portVisiting);
		if (resolved.unresolvedGeneric() || resolved.unresolvedNumeric()) {
			return null;
		}
		return resolved.effectiveType();
	}

	private static RerouteComponent collectRerouteComponent(GraphDefinition graph, NodeTypeRegistry registry, NodeId rerouteNodeId) {
		Set<NodeId> rerouteNodeIds = new LinkedHashSet<>();
		List<PortRef> anchors = new ArrayList<>();
		ArrayDeque<NodeId> queue = new ArrayDeque<>();
		queue.add(rerouteNodeId);
		while (!queue.isEmpty()) {
			NodeId currentNodeId = queue.removeFirst();
			if (!rerouteNodeIds.add(currentNodeId)) {
				continue;
			}
			NodeInstance node = requireNode(graph, currentNodeId);
			if (!isReroute(node)) {
				continue;
			}
			for (EdgeDefinition edge : graph.edges()) {
				PortRef opposite = rerouteOpposite(edge, currentNodeId);
				if (opposite == null) {
					continue;
				}
				NodeInstance oppositeNode = requireNode(graph, opposite.nodeId());
				if (isReroute(oppositeNode)) {
					queue.addLast(opposite.nodeId());
					continue;
				}
				anchors.add(opposite);
			}
		}
		return new RerouteComponent(Set.copyOf(rerouteNodeIds), List.copyOf(anchors));
	}

	private static PortRef rerouteOpposite(EdgeDefinition edge, NodeId rerouteNodeId) {
		if (edge.fromNodeId().equals(rerouteNodeId) && edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)) {
			return new PortRef(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
		}
		if (edge.toNodeId().equals(rerouteNodeId) && edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)) {
			return new PortRef(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
		}
		return null;
	}

	private static PortType<?> inferFromEdge(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		GroupKey groupKey,
		EdgeDefinition edge,
		Set<GroupKey> visiting,
		Set<PortRef> portVisiting,
		Function<String, PortType<?>> variableTypeResolver
	) {
		PortDefinition fromDefinition = findPortDefinition(graph, registry, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
		PortDefinition toDefinition = findPortDefinition(graph, registry, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
		if (fromDefinition == null || toDefinition == null) {
			return null;
		}
		if (resolveChannel(graph, registry, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT) != PortChannel.DATA
			|| resolveChannel(graph, registry, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT) != PortChannel.DATA) {
			return null;
		}

		if (matchesGroup(edge.fromNodeId(), fromDefinition, groupKey)) {
			return inferFromOpposite(graph, registry, edge.toNodeId(), toDefinition, PortDirection.INPUT, visiting, portVisiting, variableTypeResolver);
		}
		if (matchesGroup(edge.toNodeId(), toDefinition, groupKey)) {
			return inferFromOpposite(graph, registry, edge.fromNodeId(), fromDefinition, PortDirection.OUTPUT, visiting, portVisiting, variableTypeResolver);
		}
		return null;
	}

	private static PortType<?> inferFromOpposite(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		NodeId nodeId,
		PortDefinition definition,
		PortDirection direction,
		Set<GroupKey> visiting,
		Set<PortRef> portVisiting,
		Function<String, PortType<?>> variableTypeResolver
	) {
		if (definition.genericGroupId() != null) {
			PortType<?> resolved = resolveGenericGroupType(graph, registry, new GroupKey(nodeId, definition.genericGroupId()), visiting, portVisiting, variableTypeResolver);
			if (resolved == null || resolved.equals(MCNGPortTypes.ANY)) {
				return null;
			}
			return resolved;
		}
		if (definition.numericFamily() && direction == PortDirection.INPUT) {
			return null;
		}
		ResolvedPortType resolved = resolve(graph, registry, nodeId, definition.id(), direction, variableTypeResolver, portVisiting);
		if (resolved.unresolvedGeneric() || resolved.unresolvedNumeric()) {
			return null;
		}
		return resolved.effectiveType();
	}

	private static boolean matchesGroup(NodeId nodeId, PortDefinition definition, GroupKey groupKey) {
		return definition.genericGroupId() != null
			&& nodeId.equals(groupKey.nodeId())
			&& definition.genericGroupId().equals(groupKey.groupId());
	}

	private static boolean isRerouteGroup(GraphDefinition graph, NodeTypeRegistry registry, GroupKey groupKey) {
		NodeInstance node = graph.nodes().stream().filter(candidate -> candidate.id().equals(groupKey.nodeId())).findFirst().orElse(null);
		if (node == null || !isReroute(node)) {
			return false;
		}
		return registry.find(node.typeId())
			.map(NodeType::inputs)
			.stream()
			.flatMap(List::stream)
			.anyMatch(port -> groupKey.groupId().equals(port.genericGroupId()));
	}

	private static boolean isReroute(NodeInstance node) {
		return BuiltinNodeTypes.REROUTE.id().equals(node.typeId());
	}

	private static NodeInstance requireNode(GraphDefinition graph, NodeId nodeId) {
		NodeInstance node = graph.nodes().stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElse(null);
		if (node == null) {
			throw new IllegalArgumentException("Unknown node " + nodeId);
		}
		return node;
	}

	private static PortDefinition requirePortDefinition(GraphDefinition graph, NodeTypeRegistry registry, NodeId nodeId, PortId portId, PortDirection direction) {
		PortDefinition definition = findPortDefinition(graph, registry, nodeId, portId, direction);
		if (definition == null) {
			throw new IllegalArgumentException("Unknown " + direction + " port " + portId + " on " + nodeId);
		}
		return definition;
	}

	private static PortDefinition findPortDefinition(GraphDefinition graph, NodeTypeRegistry registry, NodeId nodeId, PortId portId, PortDirection direction) {
		NodeInstance node = graph.nodes().stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElse(null);
		if (node == null) {
			return null;
		}
		NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
		if (nodeType == null) {
			return null;
		}
		List<PortDefinition> ports = direction == PortDirection.INPUT ? nodeType.inputs(node) : nodeType.outputs(node);
		return ports.stream().filter(port -> port.id().equals(portId)).findFirst().orElse(null);
	}

	private record GroupKey(NodeId nodeId, String groupId) {
	}

	private record PortRef(NodeId nodeId, PortId portId, PortDirection direction) {
	}

	private record RerouteComponent(Set<NodeId> rerouteNodeIds, List<PortRef> anchors) {
	}
}
