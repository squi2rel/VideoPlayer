package com.github.squi2rel.mcng.core;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GraphBuilder {
	private final NodeTypeRegistry registry;
	private final PortTypeRegistry portTypes;
	private final Map<NodeId, NodeInstance> nodes = new LinkedHashMap<>();
	private final Map<NodeId, NodePosition> positions = new LinkedHashMap<>();
	private final Map<NodeId, NodeSize> sizes = new LinkedHashMap<>();
	private final List<EdgeDefinition> edges = new ArrayList<>();

	public GraphBuilder(NodeTypeRegistry registry, PortTypeRegistry portTypes) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.portTypes = Objects.requireNonNull(portTypes, "portTypes");
	}

	public <C> NodeId addNode(NodeType<C> nodeType, C config, NodePosition position) {
		return addNode(NodeId.random(), nodeType, config, position);
	}

	public <C> NodeId addNode(NodeId nodeId, NodeType<C> nodeType, C config, NodePosition position) {
		Objects.requireNonNull(nodeId, "nodeId");
		Objects.requireNonNull(nodeType, "nodeType");
		Objects.requireNonNull(position, "position");
		registry.getOrThrow(nodeType.id());

		if (nodes.containsKey(nodeId)) {
			throw new IllegalArgumentException("Duplicate node id: " + nodeId);
		}

		nodes.put(nodeId, new NodeInstance(nodeId, nodeType.id(), nodeType.configCodec().toJson(config)));
		positions.put(nodeId, position);
		return nodeId;
	}

	public GraphBuilder addEdge(NodeId fromNodeId, String fromPortId, NodeId toNodeId, String toPortId) {
		return addEdge(fromNodeId, new PortId(fromPortId), toNodeId, new PortId(toPortId));
	}

	public GraphBuilder addEdge(NodeId fromNodeId, PortId fromPortId, NodeId toNodeId, PortId toPortId) {
		NodeInstance fromNode = requireNode(fromNodeId);
		NodeInstance toNode = requireNode(toNodeId);
		requirePort(fromNode, fromPortId, PortDirection.OUTPUT);
		requirePort(toNode, toPortId, PortDirection.INPUT);
		GraphDefinition candidateGraph = new GraphDefinition(
			new ArrayList<>(nodes.values()),
			appendEdge(edges, new EdgeDefinition(fromNodeId, fromPortId, toNodeId, toPortId))
		);
		PortChannel fromChannel = GraphPortTypeResolver.resolveChannel(candidateGraph, registry, fromNodeId, fromPortId, PortDirection.OUTPUT);
		PortChannel toChannel = GraphPortTypeResolver.resolveChannel(candidateGraph, registry, toNodeId, toPortId, PortDirection.INPUT);

		if (fromChannel != toChannel) {
			throw new IllegalArgumentException("Incompatible edge channels: " + fromChannel + " -> " + toChannel);
		}

		if (fromChannel == PortChannel.DATA) {
			if (!GraphPortTypeResolver.canConnect(candidateGraph, registry, portTypes, fromNodeId, fromPortId, toNodeId, toPortId)) {
				PortType<?> fromType = GraphPortTypeResolver.resolve(candidateGraph, registry, fromNodeId, fromPortId, PortDirection.OUTPUT).effectiveType();
				PortType<?> toType = GraphPortTypeResolver.resolve(candidateGraph, registry, toNodeId, toPortId, PortDirection.INPUT).effectiveType();
				throw new IllegalArgumentException("Incompatible edge types: " + fromType + " -> " + toType);
			}
		}

		boolean occupied = edges.stream().anyMatch(edge -> edge.toNodeId().equals(toNodeId) && edge.toPortId().equals(toPortId));
		if (occupied) {
			throw new IllegalArgumentException("Input port already has a connection: " + toNodeId + ":" + toPortId);
		}

		edges.add(new EdgeDefinition(fromNodeId, fromPortId, toNodeId, toPortId));
		return this;
	}

	public GraphBuilder setInlineInput(NodeId nodeId, String portId, JsonElement value) {
		return setInlineInput(nodeId, new PortId(portId), value);
	}

	public GraphBuilder setInlineInput(NodeId nodeId, PortId portId, JsonElement value) {
		NodeInstance node = requireNode(nodeId);
		nodes.put(nodeId, new NodeInstance(node.id(), node.typeId(), NodeConfigValues.copyWithInlineInput(node.config(), portId, value)));
		return this;
	}

	public GraphBuilder setNodeSize(NodeId nodeId, NodeSize size) {
		requireNode(nodeId);
		Objects.requireNonNull(size, "size");
		sizes.put(nodeId, size);
		return this;
	}

	public GraphDefinition buildGraph() {
		return new GraphDefinition(new ArrayList<>(nodes.values()), edges);
	}

	public GraphDocument buildDocument() {
		return GraphDocument.of(buildGraph(), new GraphLayout(positions, sizes));
	}

	private NodeInstance requireNode(NodeId nodeId) {
		NodeInstance node = nodes.get(nodeId);
		if (node == null) {
			throw new IllegalArgumentException("Unknown node id: " + nodeId);
		}
		return node;
	}

	private static List<EdgeDefinition> appendEdge(List<EdgeDefinition> existing, EdgeDefinition edge) {
		List<EdgeDefinition> candidate = new ArrayList<>(existing);
		candidate.add(edge);
		return candidate;
	}

	private PortDefinition requirePort(NodeInstance node, PortId portId, PortDirection direction) {
		NodeType<?> nodeType = registry.getOrThrow(node.typeId());
		List<PortDefinition> ports = direction == PortDirection.INPUT ? nodeType.inputs(node) : nodeType.outputs(node);
		return ports.stream()
			.filter(port -> port.id().equals(portId))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown " + direction + " port " + portId + " on " + node.typeId()));
	}
}
