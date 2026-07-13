package com.github.squi2rel.mcng.core;

import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public final class GraphExecutionSession {
	private final CompiledGraphDocument compiled;
	private final NodeExecutionContext context;
	private final RuntimeState runtimeState;
	private final ExecutionRuntime externalRuntime;
	private final ArrayDeque<FrameState> frames = new ArrayDeque<>();
	private final List<GraphError> finalErrors = new ArrayList<>();
	private final Map<NodeId, Map<PortId, Object>> finalOutputs = new LinkedHashMap<>();
	private final Map<NodeId, List<PortId>> finalActivatedControlOutputs = new LinkedHashMap<>();
	private final List<NodeId> finalVisitedNodes = new ArrayList<>();
	private final Set<NodeId> finalVisitedSet = new LinkedHashSet<>();

	private ExecutionSessionStatus status;
	private List<ExecutionPosition> frontier = List.of();
	private long nextFrameId = 1L;
	private int stateVersion;

	private GraphExecutionSession(
		CompiledGraphDocument compiled,
		NodeExecutionContext context,
		StartMode startMode,
		NodeId startNodeId,
		Object payload,
		ExecutionRuntime externalRuntime
	) {
		this.compiled = Objects.requireNonNull(compiled, "compiled");
		this.context = Objects.requireNonNull(context, "context");
		this.externalRuntime = externalRuntime;
		this.runtimeState = new RuntimeState(compiled.variables());
		if (!compiled.compileErrors().isEmpty()) {
			finalErrors.addAll(compiled.compileErrors());
			this.status = ExecutionSessionStatus.COMPLETED;
			return;
		}
		this.status = ExecutionSessionStatus.RUNNING;
		frames.addLast(new FrameState(
			nextFrameId++,
			compiled.rootPlan(),
			List.of(),
			List.of(),
			Map.of(),
			startMode,
			startNodeId,
			payload,
			null
		));
	}

	static GraphExecutionSession startDataflow(CompiledGraphDocument compiled, NodeExecutionContext context) {
		return new GraphExecutionSession(compiled, context, StartMode.DATAFLOW, null, null, null);
	}

	static GraphExecutionSession startEvent(CompiledGraphDocument compiled, NodeId sourceNodeId, Object payload, NodeExecutionContext context) {
		return new GraphExecutionSession(compiled, context, StartMode.EVENT, sourceNodeId, payload, null);
	}

	static GraphExecutionSession startControl(CompiledGraphDocument compiled, NodeId sourceNodeId, NodeExecutionContext context) {
		return new GraphExecutionSession(compiled, context, StartMode.CONTROL, sourceNodeId, null, null);
	}

	static GraphExecutionSession startDataflow(CompiledGraphDocument compiled, NodeExecutionContext context, ExecutionRuntime runtime) {
		return new GraphExecutionSession(compiled, context, StartMode.DATAFLOW, null, null, runtime);
	}

	static GraphExecutionSession startEvent(CompiledGraphDocument compiled, NodeId sourceNodeId, Object payload, NodeExecutionContext context, ExecutionRuntime runtime) {
		return new GraphExecutionSession(compiled, context, StartMode.EVENT, sourceNodeId, payload, runtime);
	}

	static GraphExecutionSession startControl(CompiledGraphDocument compiled, NodeId sourceNodeId, NodeExecutionContext context, ExecutionRuntime runtime) {
		return new GraphExecutionSession(compiled, context, StartMode.CONTROL, sourceNodeId, null, runtime);
	}

	public ExecutionSessionStatus status() {
		return status;
	}

	public ExecutionSnapshot snapshot() {
		if (!frames.isEmpty()) {
			FrameState root = frames.getFirst();
			return new ExecutionSnapshot(
				copyOutputs(root.outputs),
				Map.copyOf(root.activatedControlOutputs),
				List.copyOf(root.errors),
				List.copyOf(root.visitedNodes),
				frontier,
				status
			);
		}
		return new ExecutionSnapshot(copyCompletedOutputs(finalOutputs), finalActivatedControlOutputs, finalErrors, finalVisitedNodes, frontier, status);
	}

	public ExecutionSnapshot step(int maxNodeExecutions) {
		if (status != ExecutionSessionStatus.RUNNING) {
			return snapshot();
		}

		int remaining = Math.max(0, maxNodeExecutions);
		LinkedHashSet<ExecutionPosition> currentFrontier = new LinkedHashSet<>();
		while (status == ExecutionSessionStatus.RUNNING && remaining > 0) {
			if (frames.isEmpty()) {
				completeSession();
				break;
			}

			FrameState frame = frames.peekLast();
			if (!frame.initialized) {
				initializeFrame(frame);
				continue;
			}
			if (frame.tasks.isEmpty()) {
				finishFrame(frame);
				continue;
			}

			FrameTask task = frame.tasks.removeLast();
			if (task instanceof WhileResumeTask whileResumeTask) {
				frame.tasks.addLast(new NodeTask(whileResumeTask.nodeId(), Set.of(BuiltinNodeTypes.CONTROL_IN_PORT), ExecutionTrigger.control()));
				continue;
			}
			if (!(task instanceof NodeTask nodeTask)) {
				continue;
			}

			if (!processNodeTask(frame, nodeTask, currentFrontier)) {
				continue;
			}
			remaining--;
		}

		frontier = List.copyOf(currentFrontier);
		if (status == ExecutionSessionStatus.RUNNING && frames.isEmpty()) {
			completeSession();
		}
		return snapshot();
	}

	public ExecutionSnapshot runToCompletion() {
		while (status == ExecutionSessionStatus.RUNNING) {
			step(Integer.MAX_VALUE);
		}
		return snapshot();
	}

	public void cancel() {
		if (status != ExecutionSessionStatus.RUNNING) {
			return;
		}
		frames.clear();
		runtimeState.clearAll();
		frontier = List.of();
		status = ExecutionSessionStatus.CANCELLED;
	}

	private void initializeFrame(FrameState frame) {
		frame.initialized = true;
		switch (frame.startMode) {
			case DATAFLOW -> initializeDataflow(frame);
			case EVENT -> initializeEvent(frame);
			case CONTROL -> frame.tasks.addLast(new NodeTask(frame.startNodeId, Set.of(), ExecutionTrigger.control()));
		}
	}

	private void initializeDataflow(FrameState frame) {
		if (frame.plan.hasControlExecution()) {
			List<NodeId> roots = frame.plan.nodes().keySet().stream()
				.filter(nodeId -> shouldStartAsControlRoot(frame.plan, nodeId))
				.toList();
			for (int index = roots.size() - 1; index >= 0; index--) {
				frame.tasks.addLast(new NodeTask(roots.get(index), Set.of(), ExecutionTrigger.control()));
			}
			return;
		}

		List<NodeId> order = frame.plan.dataOrder();
		for (int index = order.size() - 1; index >= 0; index--) {
			frame.tasks.addLast(new NodeTask(order.get(index), Set.of(), ExecutionTrigger.fullEvaluation()));
		}
	}

	private void initializeEvent(FrameState frame) {
		if (frame.startNodeId == null) {
			frame.errors.add(GraphError.of(GraphErrorCode.INVALID_EVENT_SOURCE, "Missing event source node", null, null));
			return;
		}

		if (frame.plan.hasControlExecution()) {
			frame.tasks.addLast(new NodeTask(frame.startNodeId, Set.of(), ExecutionTrigger.event(frame.startNodeId, frame.payload)));
			return;
		}

		Set<NodeId> reachable = collectReachableNodes(frame.startNodeId, frame.plan.outgoingDataEdges());
		List<NodeId> order = frame.plan.dataOrder().stream().filter(reachable::contains).toList();
		for (int index = order.size() - 1; index >= 0; index--) {
			NodeId nodeId = order.get(index);
			frame.tasks.addLast(new NodeTask(
				nodeId,
				Set.of(),
				nodeId.equals(frame.startNodeId)
					? ExecutionTrigger.event(frame.startNodeId, frame.payload)
					: ExecutionTrigger.fullEvaluation()
			));
		}
	}

	private boolean processNodeTask(FrameState frame, NodeTask task, Set<ExecutionPosition> currentFrontier) {
		NodeId nodeId = task.nodeId();
		NodeInstance node = frame.plan.nodes().get(nodeId);
		NodeType<?> nodeType = frame.plan.nodeTypes().get(nodeId);
		if (node == null || nodeType == null) {
			return false;
		}
		if (nodeType.kind() == NodeKind.EVENT_SOURCE && task.trigger().kind() != ExecutionTriggerKind.EVENT) {
			return false;
		}

			List<NodeId> prerequisites = missingDataPrerequisites(frame, nodeId, nodeType, task);
		if (!prerequisites.isEmpty()) {
			frame.tasks.addLast(task);
			for (int index = prerequisites.size() - 1; index >= 0; index--) {
				frame.tasks.addLast(new NodeTask(prerequisites.get(index), Set.of(), ExecutionTrigger.fullEvaluation()));
			}
			return false;
		}

		InputCollection inputCollection = collectDataInputs(frame, nodeId, nodeType, task);
		Collection<PortDefinition> missingInputs = requiredMissingInputs(nodeType.inputs(node), inputCollection);
		Collection<PortDefinition> failedInputs = failedRequiredInputs(nodeType.inputs(node), inputCollection);
		if ((!missingInputs.isEmpty() || !failedInputs.isEmpty()) && task.trigger().kind() != ExecutionTriggerKind.EVENT) {
			for (PortDefinition missingInput : missingInputs) {
				frame.errors.add(GraphError.of(
					GraphErrorCode.MISSING_REQUIRED_INPUT,
					"Missing required input " + missingInput.id() + " for node " + nodeType.id(),
					nodeId,
					missingInput.id()
				));
			}
			return false;
		}

		recordVisited(frame, nodeId);
		currentFrontier.add(positionOf(frame, nodeId));

		if (DocumentNodeTypes.isDefinitionType(node.typeId())) {
			startDefinitionFrame(frame, node, task, inputCollection.values());
			return true;
		}

		try {
			NodeExecutionResult result = executeNode(frame, node, nodeType, inputCollection.values(), task);
			storeOutputs(frame, nodeId, result.outputs());
			if (!result.activatedControlOutputs().isEmpty()) {
				frame.activatedControlOutputs.put(nodeId, result.activatedControlOutputs());
			}
			frame.errors.addAll(result.errors());
			scheduleControlOutputs(frame, nodeId, result.activatedControlOutputs());
		} catch (RuntimeValueConstraintException exception) {
			frame.errors.add(GraphError.of(GraphErrorCode.VALUE_LIMIT_EXCEEDED, exception.getMessage(), nodeId, null));
		} catch (RuntimeException exception) {
			frame.errors.add(GraphError.of(GraphErrorCode.NODE_EXECUTION_FAILED, exception.getMessage(), nodeId, null));
		}
		return true;
	}

	private NodeExecutionResult executeNode(FrameState frame, NodeInstance node, NodeType<?> nodeType, Map<PortId, Object> inputs, NodeTask task) {
		ScopedRuntime runtime = new ScopedRuntime(frame.id);
		Map<PortId, PortType<?>> outputTypes = new LinkedHashMap<>();
		for (PortDefinition output : nodeType.outputs(node)) {
			if (output.channel() != PortChannel.DATA) {
				continue;
			}
			outputTypes.put(output.id(), GraphPortTypeResolver.resolve(frame.plan.graph(), compiled.resolvedRegistry(), node.id(), output.id(), PortDirection.OUTPUT, compiled::variableType).effectiveType());
		}

		@SuppressWarnings("unchecked")
		NodeType<Object> typed = (NodeType<Object>) nodeType;
		Object config = typed.configCodec().fromJson(node.config());
		return typed.execute(new NodeExecutionRequest<>(
			node,
			config,
			inputs,
			outputTypes,
			frame.plan.connectedControlInputs().getOrDefault(node.id(), Set.of()),
			task.activeControlInputs(),
			() -> Map.copyOf(inputs),
			task.trigger(),
			runtime,
			compiled.portTypes(),
			compiled.resolvedRegistry(),
			new ScopedContext(frame.graphInputs)
		));
	}

	private void startDefinitionFrame(FrameState parent, NodeInstance node, NodeTask task, Map<PortId, Object> inputs) {
		String definitionId = DocumentNodeTypes.definitionIdFromTypeId(node.typeId());
		CompiledGraphDocument.DefinitionPlan definitionPlan = compiled.definitions().get(definitionId);
		if (definitionPlan == null) {
			parent.errors.add(GraphError.of(GraphErrorCode.INVALID_DOCUMENT_DEFINITION, "Unknown definition: " + definitionId, node.id(), null));
			return;
		}

		DocumentNodeTypes.DefinitionRuntimeInfo info = definitionPlan.info();
		Map<NodeId, Object> graphInputs = new LinkedHashMap<>();
		for (PortDefinition input : info.inputs()) {
			if (input.channel() != PortChannel.DATA) {
				continue;
			}
			if (inputs.containsKey(input.id())) {
				graphInputs.put(new NodeId(input.id().value()), inputs.get(input.id()));
			}
		}

		List<String> definitionPath = new ArrayList<>(parent.definitionPath);
		definitionPath.add(definitionId);
		List<NodeId> invocationPath = new ArrayList<>(parent.invocationPath);
		invocationPath.add(node.id());
		frames.addLast(new FrameState(
			nextFrameId++,
			definitionPlan.graphPlan(),
			definitionPath,
			invocationPath,
			graphInputs,
			StartMode.DATAFLOW,
			null,
			null,
			new ParentLink(parent, node.id(), definitionPlan)
		));
	}

	private void finishFrame(FrameState frame) {
		frames.removeLast();
		if (frame.parentLink == null) {
			finalizeRoot(frame);
			runtimeState.releaseFrame(frame.id);
			completeSession();
			return;
		}

		ParentLink parentLink = frame.parentLink;
		FrameState parent = parentLink.parent();
		DocumentNodeTypes.DefinitionRuntimeInfo info = parentLink.definitionPlan().info();

		Map<PortId, Object> outputs = new LinkedHashMap<>();
		for (DocumentNodeTypes.DefinitionOutputMapping outputMapping : info.exposedDataOutputs()) {
			OutputEntry nodeOutputs = frame.outputs.get(outputMapping.nodeId());
			if (nodeOutputs != null && nodeOutputs.values().containsKey(new PortId("value"))) {
				outputs.put(outputMapping.definition().id(), nodeOutputs.values().get(new PortId("value")));
			}
		}
		storeOutputs(parent, parentLink.parentNodeId(), outputs);
		for (GraphError error : frame.errors) {
			parent.errors.add(GraphError.of(
				error.code(),
				"Inside " + info.displayName() + ": " + error.message(),
				parentLink.parentNodeId(),
				null
			));
		}
		runtimeState.releaseFrame(frame.id);
	}

	private void finalizeRoot(FrameState root) {
		finalOutputs.clear();
		finalOutputs.putAll(copyOutputs(root.outputs));
		finalActivatedControlOutputs.clear();
		finalActivatedControlOutputs.putAll(root.activatedControlOutputs);
		finalErrors.clear();
		finalErrors.addAll(root.errors);
		finalVisitedNodes.clear();
		finalVisitedNodes.addAll(root.visitedNodes);
		finalVisitedSet.clear();
		finalVisitedSet.addAll(root.visitedSet);
	}

	private void completeSession() {
		if (status == ExecutionSessionStatus.RUNNING) {
			status = ExecutionSessionStatus.COMPLETED;
		}
		if (status != ExecutionSessionStatus.RUNNING) {
			runtimeState.clearAll();
			frontier = List.of();
		}
	}

	private void scheduleControlOutputs(FrameState frame, NodeId nodeId, List<PortId> activatedOutputs) {
		if (activatedOutputs.isEmpty()) {
			return;
		}

		boolean whileBody = BuiltinNodeTypes.WHILE.id().equals(frame.plan.nodeTypes().get(nodeId).id())
			&& activatedOutputs.contains(BuiltinNodeTypes.BODY_PORT);
		if (whileBody) {
			frame.tasks.addLast(new WhileResumeTask(nodeId));
		}

		for (int portIndex = activatedOutputs.size() - 1; portIndex >= 0; portIndex--) {
			PortId outputPortId = activatedOutputs.get(portIndex);
			List<EdgeDefinition> outgoing = frame.plan.outgoingControlEdges().getOrDefault(nodeId, List.of());
			for (int edgeIndex = outgoing.size() - 1; edgeIndex >= 0; edgeIndex--) {
				EdgeDefinition edge = outgoing.get(edgeIndex);
				if (!edge.fromPortId().equals(outputPortId)) {
					continue;
				}
				frame.tasks.addLast(new NodeTask(edge.toNodeId(), Set.of(edge.toPortId()), ExecutionTrigger.control()));
			}
		}
	}

	private List<NodeId> missingDataPrerequisites(FrameState frame, NodeId nodeId, NodeType<?> nodeType, NodeTask task) {
		LinkedHashSet<NodeId> prerequisites = new LinkedHashSet<>();
		collectMissingDataPrerequisites(frame, nodeId, nodeType, task, prerequisites, new LinkedHashSet<>());
		List<NodeId> ordered = new ArrayList<>(prerequisites);
		ordered.sort((left, right) -> Integer.compare(
			frame.plan.dataOrderIndex().getOrDefault(left, Integer.MAX_VALUE),
			frame.plan.dataOrderIndex().getOrDefault(right, Integer.MAX_VALUE)
		));
		return ordered;
	}

	private void collectMissingDataPrerequisites(
		FrameState frame,
		NodeId nodeId,
		NodeType<?> nodeType,
		NodeTask task,
		Set<NodeId> prerequisites,
		Set<NodeId> visiting
	) {
		for (EdgeDefinition edge : relevantDataEdges(frame, nodeId, nodeType, task)) {
			NodeId sourceNodeId = edge.fromNodeId();
			if (outputReady(frame, sourceNodeId, edge.fromPortId())) {
				continue;
			}

			NodeType<?> sourceType = frame.plan.nodeTypes().get(sourceNodeId);
			if (sourceType == null || sourceType.kind() == NodeKind.EVENT_SOURCE) {
				continue;
			}
			if (!visiting.add(sourceNodeId)) {
				continue;
			}
			collectMissingDataPrerequisites(frame, sourceNodeId, frame.plan.nodeTypes().get(sourceNodeId), new NodeTask(sourceNodeId, Set.of(), ExecutionTrigger.fullEvaluation()), prerequisites, visiting);
			prerequisites.add(sourceNodeId);
		}
	}

	private boolean outputReady(FrameState frame, NodeId nodeId, PortId portId) {
		OutputEntry entry = frame.outputs.get(nodeId);
		if (entry == null) {
			return false;
		}
		if (!entry.values().containsKey(portId)) {
			return false;
		}
		return frame.plan.persistentNodes().contains(nodeId) || entry.version == stateVersion;
	}

	private InputCollection collectDataInputs(FrameState frame, NodeId nodeId, NodeType<?> nodeType, NodeTask task) {
		NodeInstance node = frame.plan.nodes().get(nodeId);
		Map<PortId, Object> inputs = new LinkedHashMap<>();
		Set<PortId> failedPorts = new LinkedHashSet<>();
		Set<PortId> connectedPorts = new LinkedHashSet<>();

		for (EdgeDefinition edge : relevantDataEdges(frame, nodeId, nodeType, task)) {
			connectedPorts.add(edge.toPortId());
			OutputEntry sourceOutputs = frame.outputs.get(edge.fromNodeId());
			if (sourceOutputs == null || !sourceOutputs.values().containsKey(edge.fromPortId())) {
				continue;
			}

			try {
				ResolvedPortType sourceResolution = GraphPortTypeResolver.resolve(frame.plan.graph(), compiled.resolvedRegistry(), edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT, compiled::variableType);
				ResolvedPortType targetResolution = GraphPortTypeResolver.resolve(frame.plan.graph(), compiled.resolvedRegistry(), nodeId, edge.toPortId(), PortDirection.INPUT, compiled::variableType);
				Object sourceValue = sourceOutputs.values().get(edge.fromPortId());
				if (targetResolution.unresolvedNumeric()) {
					inputs.put(edge.toPortId(), copyRuntimeValue(sourceValue));
				} else {
					inputs.put(edge.toPortId(), copyRuntimeValue(compiled.portTypes().convert(sourceValue, sourceResolution.effectiveType(), targetResolution.effectiveType())));
				}
			} catch (RuntimeValueConstraintException exception) {
				failedPorts.add(edge.toPortId());
				frame.errors.add(GraphError.of(GraphErrorCode.VALUE_LIMIT_EXCEEDED, exception.getMessage(), nodeId, edge.toPortId()));
			} catch (IllegalArgumentException exception) {
				failedPorts.add(edge.toPortId());
				frame.errors.add(GraphError.of(
					GraphErrorCode.CONVERSION_FAILED,
					"Failed to convert "
						+ GraphPortTypeResolver.resolve(frame.plan.graph(), compiled.resolvedRegistry(), edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT, compiled::variableType).effectiveType()
						+ " to "
						+ GraphPortTypeResolver.resolve(frame.plan.graph(), compiled.resolvedRegistry(), nodeId, edge.toPortId(), PortDirection.INPUT, compiled::variableType).effectiveType()
						+ ": "
						+ exception.getMessage(),
					nodeId,
					edge.toPortId()
				));
			}
		}

		if (nodeType != null && node != null) {
			for (PortDefinition port : nodeType.inputs(node)) {
				if (port.channel() != PortChannel.DATA) {
					continue;
				}
				if (inputs.containsKey(port.id()) || failedPorts.contains(port.id()) || connectedPorts.contains(port.id()) || port.inlineWidget() == null) {
					continue;
				}
				try {
					inputs.put(port.id(), NodeConfigValues.readInlineInputValue(node.config(), port));
				} catch (IllegalArgumentException exception) {
					failedPorts.add(port.id());
					frame.errors.add(GraphError.of(GraphErrorCode.INVALID_INLINE_INPUT, exception.getMessage(), nodeId, port.id()));
				}
			}
		}

		return new InputCollection(inputs, failedPorts);
	}

	private List<EdgeDefinition> relevantDataEdges(FrameState frame, NodeId nodeId, NodeType<?> nodeType, NodeTask task) {
		List<EdgeDefinition> incoming = frame.plan.incomingDataEdges().getOrDefault(nodeId, List.of());
		if (nodeType == null || !BuiltinNodeTypes.LATCH.id().equals(nodeType.id())) {
			return incoming;
		}
		if (task.trigger().kind() != ExecutionTriggerKind.CONTROL) {
			return List.of();
		}

		Set<PortId> relevantPorts = new LinkedHashSet<>();
		if (task.activeControlInputs().contains(BuiltinNodeTypes.STORE_A_PORT)) {
			relevantPorts.add(BuiltinNodeTypes.VALUE_A_PORT);
		}
		if (task.activeControlInputs().contains(BuiltinNodeTypes.STORE_B_PORT)) {
			relevantPorts.add(BuiltinNodeTypes.VALUE_B_PORT);
		}
		if (relevantPorts.isEmpty()) {
			return List.of();
		}
		return incoming.stream()
			.filter(edge -> relevantPorts.contains(edge.toPortId()))
			.toList();
	}

	private Collection<PortDefinition> requiredMissingInputs(List<PortDefinition> inputs, InputCollection inputCollection) {
		return inputs.stream()
			.filter(port -> port.channel() == PortChannel.DATA)
			.filter(PortDefinition::required)
			.filter(port -> !inputCollection.values().containsKey(port.id()))
			.filter(port -> !inputCollection.failedPorts().contains(port.id()))
			.toList();
	}

	private Collection<PortDefinition> failedRequiredInputs(List<PortDefinition> inputs, InputCollection inputCollection) {
		return inputs.stream()
			.filter(port -> port.channel() == PortChannel.DATA)
			.filter(PortDefinition::required)
			.filter(port -> inputCollection.failedPorts().contains(port.id()))
			.toList();
	}

	private void storeOutputs(FrameState frame, NodeId nodeId, Map<PortId, Object> outputs) {
		frame.outputs.put(nodeId, new OutputEntry(sanitizeOutputValues(outputs), stateVersion));
	}

	private void recordVisited(FrameState frame, NodeId nodeId) {
		if (frame.visitedSet.add(nodeId)) {
			frame.visitedNodes.add(nodeId);
		}
	}

	private ExecutionPosition positionOf(FrameState frame, NodeId nodeId) {
		List<String> definitionPath = new ArrayList<>(frame.definitionPath);
		definitionPath.addAll(frame.plan.scopePath(nodeId));
		return new ExecutionPosition(definitionPath, frame.invocationPath, nodeId);
	}

	private boolean shouldStartAsControlRoot(CompiledGraphDocument.CompiledGraphPlan plan, NodeId nodeId) {
		NodeType<?> nodeType = plan.nodeTypes().get(nodeId);
		if (nodeType == null || nodeType.kind() == NodeKind.EVENT_SOURCE) {
			return false;
		}
		if (!plan.incomingControlEdges().getOrDefault(nodeId, List.of()).isEmpty()) {
			return false;
		}
		return nodeType.controlMode() == NodeControlMode.REQUIRED || !plan.outgoingControlEdges().getOrDefault(nodeId, List.of()).isEmpty();
	}

	private static Set<NodeId> collectReachableNodes(NodeId sourceNodeId, Map<NodeId, List<EdgeDefinition>> outgoingEdges) {
		Set<NodeId> reachable = new LinkedHashSet<>();
		ArrayDeque<NodeId> queue = new ArrayDeque<>();
		queue.add(sourceNodeId);

		while (!queue.isEmpty()) {
			NodeId current = queue.removeFirst();
			if (!reachable.add(current)) {
				continue;
			}

			for (EdgeDefinition edge : outgoingEdges.getOrDefault(current, List.of())) {
				queue.add(edge.toNodeId());
			}
		}
		return reachable;
	}

	private Map<NodeId, Map<PortId, Object>> copyOutputs(Map<NodeId, OutputEntry> outputs) {
		Map<NodeId, Map<PortId, Object>> copy = new LinkedHashMap<>();
		for (Map.Entry<NodeId, OutputEntry> entry : outputs.entrySet()) {
			copy.put(entry.getKey(), copyOutputValues(entry.getValue().values()));
		}
		return Map.copyOf(copy);
	}

	private Map<NodeId, Map<PortId, Object>> copyCompletedOutputs(Map<NodeId, Map<PortId, Object>> outputs) {
		Map<NodeId, Map<PortId, Object>> copy = new LinkedHashMap<>();
		for (Map.Entry<NodeId, Map<PortId, Object>> entry : outputs.entrySet()) {
			copy.put(entry.getKey(), copyOutputValues(entry.getValue()));
		}
		return Map.copyOf(copy);
	}

	private Map<PortId, Object> sanitizeOutputValues(Map<PortId, Object> outputs) {
		Map<PortId, Object> sanitized = new LinkedHashMap<>();
		for (Map.Entry<PortId, Object> entry : outputs.entrySet()) {
			sanitized.put(entry.getKey(), sanitizeRuntimeValue(entry.getValue()));
		}
		return Map.copyOf(sanitized);
	}

	private Map<PortId, Object> copyOutputValues(Map<PortId, Object> outputs) {
		Map<PortId, Object> copy = new LinkedHashMap<>();
		for (Map.Entry<PortId, Object> entry : outputs.entrySet()) {
			copy.put(entry.getKey(), copyRuntimeValue(entry.getValue()));
		}
		return Map.copyOf(copy);
	}

	private Object sanitizeRuntimeValue(Object value) {
		try {
			return RuntimeValueUtils.copyWithinLimits(value, compiled.options().valueLimits());
		} catch (IllegalArgumentException exception) {
			throw new RuntimeValueConstraintException(exception.getMessage(), exception);
		}
	}

	private Object copyRuntimeValue(Object value) {
		try {
			return RuntimeValueUtils.copyIfContainer(value);
		} catch (IllegalArgumentException exception) {
			throw new RuntimeValueConstraintException(exception.getMessage(), exception);
		}
	}

	private enum StartMode {
		DATAFLOW,
		EVENT,
		CONTROL
	}

	private sealed interface FrameTask permits NodeTask, WhileResumeTask {
	}

	private record NodeTask(NodeId nodeId, Set<PortId> activeControlInputs, ExecutionTrigger trigger) implements FrameTask {
		private NodeTask {
			activeControlInputs = Set.copyOf(activeControlInputs);
		}
	}

	private record WhileResumeTask(NodeId nodeId) implements FrameTask {
	}

	private record InputCollection(Map<PortId, Object> values, Set<PortId> failedPorts) {
	}

	private record OutputEntry(Map<PortId, Object> values, int version) {
	}

	private record ParentLink(FrameState parent, NodeId parentNodeId, CompiledGraphDocument.DefinitionPlan definitionPlan) {
	}

	private final class FrameState {
		private final long id;
		private final CompiledGraphDocument.CompiledGraphPlan plan;
		private final List<String> definitionPath;
		private final List<NodeId> invocationPath;
		private final Map<NodeId, Object> graphInputs;
		private final StartMode startMode;
		private final NodeId startNodeId;
		private final Object payload;
		private final ParentLink parentLink;
		private final ArrayDeque<FrameTask> tasks = new ArrayDeque<>();
		private final Map<NodeId, OutputEntry> outputs = new LinkedHashMap<>();
		private final Map<NodeId, List<PortId>> activatedControlOutputs = new LinkedHashMap<>();
		private final List<GraphError> errors = new ArrayList<>();
		private final List<NodeId> visitedNodes = new ArrayList<>();
		private final Set<NodeId> visitedSet = new LinkedHashSet<>();
		private boolean initialized;

		private FrameState(
			long id,
			CompiledGraphDocument.CompiledGraphPlan plan,
			List<String> definitionPath,
			List<NodeId> invocationPath,
			Map<NodeId, Object> graphInputs,
			StartMode startMode,
			NodeId startNodeId,
			Object payload,
			ParentLink parentLink
		) {
			this.id = id;
			this.plan = plan;
			this.definitionPath = List.copyOf(definitionPath);
			this.invocationPath = List.copyOf(invocationPath);
			this.graphInputs = Map.copyOf(graphInputs);
			this.startMode = startMode;
			this.startNodeId = startNodeId;
			this.payload = payload;
			this.parentLink = parentLink;
		}
	}

	private final class RuntimeState {
		private final Map<String, VariableEntry> variableEntries = new LinkedHashMap<>();
		private final Map<String, Object> variableValues = new LinkedHashMap<>();
		private final Map<VariableStateKey, Object> frameVariableValues = new LinkedHashMap<>();
		private final Map<StateKey, Object> localState = new LinkedHashMap<>();

		private RuntimeState(List<GraphVariableDefinition> variables) {
			for (GraphVariableDefinition variable : variables) {
				PortType<?> type = compiled.portTypes().findType(variable.typeId())
					.orElseThrow(() -> new IllegalArgumentException("Unknown variable type: " + variable.typeId()));
				variableEntries.put(variable.id(), new VariableEntry(variable, type));
				if (compiled.variableStorage(variable.id()) == DocumentGraphFlattener.VariableStorage.GLOBAL) {
					variableValues.put(variable.id(), readDefaultValue(variable, type));
				}
			}
		}

		private Object readDefaultValue(GraphVariableDefinition variable, PortType<?> type) {
			return sanitizeRuntimeValue(RuntimeValueUtils.fromJsonValue(variable.defaultValue(), type, compiled.portTypes()));
		}

		private void releaseFrame(long frameId) {
			frameVariableValues.keySet().removeIf(key -> key.frameId() == frameId);
			localState.keySet().removeIf(key -> key.frameId() == frameId);
		}

		private void clearAll() {
			variableValues.clear();
			frameVariableValues.clear();
			localState.clear();
		}
	}

	private final class ScopedRuntime implements ExecutionRuntime {
		private final long frameId;

		private ScopedRuntime(long frameId) {
			this.frameId = frameId;
		}

		@Override
		public boolean hasVariable(String id) {
			if (externalRuntime != null && compiled.variableStorage(id) == DocumentGraphFlattener.VariableStorage.GLOBAL) {
				return externalRuntime.hasVariable(id);
			}
			return runtimeState.variableEntries.containsKey(id);
		}

		@Override
		public PortType<?> variableType(String id) {
			VariableEntry entry = runtimeState.variableEntries.get(id);
			return entry != null ? entry.type() : null;
		}

		@Override
		public Object readVariable(String id) {
			DocumentGraphFlattener.VariableStorage storage = compiled.variableStorage(id);
			if (externalRuntime != null && storage == DocumentGraphFlattener.VariableStorage.GLOBAL) {
				return sanitizeRuntimeValue(externalRuntime.readVariable(id));
			}
			VariableEntry entry = runtimeState.variableEntries.get(id);
			if (entry == null) {
				return null;
			}
			if (storage == DocumentGraphFlattener.VariableStorage.FRAME_LOCAL) {
				VariableStateKey key = new VariableStateKey(frameId, id);
				return copyRuntimeValue(runtimeState.frameVariableValues.computeIfAbsent(key, ignored -> runtimeState.readDefaultValue(entry.definition(), entry.type())));
			}
			return copyRuntimeValue(runtimeState.variableValues.get(id));
		}

		@Override
		public void writeVariable(String id, Object value) {
			DocumentGraphFlattener.VariableStorage storage = compiled.variableStorage(id);
			Object storedValue = sanitizeRuntimeValue(value);
			if (externalRuntime != null && storage == DocumentGraphFlattener.VariableStorage.GLOBAL) {
				externalRuntime.writeVariable(id, storedValue);
				stateVersion++;
				return;
			}
			VariableEntry entry = runtimeState.variableEntries.get(id);
			if (entry == null) {
				throw new IllegalArgumentException("Unknown variable: " + id);
			}
			if (storedValue != null && !entry.type().accepts(storedValue)) {
				throw new IllegalArgumentException("Invalid value for variable " + id + ": " + storedValue.getClass().getSimpleName());
			}
			if (storage == DocumentGraphFlattener.VariableStorage.FRAME_LOCAL) {
				runtimeState.frameVariableValues.put(new VariableStateKey(frameId, id), storedValue);
			} else {
				runtimeState.variableValues.put(id, storedValue);
			}
			stateVersion++;
		}

		@Override
		public Object readLocalState(NodeId nodeId, String slot) {
			if (externalRuntime != null) {
				return sanitizeRuntimeValue(externalRuntime.readLocalState(nodeId, slot));
			}
			return copyRuntimeValue(runtimeState.localState.get(new StateKey(frameId, nodeId, slot)));
		}

		@Override
		public void writeLocalState(NodeId nodeId, String slot, Object value) {
			Object storedValue = sanitizeRuntimeValue(value);
			if (externalRuntime != null) {
				externalRuntime.writeLocalState(nodeId, slot, storedValue);
				stateVersion++;
				return;
			}
			runtimeState.localState.put(new StateKey(frameId, nodeId, slot), storedValue);
			stateVersion++;
		}

		@Override
		public void consumeControlStep() {
			if (externalRuntime != null) {
				externalRuntime.consumeControlStep();
			}
		}

		@Override
		public void consumeLoopIteration(NodeId nodeId) {
			if (externalRuntime != null) {
				externalRuntime.consumeLoopIteration(nodeId);
			}
		}
	}

	private final class ScopedContext implements NodeExecutionContext {
		private final Map<NodeId, Object> graphInputs;

		private ScopedContext(Map<NodeId, Object> graphInputs) {
			this.graphInputs = graphInputs;
		}

		@Override
		public void publishDebug(NodeId nodeId, String message) {
			context.publishDebug(nodeId, message);
		}

		@Override
		public Object resolveGraphInput(NodeId nodeId) {
			if (graphInputs.containsKey(nodeId)) {
				return copyRuntimeValue(graphInputs.get(nodeId));
			}
			return sanitizeRuntimeValue(context.resolveGraphInput(nodeId));
		}
	}

	private record VariableEntry(GraphVariableDefinition definition, PortType<?> type) {
	}

	private record StateKey(long frameId, NodeId nodeId, String slot) {
	}

	private record VariableStateKey(long frameId, String variableId) {
	}
}
