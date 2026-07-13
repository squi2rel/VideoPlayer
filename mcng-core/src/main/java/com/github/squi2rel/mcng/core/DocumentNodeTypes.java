package com.github.squi2rel.mcng.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DocumentNodeTypes {
	public static final String GRAPH_INPUT_TYPE_ID = "mcng:graph_input";
	public static final String GRAPH_OUTPUT_TYPE_ID = "mcng:graph_output";
	public static final String FLOW_INPUT_TYPE_ID = "mcng:flow_input";
	public static final String FLOW_OUTPUT_TYPE_ID = "mcng:flow_output";
	public static final String SUBGRAPH_INPUT_TYPE_ID = "mcng:subgraph_input";
	public static final String SUBGRAPH_OUTPUT_TYPE_ID = "mcng:subgraph_output";
	public static final String SUBGRAPH_FLOW_INPUT_TYPE_ID = "mcng:subgraph_flow_input";
	public static final String SUBGRAPH_FLOW_OUTPUT_TYPE_ID = "mcng:subgraph_flow_output";
	public static final String DEFINITION_TYPE_PREFIX = "mcng:def/";
	public static final String SUBGRAPH_TYPE_PREFIX = "mcng:subgraph/";
	public static final String DEFINITION_NAME_CONTROL_KEY = "__definitionName";

	private static final String BOUNDARY_GROUP_PREFIX = "boundary:";
	private static final String PORT_NAME_KEY = "name";
	private static final String PORT_INLINE_INPUT_KEY = "inlineInput";
	private static final PortId VALUE_PORT = new PortId("value");
	private static final PortId FLOW_PORT = new PortId("flow");
	private static final NodeEditorControl.TextControl PORT_NAME_CONTROL = new NodeEditorControl.TextControl(PORT_NAME_KEY, "Name", "Port");
	private static final NodeEditorControl.BooleanControl PORT_INLINE_INPUT_CONTROL = new NodeEditorControl.BooleanControl(PORT_INLINE_INPUT_KEY, "Inline Input", false);
	private static final NodeConfigCodec<JsonObject> RAW_JSON_CODEC = new RawJsonCodec();

	public static final NodeType<JsonObject> GRAPH_INPUT = helperNode(
		GRAPH_INPUT_TYPE_ID,
		"Graph Input",
		List.of(),
		List.of(PortDefinition.outputGeneric("value", "Value", "value")),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.context().resolveGraphInput(request.node().id()))),
		true
	);

	public static final NodeType<JsonObject> GRAPH_OUTPUT = helperNode(
		GRAPH_OUTPUT_TYPE_ID,
		"Graph Output",
		List.of(PortDefinition.inputGeneric("value", "Value", true, "value")),
		List.of(),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.inputs().get(VALUE_PORT)))
	);

	public static final NodeType<JsonObject> FLOW_INPUT = helperNode(
		FLOW_INPUT_TYPE_ID,
		"Flow Input",
		List.of(),
		List.of(PortDefinition.controlOutput("flow", "Flow")),
		request -> request.trigger().kind() == ExecutionTriggerKind.CONTROL
			? NodeExecutionResult.control(List.of(FLOW_PORT))
			: NodeExecutionResult.of(Map.of())
	);

	public static final NodeType<JsonObject> FLOW_OUTPUT = helperNode(
		FLOW_OUTPUT_TYPE_ID,
		"Flow Output",
		List.of(PortDefinition.controlInput("flow", "Flow")),
		List.of(),
		request -> NodeExecutionResult.of(Map.of())
	);

	public static final NodeType<JsonObject> SUBGRAPH_INPUT = helperNode(
		SUBGRAPH_INPUT_TYPE_ID,
		"Subgraph Input",
		List.of(),
		List.of(PortDefinition.outputGeneric("value", "Value", "value")),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.context().resolveGraphInput(request.node().id()))),
		true
	);

	public static final NodeType<JsonObject> SUBGRAPH_OUTPUT = helperNode(
		SUBGRAPH_OUTPUT_TYPE_ID,
		"Subgraph Output",
		List.of(PortDefinition.inputGeneric("value", "Value", true, "value")),
		List.of(),
		request -> NodeExecutionResult.of(Map.of(VALUE_PORT, request.inputs().get(VALUE_PORT)))
	);

	public static final NodeType<JsonObject> SUBGRAPH_FLOW_INPUT = helperNode(
		SUBGRAPH_FLOW_INPUT_TYPE_ID,
		"Subgraph Flow Input",
		List.of(),
		List.of(PortDefinition.controlOutput("flow", "Flow")),
		request -> request.trigger().kind() == ExecutionTriggerKind.CONTROL
			? NodeExecutionResult.control(List.of(FLOW_PORT))
			: NodeExecutionResult.of(Map.of())
	);

	public static final NodeType<JsonObject> SUBGRAPH_FLOW_OUTPUT = helperNode(
		SUBGRAPH_FLOW_OUTPUT_TYPE_ID,
		"Subgraph Flow Output",
		List.of(PortDefinition.controlInput("flow", "Flow")),
		List.of(),
		request -> NodeExecutionResult.of(Map.of())
	);

	private DocumentNodeTypes() {
	}

	public static NodeTypeRegistry createResolvedRegistry(NodeTypeRegistry baseRegistry, PortTypeRegistry portTypes, GraphDocument document) {
		Objects.requireNonNull(baseRegistry, "baseRegistry");
		Objects.requireNonNull(portTypes, "portTypes");
		Objects.requireNonNull(document, "document");

		NodeTypeRegistry resolved = new NodeTypeRegistry();
		for (NodeType<?> nodeType : baseRegistry.all()) {
			if (resolved.find(nodeType.id()).isEmpty()) {
				resolved.register(nodeType);
			}
		}
		registerIfMissing(resolved, GRAPH_INPUT);
		registerIfMissing(resolved, GRAPH_OUTPUT);
		registerIfMissing(resolved, FLOW_INPUT);
		registerIfMissing(resolved, FLOW_OUTPUT);
		registerIfMissing(resolved, SUBGRAPH_INPUT);
		registerIfMissing(resolved, SUBGRAPH_OUTPUT);
		registerIfMissing(resolved, SUBGRAPH_FLOW_INPUT);
		registerIfMissing(resolved, SUBGRAPH_FLOW_OUTPUT);

		Map<String, DocumentNodeDefinition> definitions = indexDefinitions(document);
		Map<String, SubgraphDefinition> subgraphs = indexSubgraphs(document);
		for (SubgraphDefinition subgraph : subgraphs.values()) {
			registerIfMissing(resolved, new DynamicSubgraphNodeType(subgraph.id(), definitions, subgraphs, resolved, portTypes));
		}
		for (DocumentNodeDefinition definition : definitions.values()) {
			registerIfMissing(resolved, new DynamicDefinitionNodeType(definition.id(), definitions, subgraphs, resolved, portTypes));
		}
		return resolved;
	}

	public static List<DocumentNodeDefinition> definitionsFromRegistry(NodeTypeRegistry registry) {
		List<DocumentNodeDefinition> definitions = new ArrayList<>();
		for (NodeType<?> nodeType : registry.all()) {
			if (nodeType instanceof DynamicDefinitionNodeType dynamicDefinitionNodeType) {
				definitions.add(dynamicDefinitionNodeType.definition());
			}
		}
		return definitions;
	}

	public static DefinitionRuntimeInfo definitionRuntimeInfo(
		DocumentNodeDefinition definition,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes
	) {
		DefinitionMetadata metadata = definitionMetadata(definition, definitions, subgraphs, registry, portTypes, Set.of());
		return new DefinitionRuntimeInfo(
			definition.id(),
			definition.displayName(),
			definition.scope(),
			metadata.inputs(),
			metadata.outputs(),
			metadata.exposedDataOutputs().stream()
				.map(output -> new DefinitionOutputMapping(output.definition(), output.nodeId()))
				.toList(),
			metadata.error()
		);
	}

	public static String readDefinitionDisplayName(NodeType<?> nodeType) {
		if (nodeType instanceof DynamicDefinitionNodeType dynamicDefinitionNodeType) {
			return dynamicDefinitionNodeType.definition().displayName();
		}
		if (nodeType instanceof DynamicSubgraphNodeType dynamicSubgraphNodeType) {
			return dynamicSubgraphNodeType.subgraph().displayName();
		}
		return nodeType.displayName();
	}

	public static boolean isDefinitionType(String typeId) {
		return typeId != null && typeId.startsWith(DEFINITION_TYPE_PREFIX);
	}

	public static boolean isSubgraphType(String typeId) {
		return typeId != null && typeId.startsWith(SUBGRAPH_TYPE_PREFIX);
	}

	public static String definitionTypeId(String definitionId) {
		return DEFINITION_TYPE_PREFIX + definitionId;
	}

	public static String subgraphTypeId(String subgraphId) {
		return SUBGRAPH_TYPE_PREFIX + subgraphId;
	}

	public static String definitionIdFromTypeId(String typeId) {
		return isDefinitionType(typeId) ? typeId.substring(DEFINITION_TYPE_PREFIX.length()) : null;
	}

	public static String subgraphIdFromTypeId(String typeId) {
		return isSubgraphType(typeId) ? typeId.substring(SUBGRAPH_TYPE_PREFIX.length()) : null;
	}

	public static boolean isHelperType(String typeId) {
		return GRAPH_INPUT_TYPE_ID.equals(typeId)
			|| GRAPH_OUTPUT_TYPE_ID.equals(typeId)
			|| FLOW_INPUT_TYPE_ID.equals(typeId)
			|| FLOW_OUTPUT_TYPE_ID.equals(typeId)
			|| SUBGRAPH_INPUT_TYPE_ID.equals(typeId)
			|| SUBGRAPH_OUTPUT_TYPE_ID.equals(typeId)
			|| SUBGRAPH_FLOW_INPUT_TYPE_ID.equals(typeId)
			|| SUBGRAPH_FLOW_OUTPUT_TYPE_ID.equals(typeId);
	}

	private static NodeType<JsonObject> helperNode(
		String id,
		String displayName,
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		java.util.function.Function<NodeExecutionRequest<JsonObject>, NodeExecutionResult> executor
	) {
		return helperNode(id, displayName, inputs, outputs, executor, false);
	}

	private static NodeType<JsonObject> helperNode(
		String id,
		String displayName,
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		java.util.function.Function<NodeExecutionRequest<JsonObject>, NodeExecutionResult> executor,
		boolean supportsInlineInputExposure
	) {
		return new HelperNodeType(id, displayName, inputs, outputs, executor, supportsInlineInputExposure);
	}

	private static void registerIfMissing(NodeTypeRegistry registry, NodeType<?> nodeType) {
		if (registry.find(nodeType.id()).isEmpty()) {
			registry.register(nodeType);
		}
	}

	private static Map<String, DocumentNodeDefinition> indexDefinitions(GraphDocument document) {
		Map<String, DocumentNodeDefinition> definitions = new LinkedHashMap<>();
		for (DocumentNodeDefinition definition : document.definitions()) {
			definitions.put(definition.id(), definition);
		}
		return definitions;
	}

	private static Map<String, SubgraphDefinition> indexSubgraphs(GraphDocument document) {
		Map<String, SubgraphDefinition> subgraphs = new LinkedHashMap<>();
		collectSubgraphs(document.rootScope(), subgraphs);
		for (DocumentNodeDefinition definition : document.definitions()) {
			collectSubgraphs(definition.scope(), subgraphs);
		}
		return subgraphs;
	}

	private static void collectSubgraphs(GraphScope scope, Map<String, SubgraphDefinition> subgraphs) {
		for (SubgraphDefinition subgraph : scope.subgraphs()) {
			subgraphs.put(subgraph.id(), subgraph);
			collectSubgraphs(subgraph.scope(), subgraphs);
		}
	}

	private static DefinitionMetadata definitionMetadata(
		DocumentNodeDefinition definition,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		Set<String> visitingDefinitions
	) {
		Set<String> nextVisiting = new LinkedHashSet<>(visitingDefinitions);
		if (!nextVisiting.add(definition.id())) {
			return DefinitionMetadata.invalid("Recursive definition: " + definition.id());
		}

		ScopeMetadata metadata = scopeMetadata(
			definition.scope(),
			registry,
			new BoundaryFamily(GRAPH_INPUT_TYPE_ID, GRAPH_OUTPUT_TYPE_ID, FLOW_INPUT_TYPE_ID, FLOW_OUTPUT_TYPE_ID),
			definitions,
			subgraphs,
			portTypes,
			nextVisiting,
			true
		);
		return new DefinitionMetadata(metadata.inputs(), metadata.outputs(), metadata.exposedDataOutputs(), metadata.error());
	}

	private static ScopeMetadata subgraphMetadata(
		SubgraphDefinition subgraph,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		Set<String> visitingDefinitions
	) {
		return scopeMetadata(
			subgraph.scope(),
			registry,
			new BoundaryFamily(SUBGRAPH_INPUT_TYPE_ID, SUBGRAPH_OUTPUT_TYPE_ID, SUBGRAPH_FLOW_INPUT_TYPE_ID, SUBGRAPH_FLOW_OUTPUT_TYPE_ID),
			definitions,
			subgraphs,
			portTypes,
			visitingDefinitions,
			false
		);
	}

	private static ScopeMetadata scopeMetadata(
		GraphScope scope,
		NodeTypeRegistry registry,
		BoundaryFamily boundaryFamily,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		PortTypeRegistry portTypes,
		Set<String> visitingDefinitions,
		boolean customDefinition
	) {
		ValidationResult validation = validateNestedScopes(scope, definitions, subgraphs, registry, portTypes, visitingDefinitions, customDefinition);
		if (validation.error() != null) {
			return ScopeMetadata.invalid(validation.error());
		}

		List<BoundaryEndpoint> dataInputs = sortedBoundaries(scope, boundaryFamily.dataInputTypeId(), PortDirection.INPUT);
		List<BoundaryEndpoint> dataOutputs = sortedBoundaries(scope, boundaryFamily.dataOutputTypeId(), PortDirection.OUTPUT);
		List<BoundaryEndpoint> controlInputs = sortedBoundaries(scope, boundaryFamily.controlInputTypeId(), PortDirection.INPUT);
		List<BoundaryEndpoint> controlOutputs = sortedBoundaries(scope, boundaryFamily.controlOutputTypeId(), PortDirection.OUTPUT);
		if (hasDuplicateNames(dataInputs) || hasDuplicateNames(dataOutputs) || hasDuplicateNames(controlInputs) || hasDuplicateNames(controlOutputs)) {
			return ScopeMetadata.invalid("Duplicate exposed port names");
		}
		if (customDefinition && (!controlInputs.isEmpty() || !controlOutputs.isEmpty())) {
			return ScopeMetadata.invalid("Custom nodes cannot expose control flow");
		}

		BoundaryAnalysis dataBoundaryAnalysis = analyzeDataBoundaries(scope.graph(), registry, dataInputs, dataOutputs);
		if (dataBoundaryAnalysis.error() != null) {
			return ScopeMetadata.invalid(dataBoundaryAnalysis.error());
		}

		List<PortDefinition> inputs = new ArrayList<>();
		inputs.addAll(controlInputs.stream().map(endpoint -> PortDefinition.controlInput(endpoint.nodeId().value(), endpoint.name())).toList());
		inputs.addAll(dataBoundaryAnalysis.inputs());

		List<PortDefinition> outputs = new ArrayList<>();
		outputs.addAll(controlOutputs.stream().map(endpoint -> PortDefinition.controlOutput(endpoint.nodeId().value(), endpoint.name())).toList());
		outputs.addAll(dataBoundaryAnalysis.outputs());
		return new ScopeMetadata(inputs, outputs, dataBoundaryAnalysis.outputMappings(), null);
	}

	private static ValidationResult validateNestedScopes(
		GraphScope scope,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		Set<String> visitingDefinitions,
		boolean customDefinition
	) {
		for (NodeInstance node : scope.graph().nodes()) {
			if (isHelperType(node.typeId())) {
				continue;
			}
			if (isDefinitionType(node.typeId())) {
				DocumentNodeDefinition nested = definitions.get(definitionIdFromTypeId(node.typeId()));
				if (nested == null) {
					continue;
				}
				DefinitionMetadata nestedMetadata = definitionMetadata(nested, definitions, subgraphs, registry, portTypes, visitingDefinitions);
				if (nestedMetadata.invalid()) {
					return new ValidationResult(nestedMetadata.error());
				}
				continue;
			}
			if (isSubgraphType(node.typeId())) {
				SubgraphDefinition nested = subgraphs.get(subgraphIdFromTypeId(node.typeId()));
				if (nested == null) {
					continue;
				}
				ScopeMetadata nestedMetadata = subgraphMetadata(nested, definitions, subgraphs, registry, portTypes, visitingDefinitions);
				if (nestedMetadata.invalid()) {
					return new ValidationResult(nestedMetadata.error());
				}
				if (customDefinition && scopeContainsEventSource(nested.scope(), definitions, subgraphs, registry, portTypes, visitingDefinitions)) {
					return new ValidationResult("Custom nodes cannot contain event sources");
				}
				continue;
			}
			NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
			if (customDefinition && nodeType != null && nodeType.kind() == NodeKind.EVENT_SOURCE) {
				return new ValidationResult("Custom nodes cannot contain event sources");
			}
		}
		return new ValidationResult(null);
	}

	private static boolean scopeContainsEventSource(
		GraphScope scope,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		Set<String> visitingDefinitions
	) {
		for (NodeInstance node : scope.graph().nodes()) {
			if (isHelperType(node.typeId())) {
				continue;
			}
			if (isDefinitionType(node.typeId())) {
				DocumentNodeDefinition nested = definitions.get(definitionIdFromTypeId(node.typeId()));
				if (nested != null) {
					DefinitionMetadata metadata = definitionMetadata(nested, definitions, subgraphs, registry, portTypes, visitingDefinitions);
					if (!metadata.invalid() && containsEventSource(nested.scope(), definitions, subgraphs, registry, portTypes, visitingDefinitions)) {
						return true;
					}
				}
				continue;
			}
			if (isSubgraphType(node.typeId())) {
				SubgraphDefinition nested = subgraphs.get(subgraphIdFromTypeId(node.typeId()));
				if (nested != null && scopeContainsEventSource(nested.scope(), definitions, subgraphs, registry, portTypes, visitingDefinitions)) {
					return true;
				}
				continue;
			}
			NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
			if (nodeType != null && nodeType.kind() == NodeKind.EVENT_SOURCE) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsEventSource(
		GraphScope scope,
		Map<String, DocumentNodeDefinition> definitions,
		Map<String, SubgraphDefinition> subgraphs,
		NodeTypeRegistry registry,
		PortTypeRegistry portTypes,
		Set<String> visitingDefinitions
	) {
		return scopeContainsEventSource(scope, definitions, subgraphs, registry, portTypes, visitingDefinitions);
	}

	private static List<BoundaryEndpoint> sortedBoundaries(GraphScope scope, String typeId, PortDirection direction) {
		return scope.graph().nodes().stream()
			.filter(node -> typeId.equals(node.typeId()))
			.sorted(Comparator.comparing((NodeInstance node) -> scope.layout().nodePositions().getOrDefault(node.id(), new NodePosition(0, 0)).y())
				.thenComparing(node -> scope.layout().nodePositions().getOrDefault(node.id(), new NodePosition(0, 0)).x())
				.thenComparing(node -> node.id().value()))
			.map(node -> new BoundaryEndpoint(node.id(), direction, nameOf(node), inlineInputEnabledOf(node)))
			.toList();
	}

	private static boolean hasDuplicateNames(List<BoundaryEndpoint> ports) {
		Set<String> names = new LinkedHashSet<>();
		for (BoundaryEndpoint port : ports) {
			if (!names.add(port.name())) {
				return true;
			}
		}
		return false;
	}

	private static BoundaryAnalysis analyzeDataBoundaries(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		List<BoundaryEndpoint> inputs,
		List<BoundaryEndpoint> outputs
	) {
		Map<NodeId, BoundaryEndpoint> endpointsByNodeId = new LinkedHashMap<>();
		inputs.forEach(endpoint -> endpointsByNodeId.put(endpoint.nodeId(), endpoint));
		outputs.forEach(endpoint -> endpointsByNodeId.put(endpoint.nodeId(), endpoint));

		Map<NodeId, PortDefinition> inputPorts = new LinkedHashMap<>();
		Map<NodeId, PortDefinition> outputPorts = new LinkedHashMap<>();
		Set<NodeId> visitedBoundaries = new LinkedHashSet<>();

		for (BoundaryEndpoint endpoint : endpointsByNodeId.values()) {
			if (!visitedBoundaries.add(endpoint.nodeId())) {
				continue;
			}

			BoundaryComponent component = resolveBoundaryComponent(graph, registry, endpoint, endpointsByNodeId);
			if (component.error() != null) {
				return new BoundaryAnalysis(List.of(), List.of(), List.of(), component.error());
			}
			component.boundaryNodeIds().forEach(visitedBoundaries::add);

			String genericGroupId = component.concreteType() == null ? BOUNDARY_GROUP_PREFIX + component.boundaryNodeIds().getFirst().value() : null;
			for (NodeId boundaryNodeId : component.boundaryNodeIds()) {
				BoundaryEndpoint boundary = endpointsByNodeId.get(boundaryNodeId);
				if (boundary == null) {
					continue;
				}

				PortDefinition exposed = boundary.direction() == PortDirection.INPUT
					? component.concreteType() != null
						? PortDefinition.input(boundary.nodeId().value(), boundary.name(), component.concreteType(), false, inlineWidgetForBoundary(boundary, component))
						: component.numericFamily()
							? PortDefinition.input(boundary.nodeId().value(), boundary.name(), MCNGPortTypes.ANY, false, inlineWidgetForBoundary(boundary, component), genericGroupId, true)
							: PortDefinition.input(boundary.nodeId().value(), boundary.name(), MCNGPortTypes.ANY, false, inlineWidgetForBoundary(boundary, component), genericGroupId)
					: component.concreteType() != null
						? PortDefinition.output(boundary.nodeId().value(), boundary.name(), component.concreteType())
						: component.numericFamily()
							? PortDefinition.outputNumericGeneric(boundary.nodeId().value(), boundary.name(), genericGroupId)
							: PortDefinition.outputGeneric(boundary.nodeId().value(), boundary.name(), genericGroupId);
				if (boundary.direction() == PortDirection.INPUT) {
					inputPorts.put(boundary.nodeId(), exposed);
				} else {
					outputPorts.put(boundary.nodeId(), exposed);
				}
			}
		}

		return new BoundaryAnalysis(
			inputs.stream().map(endpoint -> inputPorts.get(endpoint.nodeId())).toList(),
			outputs.stream().map(endpoint -> outputPorts.get(endpoint.nodeId())).toList(),
			outputs.stream().map(endpoint -> new ExposedDataPort(outputPorts.get(endpoint.nodeId()), endpoint.nodeId())).toList(),
			null
		);
	}

	private static BoundaryComponent resolveBoundaryComponent(
		GraphDefinition graph,
		NodeTypeRegistry registry,
		BoundaryEndpoint start,
		Map<NodeId, BoundaryEndpoint> endpointsByNodeId
	) {
		Set<PortRef> visited = new LinkedHashSet<>();
		List<PortRef> queue = new ArrayList<>();
		Set<NodeId> boundaryNodeIds = new LinkedHashSet<>();
		PortType<?> concreteType = null;
		boolean numericFamily = false;
		queue.add(start.ref());

		for (int index = 0; index < queue.size(); index++) {
			PortRef current = queue.get(index);
			if (!visited.add(current)) {
				continue;
			}

			NodeInstance node = node(graph, current.nodeId());
			if (node == null) {
				continue;
			}
			PortDefinition definition = portDefinition(graph, registry, current);
			if (definition == null || definition.channel() != PortChannel.DATA) {
				continue;
			}

			if (endpointsByNodeId.containsKey(current.nodeId()) && isDataBoundaryRef(node.typeId(), current.direction())) {
				boundaryNodeIds.add(current.nodeId());
			}

			if (definition.genericGroupId() == null && !definition.type().equals(MCNGPortTypes.ANY)) {
				if (concreteType == null) {
					concreteType = definition.type();
				} else if (!concreteType.id().equals(definition.type().id())) {
					return new BoundaryComponent(List.of(), null, false, "Conflicting concrete boundary types");
				}
				continue;
			}

			if (definition.numericFamily()) {
				numericFamily = true;
			}

			if (definition.genericGroupId() != null) {
				NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
				if (nodeType != null) {
					for (PortDefinition sibling : nodeType.inputs(node)) {
						if (sibling.channel() == PortChannel.DATA && definition.genericGroupId().equals(sibling.genericGroupId())) {
							queue.add(new PortRef(node.id(), sibling.id(), PortDirection.INPUT));
						}
					}
					for (PortDefinition sibling : nodeType.outputs(node)) {
						if (sibling.channel() == PortChannel.DATA && definition.genericGroupId().equals(sibling.genericGroupId())) {
							queue.add(new PortRef(node.id(), sibling.id(), PortDirection.OUTPUT));
						}
					}
				}
			}

			for (EdgeDefinition edge : graph.edges()) {
				if (current.direction() == PortDirection.OUTPUT
					&& edge.fromNodeId().equals(current.nodeId())
					&& edge.fromPortId().equals(current.portId())) {
					PortDefinition target = portDefinition(graph, registry, new PortRef(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT));
					if (target != null && target.channel() == PortChannel.DATA) {
						queue.add(new PortRef(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT));
					}
				}
				if (current.direction() == PortDirection.INPUT
					&& edge.toNodeId().equals(current.nodeId())
					&& edge.toPortId().equals(current.portId())) {
					PortDefinition source = portDefinition(graph, registry, new PortRef(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT));
					if (source != null && source.channel() == PortChannel.DATA) {
						queue.add(new PortRef(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT));
					}
				}
			}
		}

		return new BoundaryComponent(boundaryNodeIds.stream().sorted(Comparator.comparing(NodeId::value)).toList(), concreteType, numericFamily, null);
	}

	private static boolean isDataBoundaryRef(String typeId, PortDirection direction) {
		return ((GRAPH_INPUT_TYPE_ID.equals(typeId) || SUBGRAPH_INPUT_TYPE_ID.equals(typeId)) && direction == PortDirection.OUTPUT)
			|| ((GRAPH_OUTPUT_TYPE_ID.equals(typeId) || SUBGRAPH_OUTPUT_TYPE_ID.equals(typeId)) && direction == PortDirection.INPUT);
	}

	private static NodeInstance node(GraphDefinition graph, NodeId nodeId) {
		return graph.nodes().stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElse(null);
	}

	private static PortDefinition portDefinition(GraphDefinition graph, NodeTypeRegistry registry, PortRef portRef) {
		NodeInstance node = node(graph, portRef.nodeId());
		if (node == null) {
			return null;
		}
		NodeType<?> nodeType = registry.find(node.typeId()).orElse(null);
		if (nodeType == null) {
			return null;
		}
		List<PortDefinition> ports = portRef.direction() == PortDirection.INPUT ? nodeType.inputs(node) : nodeType.outputs(node);
		return ports.stream().filter(port -> port.id().equals(portRef.portId())).findFirst().orElse(null);
	}

	private static String nameOf(NodeInstance node) {
		return readText(node.config(), PORT_NAME_KEY, "Port");
	}

	private static boolean inlineInputEnabledOf(NodeInstance node) {
		return readBoolean(node.config(), PORT_INLINE_INPUT_KEY, false);
	}

	private static String readText(JsonObject json, String key, String fallback) {
		if (json.has(key)) {
			try {
				return json.get(key).getAsString();
			} catch (RuntimeException exception) {
				return fallback;
			}
		}
		return fallback;
	}

	private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
		if (json.has(key)) {
			try {
				return json.get(key).getAsBoolean();
			} catch (RuntimeException exception) {
				return fallback;
			}
		}
		return fallback;
	}

	private static PortInlineWidget inlineWidgetForBoundary(BoundaryEndpoint boundary, BoundaryComponent component) {
		if (!boundary.inlineInputEnabled()) {
			return null;
		}
		if (component.concreteType() != null) {
			return inlineWidgetForType(component.concreteType());
		}
		if (component.numericFamily()) {
			return PortInlineWidget.numericText("0.0");
		}
		return null;
	}

	private static PortInlineWidget inlineWidgetForType(PortType<?> type) {
		if (MCNGPortTypes.INT.equals(type)) {
			return PortInlineWidget.numericText("0");
		}
		if (MCNGPortTypes.LONG.equals(type)) {
			return PortInlineWidget.numericText("0");
		}
		if (MCNGPortTypes.DOUBLE.equals(type)) {
			return PortInlineWidget.numericText("0.0");
		}
		if (MCNGPortTypes.STRING.equals(type)) {
			return PortInlineWidget.stringText("");
		}
		if (MCNGPortTypes.BOOLEAN.equals(type)) {
			return PortInlineWidget.booleanToggle(false);
		}
		return null;
	}

	public record DefinitionOutputMapping(PortDefinition definition, NodeId nodeId) {
	}

	public record DefinitionRuntimeInfo(
		String definitionId,
		String displayName,
		GraphScope scope,
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		List<DefinitionOutputMapping> exposedDataOutputs,
		String error
	) {
		public DefinitionRuntimeInfo {
			inputs = List.copyOf(inputs);
			outputs = List.copyOf(outputs);
			exposedDataOutputs = List.copyOf(exposedDataOutputs);
		}

		public boolean invalid() {
			return error != null;
		}
	}

	private record BoundaryFamily(
		String dataInputTypeId,
		String dataOutputTypeId,
		String controlInputTypeId,
		String controlOutputTypeId
	) {
	}

	private record BoundaryEndpoint(NodeId nodeId, PortDirection direction, String name, boolean inlineInputEnabled) {
		private PortRef ref() {
			return new PortRef(nodeId, VALUE_PORT, direction == PortDirection.INPUT ? PortDirection.OUTPUT : PortDirection.INPUT);
		}
	}

	private record BoundaryAnalysis(
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		List<ExposedDataPort> outputMappings,
		String error
	) {
	}

	private record BoundaryComponent(List<NodeId> boundaryNodeIds, PortType<?> concreteType, boolean numericFamily, String error) {
	}

	private record ExposedDataPort(PortDefinition definition, NodeId nodeId) {
	}

	private record PortRef(NodeId nodeId, PortId portId, PortDirection direction) {
	}

	private record ValidationResult(String error) {
	}

	private record ScopeMetadata(
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		List<ExposedDataPort> exposedDataOutputs,
		String error
	) {
		private static ScopeMetadata invalid(String error) {
			return new ScopeMetadata(List.of(), List.of(), List.of(), error);
		}

		private boolean invalid() {
			return error != null;
		}
	}

	private record DefinitionMetadata(
		List<PortDefinition> inputs,
		List<PortDefinition> outputs,
		List<ExposedDataPort> exposedDataOutputs,
		String error
	) {
		private static DefinitionMetadata invalid(String error) {
			return new DefinitionMetadata(List.of(), List.of(), List.of(), error);
		}

		private boolean invalid() {
			return error != null;
		}
	}

	private static final class HelperNodeType implements NodeType<JsonObject> {
		private final String id;
		private final String displayName;
		private final List<PortDefinition> inputs;
		private final List<PortDefinition> outputs;
		private final java.util.function.Function<NodeExecutionRequest<JsonObject>, NodeExecutionResult> executor;
		private final boolean supportsInlineInputExposure;

		private HelperNodeType(
			String id,
			String displayName,
			List<PortDefinition> inputs,
			List<PortDefinition> outputs,
			java.util.function.Function<NodeExecutionRequest<JsonObject>, NodeExecutionResult> executor,
			boolean supportsInlineInputExposure
		) {
			this.id = id;
			this.displayName = displayName;
			this.inputs = List.copyOf(inputs);
			this.outputs = List.copyOf(outputs);
			this.executor = executor;
			this.supportsInlineInputExposure = supportsInlineInputExposure;
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
			return NodeKind.COMPUTE;
		}

		@Override
		public NodeControlMode controlMode() {
			return NodeControlMode.NONE;
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
		public JsonObject defaultConfig() {
			JsonObject config = new JsonObject();
			config.addProperty(PORT_NAME_KEY, "Port");
			if (supportsInlineInputExposure) {
				config.addProperty(PORT_INLINE_INPUT_KEY, false);
			}
			return config;
		}

		@Override
		public NodeConfigCodec<JsonObject> configCodec() {
			return RAW_JSON_CODEC;
		}

		@Override
		public List<NodeEditorControl> editorControls() {
			if (supportsInlineInputExposure) {
				return List.of(PORT_NAME_CONTROL, PORT_INLINE_INPUT_CONTROL);
			}
			return List.of(PORT_NAME_CONTROL);
		}

		@Override
		public NodeExecutionResult execute(NodeExecutionRequest<JsonObject> request) {
			return executor.apply(request);
		}
	}

	private static final class DynamicDefinitionNodeType implements NodeType<JsonObject> {
		private final String definitionId;
		private final Map<String, DocumentNodeDefinition> definitions;
		private final Map<String, SubgraphDefinition> subgraphs;
		private final NodeTypeRegistry registry;
		private final PortTypeRegistry portTypes;
		private DefinitionMetadata metadata;

		private DynamicDefinitionNodeType(
			String definitionId,
			Map<String, DocumentNodeDefinition> definitions,
			Map<String, SubgraphDefinition> subgraphs,
			NodeTypeRegistry registry,
			PortTypeRegistry portTypes
		) {
			this.definitionId = definitionId;
			this.definitions = definitions;
			this.subgraphs = subgraphs;
			this.registry = registry;
			this.portTypes = portTypes;
		}

		private DocumentNodeDefinition definition() {
			return definitions.get(definitionId);
		}

		private DefinitionMetadata metadata() {
			if (metadata == null) {
				metadata = definitionMetadata(definition(), definitions, subgraphs, registry, portTypes, new LinkedHashSet<>());
			}
			return metadata;
		}

		@Override
		public String id() {
			return definitionTypeId(definitionId);
		}

		@Override
		public String displayName() {
			return definition().displayName();
		}

		@Override
		public NodeKind kind() {
			return NodeKind.COMPUTE;
		}

		@Override
		public NodeControlMode controlMode() {
			return NodeControlMode.NONE;
		}

		@Override
		public List<PortDefinition> inputs() {
			return metadata().inputs();
		}

		@Override
		public List<PortDefinition> outputs() {
			return metadata().outputs();
		}

		@Override
		public JsonObject defaultConfig() {
			return new JsonObject();
		}

		@Override
		public NodeConfigCodec<JsonObject> configCodec() {
			return RAW_JSON_CODEC;
		}

		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(new NodeEditorControl.TextControl(DEFINITION_NAME_CONTROL_KEY, "Name", definition().displayName()));
		}

		@Override
		public NodeExecutionResult execute(NodeExecutionRequest<JsonObject> request) {
			DefinitionMetadata metadata = metadata();
			if (metadata.invalid()) {
				return new NodeExecutionResult(
					Map.of(),
					List.of(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, metadata.error(), request.node().id(), null)),
					List.of()
				);
			}
			return NodeExecutionResult.of(Map.of());
		}
	}

	private static final class DynamicSubgraphNodeType implements NodeType<JsonObject> {
		private final String subgraphId;
		private final Map<String, DocumentNodeDefinition> definitions;
		private final Map<String, SubgraphDefinition> subgraphs;
		private final NodeTypeRegistry registry;
		private final PortTypeRegistry portTypes;
		private ScopeMetadata metadata;

		private DynamicSubgraphNodeType(
			String subgraphId,
			Map<String, DocumentNodeDefinition> definitions,
			Map<String, SubgraphDefinition> subgraphs,
			NodeTypeRegistry registry,
			PortTypeRegistry portTypes
		) {
			this.subgraphId = subgraphId;
			this.definitions = definitions;
			this.subgraphs = subgraphs;
			this.registry = registry;
			this.portTypes = portTypes;
		}

		private SubgraphDefinition subgraph() {
			return subgraphs.get(subgraphId);
		}

		private ScopeMetadata metadata() {
			if (metadata == null) {
				metadata = subgraphMetadata(subgraph(), definitions, subgraphs, registry, portTypes, new LinkedHashSet<>());
			}
			return metadata;
		}

		@Override
		public String id() {
			return subgraphTypeId(subgraphId);
		}

		@Override
		public String displayName() {
			return subgraph().displayName();
		}

		@Override
		public NodeKind kind() {
			return NodeKind.COMPUTE;
		}

		@Override
		public NodeControlMode controlMode() {
			return metadata().inputs().stream().anyMatch(port -> port.channel() == PortChannel.CONTROL) ? NodeControlMode.OPTIONAL : NodeControlMode.NONE;
		}

		@Override
		public List<PortDefinition> inputs() {
			return metadata().inputs();
		}

		@Override
		public List<PortDefinition> outputs() {
			return metadata().outputs();
		}

		@Override
		public JsonObject defaultConfig() {
			return new JsonObject();
		}

		@Override
		public NodeConfigCodec<JsonObject> configCodec() {
			return RAW_JSON_CODEC;
		}

		@Override
		public List<NodeEditorControl> editorControls() {
			return List.of(new NodeEditorControl.TextControl(DEFINITION_NAME_CONTROL_KEY, "Name", subgraph().displayName()));
		}

		@Override
		public NodeExecutionResult execute(NodeExecutionRequest<JsonObject> request) {
			ScopeMetadata metadata = metadata();
			if (metadata.invalid()) {
				return new NodeExecutionResult(
					Map.of(),
					List.of(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, metadata.error(), request.node().id(), null)),
					List.of()
				);
			}
			return NodeExecutionResult.of(Map.of());
		}
	}

	private static final class RawJsonCodec implements NodeConfigCodec<JsonObject> {
		@Override
		public JsonObject toJson(JsonObject config) {
			return config.deepCopy();
		}

		@Override
		public JsonObject fromJson(JsonObject json) {
			return json.deepCopy();
		}
	}
}
