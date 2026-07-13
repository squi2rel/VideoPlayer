package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeDefinition;
import com.github.squi2rel.mcng.core.DocumentNodeDefinitionKind;
import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.CompiledGraphDocument;
import com.github.squi2rel.mcng.core.DynamicPortTypeResolverContext;
import com.github.squi2rel.mcng.core.EdgeDefinition;
import com.github.squi2rel.mcng.core.ExecutionPosition;
import com.github.squi2rel.mcng.core.ExecutionResult;
import com.github.squi2rel.mcng.core.ExecutionSessionStatus;
import com.github.squi2rel.mcng.core.ExecutionSnapshot;
import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphError;
import com.github.squi2rel.mcng.core.GraphExecutor;
import com.github.squi2rel.mcng.core.GraphExecutionSession;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.GraphPortTypeResolver;
import com.github.squi2rel.mcng.core.GraphScope;
import com.github.squi2rel.mcng.core.GraphVariableDefinition;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodeKind;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeSize;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.NumericTypes;
import com.github.squi2rel.mcng.core.PortChannel;
import com.github.squi2rel.mcng.core.PortDefinition;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortType;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.ResolvedPortType;
import com.github.squi2rel.mcng.core.SubgraphDefinition;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GraphEditorSession {
	private static final String REROUTE_ORIENTATION_KEY = "__rerouteOrientation";
	private static final String LEGACY_REROUTE_FLIPPED_KEY = "__rerouteFlipped";
	private static final String LEGACY_REROUTE_VERTICAL_KEY = "__rerouteVertical";
	private static final String FLAT_NODE_PREFIX = "$flat$";
	private static final NodeSize DEFAULT_SELECTION_NODE_SIZE = new NodeSize(196, 96);
	private static final int MAX_HISTORY_ENTRIES = 128;

	private final NodeTypeRegistry baseRegistry;
	private final PortTypeRegistry portTypes;
	private final GraphJsonCodec codec;
	private final GraphEditorHost host;
	private final MutableGraphState root = new MutableGraphState();
	private final Map<String, DefinitionState> definitions = new LinkedHashMap<>();
	private final Map<String, ParentScopeRef> definitionParents = new LinkedHashMap<>();
	private final Map<String, GraphVariableDefinition> rootVariables = new LinkedHashMap<>();
	private final List<String> contextPath = new ArrayList<>();
	private final List<NodeId> contextNodePath = new ArrayList<>();
	private final List<String> debugMessages = new ArrayList<>();
	private final Set<NodeId> selectedNodeIds = new LinkedHashSet<>();
	private final Deque<HistoryEntry> undoStack = new ArrayDeque<>();
	private final Deque<HistoryEntry> redoStack = new ArrayDeque<>();

	private LocalClipboard localClipboard;
	private List<GraphError> lastErrors = List.of();
	private PendingConnection pendingConnection;
	private NodeId primarySelectedNodeId;
	private long triggerCounter = 1L;
	private NodeTypeRegistry resolvedRegistry;
	private CompiledGraphDocument compiledDocument;
	private GraphExecutionSession runningExecution;
	private ExecutionSnapshot lastExecutionSnapshot = emptySnapshot();
	private Set<NodeId> executingVisibleNodeIds = Set.of();
	private Set<NodeId> executingVisibleRerouteNodeIds = Set.of();
	private int compositeEditDepth;
	private EditorSnapshot compositeEditStart;
	private EditorSnapshot compositeEditLatest;
	private boolean compositeEditChanged;

	public GraphEditorSession(NodeTypeRegistry registry, PortTypeRegistry portTypes, GraphJsonCodec codec, GraphDocument document, GraphEditorHost host) {
		this.baseRegistry = Objects.requireNonNull(registry, "registry");
		this.portTypes = Objects.requireNonNull(portTypes, "portTypes");
		this.codec = Objects.requireNonNull(codec, "codec");
		this.host = Objects.requireNonNull(host, "host");
		load(document);
	}

	public void load(GraphDocument document) {
		Objects.requireNonNull(document, "document");
		finishOpenCompositeEdit();
		loadDocumentState(document);
		resetForLoadedDocument(true);
		clearHistoryState();
		refreshRuntime(document);
	}

	public boolean replaceDocument(GraphDocument document) {
		Objects.requireNonNull(document, "document");
		finishOpenCompositeEdit();
		EditorSnapshot before = captureSnapshot();
		loadDocumentState(document);
		resetForLoadedDocument(false);
		GraphDocument after = this.document();
		if (before.matches(after, contextPath, contextNodePath, selectedNodeIds, primarySelectedNodeId)) {
			return false;
		}
		pushHistory(undoStack, new HistoryEntry(before, captureSnapshot(after)));
		redoStack.clear();
		persist(after);
		return true;
	}

	public boolean canUndo() {
		return !undoStack.isEmpty();
	}

	public boolean canRedo() {
		return !redoStack.isEmpty();
	}

	public boolean undo() {
		finishOpenCompositeEdit();
		HistoryEntry entry = undoStack.pollLast();
		if (entry == null) {
			return false;
		}
		pushHistory(redoStack, entry);
		applySnapshot(entry.before());
		return true;
	}

	public boolean redo() {
		finishOpenCompositeEdit();
		HistoryEntry entry = redoStack.pollLast();
		if (entry == null) {
			return false;
		}
		pushHistory(undoStack, entry);
		applySnapshot(entry.after());
		return true;
	}

	public void beginCompositeEdit() {
		if (compositeEditDepth == 0) {
			compositeEditStart = captureSnapshot();
			compositeEditLatest = compositeEditStart;
			compositeEditChanged = false;
		}
		compositeEditDepth++;
	}

	public void endCompositeEdit() {
		if (compositeEditDepth <= 0) {
			return;
		}
		compositeEditDepth--;
		if (compositeEditDepth > 0) {
			return;
		}
		if (compositeEditChanged && compositeEditStart != null) {
			pushHistory(undoStack, new HistoryEntry(compositeEditStart, compositeEditLatest == null ? compositeEditStart : compositeEditLatest));
		}
		compositeEditStart = null;
		compositeEditLatest = null;
		compositeEditChanged = false;
	}

	public GraphDocument document() {
		List<DocumentNodeDefinition> customDefinitions = definitions.values().stream()
			.filter(definition -> definition.kind == DocumentNodeDefinitionKind.CUSTOM_NODE)
			.filter(definition -> definitionParents.get(definition.id) == null)
			.map(this::toCustomDefinition)
			.toList();
		GraphScope rootScope = new GraphScope(
			root.graph(),
			root.layout(),
			new ArrayList<>(rootVariables.values()),
			subgraphsForParent(null)
		);
		return GraphDocument.of(rootScope, customDefinitions);
	}

	public List<GraphVariableDefinition> variables() {
		return List.copyOf(currentVariableState().values());
	}

	public GraphVariableDefinition createVariable() {
		String id = nextVariableId();
		GraphVariableDefinition variable = new GraphVariableDefinition(id, id, MCNGPortTypes.INT.id(), NodeConfigValues.intValue(0));
		EditorSnapshot before = captureSnapshot();
		currentVariableState().put(id, variable);
		finishMutation(before);
		return variable;
	}

	public boolean cycleVariableType(String variableId) {
		Map<String, GraphVariableDefinition> variables = currentVariableState();
		GraphVariableDefinition variable = variables.get(variableId);
		if (variable == null) {
			return false;
		}
		List<String> order = List.of(MCNGPortTypes.INT.id(), MCNGPortTypes.LONG.id(), MCNGPortTypes.DOUBLE.id(), MCNGPortTypes.STRING.id(), MCNGPortTypes.BOOLEAN.id(), MCNGPortTypes.ANY.id());
		int currentIndex = order.indexOf(variable.typeId());
		String nextType = order.get((currentIndex + 1 + order.size()) % order.size());
		EditorSnapshot before = captureSnapshot();
		variables.put(variableId, new GraphVariableDefinition(variable.id(), variable.displayName(), nextType, defaultVariableValue(nextType)));
		return finishMutation(before);
	}

	public boolean removeVariable(String variableId) {
		Map<String, GraphVariableDefinition> variables = currentVariableState();
		if (!variables.containsKey(variableId)) {
			return false;
		}
		EditorSnapshot before = captureSnapshot();
		variables.remove(variableId);
		return finishMutation(before);
	}

	public List<NodeInstance> nodes() {
		return currentState().nodes.values().stream().sorted(Comparator.comparing(node -> node.id().value())).toList();
	}

	public List<EdgeDefinition> edges() {
		return List.copyOf(currentState().edges);
	}

	public Map<NodeId, NodePosition> positions() {
		return Map.copyOf(currentState().positions);
	}

	public Map<NodeId, NodeSize> sizes() {
		return Map.copyOf(currentState().sizes);
	}

	public Optional<NodeId> selectedNodeId() {
		return Optional.ofNullable(primarySelectedNodeId);
	}

	public Set<NodeId> selectedNodeIds() {
		return Set.copyOf(selectedNodeIds);
	}

	public PendingConnection pendingConnection() {
		return pendingConnection;
	}

	public List<String> debugMessages() {
		return List.copyOf(debugMessages);
	}

	public List<GraphError> lastErrors() {
		return lastErrors;
	}

	public boolean hasError(NodeId nodeId) {
		if (lastErrors.stream().anyMatch(error -> nodeId.equals(error.nodeId()))) {
			return true;
		}
		String customDefinitionId = currentCustomDefinitionId();
		List<String> planContext = currentPlanContextPath();
		for (GraphError error : lastErrors) {
			List<String> path = customDefinitionId == null
				? compiledDocument.scopePathForNode(error.nodeId())
				: compiledDocument.scopePathForDefinitionNode(customDefinitionId, error.nodeId());
			if (path.equals(planContext) && nodeId.equals(visibleNodeIdFromRuntimeNode(error.nodeId(), planContext))) {
				return true;
			}
			if (path.size() > planContext.size() && path.subList(0, planContext.size()).equals(planContext)) {
				NodeId containerNodeId = containerNodeIdForSubgraph(path.get(planContext.size()));
				if (nodeId.equals(containerNodeId)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean isExecuting(NodeId nodeId) {
		return executingVisibleNodeIds.contains(nodeId) || executingVisibleRerouteNodeIds.contains(nodeId);
	}

	public boolean isExecutionRunning() {
		return runningExecution != null && lastExecutionSnapshot.status() == ExecutionSessionStatus.RUNNING;
	}

	public NodeType<?> nodeType(NodeInstance node) {
		return runtimeRegistry().getOrThrow(node.typeId());
	}

	public NodeInstance node(NodeId nodeId) {
		return requireNode(nodeId);
	}

	public List<NodeType<?>> availableNodeTypes() {
		return runtimeRegistry().all().stream()
			.sorted(Comparator.comparing(NodeType::displayName))
			.toList();
	}

	public NodeTypeRegistry resolvedRegistry() {
		return runtimeRegistry();
	}

	public PortTypeRegistry portTypes() {
		return portTypes;
	}

	public GraphEditorI18n i18n() {
		return host.i18n();
	}

	public String translate(String key, String fallback, Object... args) {
		return i18n().translate(key, fallback, args);
	}

	public String channelLabel(PortChannel channel) {
		if (channel == null) {
			return translate("mcng.ui.common.channel.unknown", "Unknown");
		}
		return switch (channel) {
			case DATA -> translate("mcng.ui.common.channel.data", "Data");
			case CONTROL -> translate("mcng.ui.common.channel.control", "Control");
		};
	}

	public String portTypeLabel(PortType<?> type) {
		return GraphEditorTranslations.shortPortTypeLabel(i18n(), type == null ? null : type.id());
	}

	public PortType<?> effectivePortType(NodeId nodeId, PortId portId, PortDirection direction) {
		try {
			return resolvePortType(currentDepth(), nodeId, portId, direction).effectiveType();
		} catch (IllegalArgumentException exception) {
			return requirePortDefinition(graphAtDepth(currentDepth()), nodeId, portId, direction).type();
		}
	}

	public PortChannel effectivePortChannel(NodeId nodeId, PortId portId, PortDirection direction) {
		try {
			return resolvePortChannel(currentDepth(), nodeId, portId, direction);
		} catch (IllegalArgumentException exception) {
			return requirePortDefinition(graphAtDepth(currentDepth()), nodeId, portId, direction).channel();
		}
	}

	public boolean isSelected(NodeId nodeId) {
		return selectedNodeIds.contains(nodeId);
	}

	public void selectNode(NodeId nodeId) {
		selectedNodeIds.clear();
		if (nodeId != null) {
			selectedNodeIds.add(nodeId);
		}
		primarySelectedNodeId = nodeId;
	}

	public void toggleNodeSelection(NodeId nodeId) {
		if (selectedNodeIds.contains(nodeId)) {
			selectedNodeIds.remove(nodeId);
			if (Objects.equals(primarySelectedNodeId, nodeId)) {
				primarySelectedNodeId = selectedNodeIds.stream().findFirst().orElse(null);
			}
			return;
		}
		selectedNodeIds.add(nodeId);
		primarySelectedNodeId = nodeId;
	}

	public void selectNodes(Collection<NodeId> nodeIds, boolean additive) {
		if (!additive) {
			selectedNodeIds.clear();
		}
		for (NodeId nodeId : nodeIds) {
			if (currentState().nodes.containsKey(nodeId)) {
				selectedNodeIds.add(nodeId);
				primarySelectedNodeId = nodeId;
			}
		}
		if (selectedNodeIds.isEmpty()) {
			primarySelectedNodeId = null;
		}
	}

	public void clearSelection() {
		selectedNodeIds.clear();
		primarySelectedNodeId = null;
	}

	public void moveNode(NodeId nodeId, double x, double y) {
		EditorSnapshot before = captureSnapshot();
		currentState().positions.put(nodeId, new NodePosition(x, y));
		finishMutation(before);
	}

	public void moveNodes(Map<NodeId, NodePosition> updatedPositions) {
		EditorSnapshot before = captureSnapshot();
		updatedPositions.forEach((nodeId, position) -> {
			if (currentState().nodes.containsKey(nodeId)) {
				currentState().positions.put(nodeId, position);
			}
		});
		finishMutation(before);
	}

	public void resizeNode(NodeId nodeId, NodePosition position, NodeSize size) {
		if (!currentState().nodes.containsKey(nodeId)) {
			return;
		}
		EditorSnapshot before = captureSnapshot();
		currentState().positions.put(nodeId, position);
		currentState().sizes.put(nodeId, size);
		finishMutation(before);
	}

	public void resizeReroute(NodeId nodeId, NodePosition position, NodeSize size, RerouteOrientation orientation) {
		NodeInstance node = currentState().nodes.get(nodeId);
		if (!isReroute(nodeId, node)) {
			return;
		}
		EditorSnapshot before = captureSnapshot();
		currentState().positions.put(nodeId, position);
		currentState().sizes.put(nodeId, size);
		setRerouteOrientation(nodeId, orientation);
		finishMutation(before);
	}

	public void addNode(String nodeTypeId, double x, double y) {
		NodeType<?> nodeType = runtimeRegistry().getOrThrow(nodeTypeId);
		NodeId nodeId = NodeId.random();
		EditorSnapshot before = captureSnapshot();
		currentState().nodes.put(nodeId, createNode(nodeId, nodeType));
		currentState().positions.put(nodeId, new NodePosition(x, y));
		selectNode(nodeId);
		finishMutation(before);
	}

	public void removeSelectedNode() {
		removeSelectedNodes();
	}

	public void removeSelectedNodes() {
		if (selectedNodeIds.isEmpty()) {
			return;
		}

		EditorSnapshot before = captureSnapshot();
		Set<NodeId> removed = new LinkedHashSet<>(selectedNodeIds);
		removed.forEach(nodeId -> {
			currentState().nodes.remove(nodeId);
			currentState().positions.remove(nodeId);
			currentState().sizes.remove(nodeId);
		});
		currentState().edges.removeIf(edge -> removed.contains(edge.fromNodeId()) || removed.contains(edge.toNodeId()));
		pendingConnection = null;
		clearSelection();
		finishMutation(before);
	}

	public boolean cancelPendingConnection() {
		if (pendingConnection == null) {
			return false;
		}
		pendingConnection = null;
		return true;
	}

	public boolean toggleConnectionCandidate(NodeId nodeId, PortId portId, PortDirection direction) {
		return toggleConnectionCandidate(nodeId, portId, direction, direction == PortDirection.OUTPUT);
	}

	public boolean toggleConnectionCandidate(NodeId nodeId, PortId portId, PortDirection direction, boolean rightSide) {
		return toggleConnectionCandidate(nodeId, portId, direction, rightSide ? NodeWidget.PortSide.RIGHT : NodeWidget.PortSide.LEFT);
	}

	public boolean toggleConnectionCandidate(NodeId nodeId, PortId portId, PortDirection direction, NodeWidget.PortSide side) {
		if (pendingConnection != null && pendingConnection.nodeId().equals(nodeId) && pendingConnection.portId().equals(portId) && pendingConnection.direction() == direction) {
			pendingConnection = null;
			return true;
		}

		EditorSnapshot before = captureSnapshot();
		boolean rerouteOrientationChanged = false;
		PortDirection effectiveDirection = direction;
		if (pendingConnection != null && isReroute(nodeId, currentState().nodes.get(nodeId))) {
			effectiveDirection = opposite(pendingConnection.direction());
			rerouteOrientationChanged = setRerouteOrientation(nodeId, RerouteOrientation.fromPortSide(side, effectiveDirection));
		}
		if (pendingConnection == null) {
			pendingConnection = new PendingConnection(nodeId, portId, effectiveDirection);
			return true;
		}

		PendingConnection previous = pendingConnection;
		pendingConnection = null;
		boolean connected = connectInternal(previous, new PendingConnection(nodeId, portId, effectiveDirection));
		if (connected || rerouteOrientationChanged) {
			finishMutation(before);
		}
		return connected;
	}

	public void clearPortConnections(NodeId nodeId, PortId portId, PortDirection direction) {
		EditorSnapshot before = captureSnapshot();
		if (direction == PortDirection.INPUT) {
			currentState().edges.removeIf(edge -> edge.toNodeId().equals(nodeId) && edge.toPortId().equals(portId));
		} else {
			currentState().edges.removeIf(edge -> edge.fromNodeId().equals(nodeId) && edge.fromPortId().equals(portId));
		}
		finishMutation(before);
	}

	public boolean hasIncomingConnection(NodeId nodeId, PortId portId) {
		return currentState().edges.stream().anyMatch(edge -> edge.toNodeId().equals(nodeId) && edge.toPortId().equals(portId));
	}

	boolean isRerouteFlipped(NodeId nodeId) {
		return rerouteOrientation(nodeId).inputSide().positiveAxis();
	}

	boolean isRerouteVertical(NodeId nodeId) {
		return rerouteOrientation(nodeId).vertical();
	}

	RerouteOrientation rerouteOrientation(NodeId nodeId) {
		NodeInstance node = currentState().nodes.get(nodeId);
		if (!isReroute(nodeId, node)) {
			return RerouteOrientation.LEFT_TO_RIGHT;
		}
		RerouteOrientation explicit = explicitRerouteOrientation(node.config());
		return explicit != null ? explicit : legacyRerouteOrientation(nodeId, node.config());
	}

	public void updateInlineInput(NodeId nodeId, PortId portId, JsonElement value) {
		NodeInstance node = requireNode(nodeId);
		EditorSnapshot before = captureSnapshot();
		currentState().nodes.put(nodeId, new NodeInstance(node.id(), node.typeId(), NodeConfigValues.copyWithInlineInput(node.config(), portId, value)));
		finishMutation(before);
	}

	public void updateControlValue(NodeId nodeId, String key, JsonElement value) {
		NodeInstance node = requireNode(nodeId);
		EditorSnapshot before = captureSnapshot();
		if ((DocumentNodeTypes.isDefinitionType(node.typeId()) || DocumentNodeTypes.isSubgraphType(node.typeId()))
			&& DocumentNodeTypes.DEFINITION_NAME_CONTROL_KEY.equals(key)) {
			String definitionId = DocumentNodeTypes.isDefinitionType(node.typeId())
				? DocumentNodeTypes.definitionIdFromTypeId(node.typeId())
				: DocumentNodeTypes.subgraphIdFromTypeId(node.typeId());
			DefinitionState definition = definitions.get(definitionId);
			if (definition != null) {
				definition.displayName = value.getAsString();
				finishMutation(before);
			}
			return;
		}
		currentState().nodes.put(nodeId, new NodeInstance(node.id(), node.typeId(), NodeConfigValues.copyWithControlValue(node.config(), key, value)));
		pruneInvalidNodePorts(nodeId);
		finishMutation(before);
	}

	public String exportJson() {
		String json = codec.toJson(document());
		host.copyToClipboard(json);
		showTranslatedMessage("mcng.ui.message.graph_json_copied", "Copied graph JSON to clipboard");
		return json;
	}

	public boolean importFromClipboard() {
		String contents = host.readClipboard();
		if (contents == null || contents.isBlank()) {
			showTranslatedMessage("mcng.ui.message.clipboard_empty", "Clipboard is empty");
			return false;
		}

		try {
			GraphDocument parsed = codec.fromJson(contents);
			replaceDocument(parsed);
			showTranslatedMessage("mcng.ui.message.graph_json_imported", "Imported graph JSON from clipboard");
			return true;
		} catch (RuntimeException exception) {
			showTranslatedMessage("mcng.ui.message.graph_json_import_failed", "Failed to import JSON: %s", exception.getMessage());
			return false;
		}
	}

	public ExecutionResult executeGraph() {
		cancelExecutionSilently();
		debugMessages.clear();
		runningExecution = compiledDocument.startExecution(new RecordingExecutionContext());
		lastExecutionSnapshot = runningExecution.snapshot();
		lastErrors = lastExecutionSnapshot.errors();
		refreshExecutionHighlights();
		if (lastExecutionSnapshot.status() == ExecutionSessionStatus.RUNNING) {
			showTranslatedMessage("mcng.ui.message.execution_started", "Execution started");
		} else {
			runningExecution = null;
			if (lastErrors.isEmpty()) {
				showTranslatedMessage("mcng.ui.message.execution_completed", "Executed dataflow graph");
			} else {
				showTranslatedMessage("mcng.ui.message.execution_errors", "Execution produced %s error(s)", lastErrors.size());
			}
		}
		return lastExecutionSnapshot.toExecutionResult();
	}

	public ExecutionResult triggerSelectedEvent() {
		NodeId source = primarySelectedNodeId != null && runtimeRegistry().getOrThrow(currentState().nodes.get(primarySelectedNodeId).typeId()).kind() == NodeKind.EVENT_SOURCE
			? primarySelectedNodeId
			: firstVisibleEventSource();

		if (source == null) {
			showTranslatedMessage("mcng.ui.message.no_event_source", "No event source node is available");
			lastErrors = List.of();
			return new ExecutionResult(Map.of(), Map.of(), List.of(), List.of());
		}

		cancelExecutionSilently();
		debugMessages.clear();
		runningExecution = compiledDocument.startEventExecution(source, (double) triggerCounter++, new RecordingExecutionContext());
		lastExecutionSnapshot = runningExecution.snapshot();
		lastErrors = lastExecutionSnapshot.errors();
		refreshExecutionHighlights();
		if (lastExecutionSnapshot.status() == ExecutionSessionStatus.RUNNING) {
			showTranslatedMessage("mcng.ui.message.trigger_started", "Triggered event execution");
		} else {
			runningExecution = null;
			if (lastErrors.isEmpty()) {
				showTranslatedMessage("mcng.ui.message.trigger_completed", "Triggered event graph");
			} else {
				showTranslatedMessage("mcng.ui.message.trigger_errors", "Trigger produced %s error(s)", lastErrors.size());
			}
		}
		return lastExecutionSnapshot.toExecutionResult();
	}

	private NodeId firstVisibleEventSource() {
		return compiledDocument.rootEventSourceNodeIds().stream()
			.filter(nodeId -> {
				List<String> path = compiledDocument.scopePathForNode(nodeId);
				List<String> context = currentPlanContextPath();
				return path.size() >= context.size() && path.subList(0, context.size()).equals(context);
			})
			.findFirst()
			.orElse(null);
	}

	public ExecutionSnapshot tickExecution(int maxNodeExecutions) {
		if (runningExecution == null) {
			return lastExecutionSnapshot;
		}
		lastExecutionSnapshot = runningExecution.step(maxNodeExecutions);
		lastErrors = lastExecutionSnapshot.errors();
		refreshExecutionHighlights();
		if (lastExecutionSnapshot.status() != ExecutionSessionStatus.RUNNING) {
			runningExecution = null;
			if (lastExecutionSnapshot.cancelled()) {
				showTranslatedMessage("mcng.ui.message.execution_cancelled", "Execution cancelled");
			} else if (lastErrors.isEmpty()) {
				showTranslatedMessage("mcng.ui.message.execution_finished", "Execution finished");
			} else {
				showTranslatedMessage("mcng.ui.message.execution_errors", "Execution produced %s error(s)", lastErrors.size());
			}
		}
		return lastExecutionSnapshot;
	}

	public boolean cancelExecution() {
		if (runningExecution == null) {
			return false;
		}
		runningExecution.cancel();
		lastExecutionSnapshot = runningExecution.snapshot();
		lastErrors = lastExecutionSnapshot.errors();
		runningExecution = null;
		refreshExecutionHighlights();
		showTranslatedMessage("mcng.ui.message.execution_cancelled", "Execution cancelled");
		return true;
	}

	public void showMessage(String message) {
		host.showMessage(message);
	}

	private void showTranslatedMessage(String key, String fallback, Object... args) {
		host.showMessage(translate(key, fallback, args));
	}

	public boolean hasLocalClipboard() {
		return localClipboard != null;
	}

	public boolean copySelectionToLocalClipboard() {
		if (selectedNodeIds.isEmpty()) {
			showTranslatedMessage("mcng.ui.message.select_node", "Select at least one node");
			return false;
		}

		MutableGraphState workspace = currentState();
		Set<NodeId> selection = new LinkedHashSet<>(selectedNodeIds);
		Bounds bounds = selectionBounds(selection, workspace.positions, workspace.sizes);

		List<NodeInstance> nodes = selection.stream()
			.map(workspace.nodes::get)
			.filter(Objects::nonNull)
			.map(this::copyNodeInstance)
			.toList();
		List<EdgeDefinition> edges = workspace.edges.stream()
			.filter(edge -> selection.contains(edge.fromNodeId()) && selection.contains(edge.toNodeId()))
			.map(this::copyEdgeDefinition)
			.toList();
		Map<NodeId, NodePosition> positions = new LinkedHashMap<>();
		Map<NodeId, NodeSize> sizes = new LinkedHashMap<>();
		for (NodeId nodeId : selection) {
			NodePosition position = workspace.positions.getOrDefault(nodeId, new NodePosition(0, 0));
			positions.put(nodeId, new NodePosition(position.x() - bounds.minX(), position.y() - bounds.minY()));
			NodeSize size = workspace.sizes.get(nodeId);
			if (size != null) {
				sizes.put(nodeId, size);
			}
		}

		Map<String, ClipboardDefinition> definitionsBySourceId = new LinkedHashMap<>();
		for (NodeInstance node : nodes) {
			collectSubgraphDefinitions(node.typeId(), definitionsBySourceId, new LinkedHashSet<>());
		}

		localClipboard = new LocalClipboard(
			new GraphDefinition(nodes, edges),
			new GraphLayout(positions, sizes),
			new ArrayList<>(definitionsBySourceId.values())
		);
		showTranslatedMessage("mcng.ui.message.local_clipboard_copied", "Copied %s node(s) to local clipboard", nodes.size());
		return true;
	}

	public boolean pasteLocalClipboard(double x, double y) {
		if (localClipboard == null) {
			showTranslatedMessage("mcng.ui.message.local_clipboard_empty", "Local clipboard is empty");
			return false;
		}
		if (!isInsideDefinition() && localClipboardContainsHelpers()) {
			showTranslatedMessage("mcng.ui.message.paste_helpers_requires_definition", "Graph Input/Output can only be pasted inside definitions");
			return false;
		}

		EditorSnapshot before = captureSnapshot();
		Map<String, String> definitionIdMap = new LinkedHashMap<>();
		for (ClipboardDefinition definition : localClipboard.definitions()) {
			definitionIdMap.put(definition.sourceId(), NodeId.random().value());
			collectClipboardSubgraphIds(definition.scope(), definitionIdMap);
		}
		for (ClipboardDefinition definition : localClipboard.definitions()) {
			String newDefinitionId = definitionIdMap.get(definition.sourceId());
			loadSubgraphs(
				List.of(new SubgraphDefinition(
					newDefinitionId,
					definition.displayName(),
					rewriteScope(definition.scope(), definitionIdMap)
				)),
				currentParentRef()
			);
		}

		MutableGraphState workspace = currentState();
		Map<NodeId, NodeId> nodeIdMap = new LinkedHashMap<>();
		List<NodeId> pastedNodeIds = new ArrayList<>();

		for (NodeInstance node : localClipboard.graph().nodes()) {
			NodeId newNodeId = NodeId.random();
			nodeIdMap.put(node.id(), newNodeId);
			workspace.nodes.put(newNodeId, rewriteNode(node, newNodeId, definitionIdMap));
			NodePosition offset = localClipboard.layout().nodePositions().getOrDefault(node.id(), new NodePosition(0, 0));
			NodePosition pastedPosition = new NodePosition(x + offset.x(), y + offset.y());
			workspace.positions.put(newNodeId, pastedPosition);
			NodeSize size = localClipboard.layout().nodeSizes().get(node.id());
			if (size != null) {
				workspace.sizes.put(newNodeId, size);
			}
			pastedNodeIds.add(newNodeId);
		}

		for (EdgeDefinition edge : localClipboard.graph().edges()) {
			NodeId fromNodeId = nodeIdMap.get(edge.fromNodeId());
			NodeId toNodeId = nodeIdMap.get(edge.toNodeId());
			if (fromNodeId != null && toNodeId != null) {
				workspace.edges.add(new EdgeDefinition(fromNodeId, edge.fromPortId(), toNodeId, edge.toPortId()));
			}
		}

		selectNodes(pastedNodeIds, false);
		if (!pastedNodeIds.isEmpty()) {
			primarySelectedNodeId = pastedNodeIds.getLast();
		}
		finishMutation(before);
		showTranslatedMessage("mcng.ui.message.local_clipboard_pasted", "Pasted %s node(s)", pastedNodeIds.size());
		return true;
	}

	public void copyToClipboard(String value) {
		host.copyToClipboard(value);
	}

	public boolean supportsFileDialogs() {
		return host.supportsFileDialogs();
	}

	public Optional<String> chooseFile(GraphFileDialogRequest request) {
		Objects.requireNonNull(request, "request");
		return host.chooseFile(request);
	}

	public void updateNodeConfig(NodeId nodeId, JsonObject config) {
		Objects.requireNonNull(config, "config");
		NodeInstance node = requireNode(nodeId);
		EditorSnapshot before = captureSnapshot();
		currentState().nodes.put(nodeId, new NodeInstance(node.id(), node.typeId(), config));
		finishMutation(before);
	}

	public String readClipboard() {
		return host.readClipboard();
	}

	public List<Breadcrumb> breadcrumbs() {
		List<Breadcrumb> breadcrumbs = new ArrayList<>();
		breadcrumbs.add(new Breadcrumb(translate("mcng.ui.breadcrumb.root", "Root"), null));
		for (String definitionId : contextPath) {
			DefinitionState definition = definitions.get(definitionId);
			if (definition != null) {
				breadcrumbs.add(new Breadcrumb(definition.displayName, definitionId));
			}
		}
		return breadcrumbs;
	}

	public boolean isInsideDefinition() {
		return !contextPath.isEmpty();
	}

	public String currentDefinitionId() {
		return contextPath.isEmpty() ? null : contextPath.getLast();
	}

	public Optional<DocumentNodeDefinitionKind> currentDefinitionKind() {
		DefinitionState definition = currentDefinition();
		return definition == null ? Optional.empty() : Optional.of(definition.kind);
	}

	public boolean canEnterDefinition(NodeId nodeId) {
		NodeInstance node = currentState().nodes.get(nodeId);
		return node != null && (DocumentNodeTypes.isDefinitionType(node.typeId()) || DocumentNodeTypes.isSubgraphType(node.typeId()));
	}

	public boolean enterDefinition(NodeId nodeId) {
		NodeInstance node = currentState().nodes.get(nodeId);
		if (node == null || (!DocumentNodeTypes.isDefinitionType(node.typeId()) && !DocumentNodeTypes.isSubgraphType(node.typeId()))) {
			return false;
		}
		String definitionId = DocumentNodeTypes.isDefinitionType(node.typeId())
			? DocumentNodeTypes.definitionIdFromTypeId(node.typeId())
			: DocumentNodeTypes.subgraphIdFromTypeId(node.typeId());
		if (!definitions.containsKey(definitionId)) {
			return false;
		}
		contextPath.add(definitionId);
		contextNodePath.add(nodeId);
		clearSelection();
		pendingConnection = null;
		NodeId first = currentState().nodes.keySet().stream().findFirst().orElse(null);
		if (first != null) {
			selectNode(first);
		}
		refreshExecutionHighlights();
		return true;
	}

	public boolean exitToBreadcrumb(String definitionId) {
		if (definitionId == null) {
			contextPath.clear();
			contextNodePath.clear();
		} else {
			int index = contextPath.indexOf(definitionId);
			if (index < 0) {
				return false;
			}
			contextPath.subList(index + 1, contextPath.size()).clear();
			contextNodePath.subList(index + 1, contextNodePath.size()).clear();
		}
		clearSelection();
		pendingConnection = null;
		NodeId first = currentState().nodes.keySet().stream().findFirst().orElse(null);
		if (first != null) {
			selectNode(first);
		}
		refreshExecutionHighlights();
		return true;
	}

	public List<DocumentNodeDefinition> definitions() {
		return definitions.values().stream()
			.filter(definition -> definition.kind == DocumentNodeDefinitionKind.CUSTOM_NODE)
			.map(this::toCustomDefinition)
			.toList();
	}

	public DocumentNodeDefinition createBlankDefinition(DocumentNodeDefinitionKind kind, double x, double y) {
		EditorSnapshot before = captureSnapshot();
		String definitionId = NodeId.random().value();
		String name = nextDefinitionName(kind);
		DefinitionState definition = new DefinitionState(definitionId, name, kind, new MutableGraphState());
		definitionParents.put(definitionId, kind == DocumentNodeDefinitionKind.SUBGRAPH ? currentParentRef() : null);
		definitions.put(definitionId, definition);
		refreshRuntime();

		String typeId = kind == DocumentNodeDefinitionKind.SUBGRAPH
			? DocumentNodeTypes.subgraphTypeId(definitionId)
			: DocumentNodeTypes.definitionTypeId(definitionId);
		NodeType<?> nodeType = runtimeRegistry().getOrThrow(typeId);
		NodeId nodeId = NodeId.random();
		currentState().nodes.put(nodeId, createNode(nodeId, nodeType));
		currentState().positions.put(nodeId, new NodePosition(x, y));
		selectNode(nodeId);
		enterDefinition(nodeId);
		finishMutation(before);
		return new DocumentNodeDefinition(definitionId, name, new GraphScope(definition.graphState.graph(), definition.graphState.layout(), List.of(), List.of()));
	}

	public boolean createDefinitionFromSelection(DocumentNodeDefinitionKind kind) {
		if (selectedNodeIds.isEmpty()) {
			showTranslatedMessage("mcng.ui.message.select_node", "Select at least one node");
			return false;
		}

		Set<NodeId> selection = new LinkedHashSet<>(selectedNodeIds);
		int eventSources = 0;
		for (NodeId nodeId : selection) {
			NodeType<?> nodeType = nodeType(requireNode(nodeId));
			if (nodeType.kind() == NodeKind.EVENT_SOURCE) {
				eventSources++;
			}
		}
		if (kind == DocumentNodeDefinitionKind.CUSTOM_NODE && eventSources > 0) {
			showTranslatedMessage("mcng.ui.message.custom_node_contains_event", "Custom nodes cannot contain event sources");
			return false;
		}

		EditorSnapshot before = captureSnapshot();
		MutableGraphState workspace = currentState();
		Bounds bounds = selectionBounds(selection, workspace.positions, workspace.sizes);
		String definitionId = NodeId.random().value();
		String name = nextDefinitionName(kind);
		MutableGraphState definitionGraph = new MutableGraphState();
		Map<PortIdKey, NodeId> outputPorts = new LinkedHashMap<>();

		for (NodeId nodeId : selection) {
			NodeInstance node = workspace.nodes.get(nodeId);
			definitionGraph.nodes.put(nodeId, node);
			NodePosition position = workspace.positions.getOrDefault(nodeId, new NodePosition(0, 0));
			definitionGraph.positions.put(nodeId, new NodePosition(position.x() - bounds.minX() + 80, position.y() - bounds.minY() + 40));
			NodeSize size = workspace.sizes.get(nodeId);
			if (size != null) {
				definitionGraph.sizes.put(nodeId, size);
			}
		}

		List<EdgeDefinition> remainingEdges = new ArrayList<>();
		for (EdgeDefinition edge : workspace.edges) {
			boolean fromSelected = selection.contains(edge.fromNodeId());
			boolean toSelected = selection.contains(edge.toNodeId());
			if (fromSelected && toSelected) {
				definitionGraph.edges.add(edge);
				continue;
			}
			if (!fromSelected && toSelected) {
				NodeId graphInputId = NodeId.random();
				PortDefinition targetPort = requirePort(workspace.nodes.get(edge.toNodeId()), edge.toPortId(), PortDirection.INPUT);
				PortChannel channel = resolvePortChannel(workspace.graph(), edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
				if (kind == DocumentNodeDefinitionKind.CUSTOM_NODE && channel == PortChannel.CONTROL) {
					showTranslatedMessage("mcng.ui.message.custom_node_control_boundary", "Custom nodes cannot expose control flow");
					return false;
				}
				definitionGraph.nodes.put(graphInputId, createGraphPortNode(
					graphInputId,
					channel == PortChannel.CONTROL
						? kind == DocumentNodeDefinitionKind.SUBGRAPH ? DocumentNodeTypes.SUBGRAPH_FLOW_INPUT : DocumentNodeTypes.FLOW_INPUT
						: kind == DocumentNodeDefinitionKind.SUBGRAPH ? DocumentNodeTypes.SUBGRAPH_INPUT : DocumentNodeTypes.GRAPH_INPUT,
					targetPort.name()
				));
				NodePosition targetPosition = definitionGraph.positions.getOrDefault(edge.toNodeId(), new NodePosition(80, 40));
				definitionGraph.positions.put(graphInputId, new NodePosition(20, targetPosition.y()));
				definitionGraph.edges.add(new EdgeDefinition(
					graphInputId,
					channel == PortChannel.CONTROL ? new PortId("flow") : new PortId("value"),
					edge.toNodeId(),
					edge.toPortId()
				));
				remainingEdges.add(new EdgeDefinition(edge.fromNodeId(), edge.fromPortId(), new NodeId("__PENDING__" + graphInputId.value()), new PortId(graphInputId.value())));
				continue;
			}
			if (fromSelected && !toSelected) {
				PortIdKey key = new PortIdKey(edge.fromNodeId(), edge.fromPortId());
				PortChannel channel = resolvePortChannel(workspace.graph(), edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
				if (kind == DocumentNodeDefinitionKind.CUSTOM_NODE && channel == PortChannel.CONTROL) {
					showTranslatedMessage("mcng.ui.message.custom_node_control_boundary", "Custom nodes cannot expose control flow");
					return false;
				}
				NodeId graphOutputId = outputPorts.computeIfAbsent(key, ignored -> {
					NodeId newId = NodeId.random();
					PortDefinition sourcePort = requirePort(workspace.nodes.get(edge.fromNodeId()), edge.fromPortId(), PortDirection.OUTPUT);
					definitionGraph.nodes.put(newId, createGraphPortNode(
						newId,
						channel == PortChannel.CONTROL
							? kind == DocumentNodeDefinitionKind.SUBGRAPH ? DocumentNodeTypes.SUBGRAPH_FLOW_OUTPUT : DocumentNodeTypes.FLOW_OUTPUT
							: kind == DocumentNodeDefinitionKind.SUBGRAPH ? DocumentNodeTypes.SUBGRAPH_OUTPUT : DocumentNodeTypes.GRAPH_OUTPUT,
						sourcePort.name()
					));
					NodePosition sourcePosition = definitionGraph.positions.getOrDefault(edge.fromNodeId(), new NodePosition(80, 40));
					definitionGraph.positions.put(newId, new NodePosition(bounds.width() + 140, sourcePosition.y()));
					definitionGraph.edges.add(new EdgeDefinition(
						edge.fromNodeId(),
						edge.fromPortId(),
						newId,
						channel == PortChannel.CONTROL ? new PortId("flow") : new PortId("value")
					));
					return newId;
				});
				remainingEdges.add(new EdgeDefinition(new NodeId("__PENDING__" + graphOutputId.value()), new PortId(graphOutputId.value()), edge.toNodeId(), edge.toPortId()));
				continue;
			}
			remainingEdges.add(edge);
		}

		DefinitionState definition = new DefinitionState(definitionId, name, kind, definitionGraph);
		definitions.put(definitionId, definition);
		definitionParents.put(definitionId, kind == DocumentNodeDefinitionKind.SUBGRAPH ? currentParentRef() : null);
		refreshRuntime();

		String typeId = kind == DocumentNodeDefinitionKind.SUBGRAPH
			? DocumentNodeTypes.subgraphTypeId(definitionId)
			: DocumentNodeTypes.definitionTypeId(definitionId);
		NodeType<?> definitionNodeType = runtimeRegistry().getOrThrow(typeId);
		NodeId replacementNodeId = NodeId.random();
		NodePosition replacementPosition = new NodePosition(bounds.centerX() - 98, bounds.centerY() - 40);
		workspace.nodes.keySet().removeIf(selection::contains);
		workspace.positions.keySet().removeIf(selection::contains);
		workspace.sizes.keySet().removeIf(selection::contains);
		workspace.edges.clear();
		workspace.nodes.put(replacementNodeId, createNode(replacementNodeId, definitionNodeType));
		workspace.positions.put(replacementNodeId, replacementPosition);
		for (EdgeDefinition edge : remainingEdges) {
			if (edge.fromNodeId().value().startsWith("__PENDING__")) {
				String definitionPortNodeId = edge.fromNodeId().value().substring("__PENDING__".length());
				workspace.edges.add(new EdgeDefinition(replacementNodeId, new PortId(definitionPortNodeId), edge.toNodeId(), edge.toPortId()));
			} else if (edge.toNodeId().value().startsWith("__PENDING__")) {
				String definitionPortNodeId = edge.toNodeId().value().substring("__PENDING__".length());
				workspace.edges.add(new EdgeDefinition(edge.fromNodeId(), edge.fromPortId(), replacementNodeId, new PortId(definitionPortNodeId)));
			} else {
				workspace.edges.add(edge);
			}
		}

		selectNode(replacementNodeId);
		return finishMutation(before);
	}

	private NodeTypeRegistry runtimeRegistry() {
		if (resolvedRegistry == null) {
			refreshRuntime();
		}
		return resolvedRegistry;
	}

	private void refreshRuntime() {
		refreshRuntime(document());
	}

	private void refreshRuntime(GraphDocument document) {
		cancelExecutionSilently();
		compiledDocument = new GraphExecutor(baseRegistry, portTypes).compile(document);
		resolvedRegistry = compiledDocument.resolvedRegistry();
		lastErrors = compiledDocument.compileErrors();
		lastExecutionSnapshot = emptySnapshot();
		refreshExecutionHighlights();
	}

	private boolean localClipboardContainsHelpers() {
		return localClipboard.graph().nodes().stream().anyMatch(node -> DocumentNodeTypes.isHelperType(node.typeId()));
	}

	private void collectSubgraphDefinitions(String typeId, Map<String, ClipboardDefinition> definitionsBySourceId, Set<String> visiting) {
		if (!DocumentNodeTypes.isSubgraphType(typeId)) {
			return;
		}
		String definitionId = DocumentNodeTypes.subgraphIdFromTypeId(typeId);
		DefinitionState definition = definitions.get(definitionId);
		if (definition == null || definition.kind != DocumentNodeDefinitionKind.SUBGRAPH || definitionsBySourceId.containsKey(definitionId) || !visiting.add(definitionId)) {
			return;
		}
		definitionsBySourceId.put(
			definitionId,
			new ClipboardDefinition(
				definitionId,
				definition.displayName,
				new GraphScope(
					copyGraph(definition.graphState.graph()),
					copyLayout(definition.graphState.layout()),
					new ArrayList<>(definition.variables.values()),
					subgraphsForParent(new ParentScopeRef(definition.id))
				)
			)
		);
	}

	private GraphDefinition rewriteGraph(GraphDefinition graph, Map<String, String> definitionIdMap) {
		return new GraphDefinition(
			graph.nodes().stream().map(node -> rewriteNode(node, node.id(), definitionIdMap)).toList(),
			graph.edges().stream().map(this::copyEdgeDefinition).toList()
		);
	}

	private GraphDefinition copyGraph(GraphDefinition graph) {
		return new GraphDefinition(
			graph.nodes().stream().map(this::copyNodeInstance).toList(),
			graph.edges().stream().map(this::copyEdgeDefinition).toList()
		);
	}

	private GraphLayout copyLayout(GraphLayout layout) {
		Map<NodeId, NodePosition> positions = new LinkedHashMap<>();
		layout.nodePositions().forEach((nodeId, position) -> positions.put(nodeId, new NodePosition(position.x(), position.y())));
		Map<NodeId, NodeSize> sizes = new LinkedHashMap<>();
		layout.nodeSizes().forEach((nodeId, size) -> sizes.put(nodeId, new NodeSize(size.width(), size.height())));
		return new GraphLayout(positions, sizes);
	}

	private NodeInstance rewriteNode(NodeInstance source, NodeId nodeId, Map<String, String> definitionIdMap) {
		String typeId = source.typeId();
		if (DocumentNodeTypes.isDefinitionType(typeId)) {
			String sourceDefinitionId = DocumentNodeTypes.definitionIdFromTypeId(typeId);
			String mappedDefinitionId = definitionIdMap.get(sourceDefinitionId);
			if (mappedDefinitionId != null) {
				typeId = DocumentNodeTypes.definitionTypeId(mappedDefinitionId);
			}
		} else if (DocumentNodeTypes.isSubgraphType(typeId)) {
			String sourceDefinitionId = DocumentNodeTypes.subgraphIdFromTypeId(typeId);
			String mappedDefinitionId = definitionIdMap.get(sourceDefinitionId);
			if (mappedDefinitionId != null) {
				typeId = DocumentNodeTypes.subgraphTypeId(mappedDefinitionId);
			}
		}
		return new NodeInstance(nodeId, typeId, source.config());
	}

	private GraphScope rewriteScope(GraphScope scope, Map<String, String> definitionIdMap) {
		return new GraphScope(
			rewriteGraph(scope.graph(), definitionIdMap),
			copyLayout(scope.layout()),
			scope.variables(),
			scope.subgraphs().stream()
				.map(subgraph -> new SubgraphDefinition(
					definitionIdMap.getOrDefault(subgraph.id(), subgraph.id()),
					subgraph.displayName(),
					rewriteScope(subgraph.scope(), definitionIdMap)
				))
				.toList()
		);
	}

	private void collectClipboardSubgraphIds(GraphScope scope, Map<String, String> definitionIdMap) {
		for (SubgraphDefinition subgraph : scope.subgraphs()) {
			definitionIdMap.putIfAbsent(subgraph.id(), NodeId.random().value());
			collectClipboardSubgraphIds(subgraph.scope(), definitionIdMap);
		}
	}

	private NodeInstance copyNodeInstance(NodeInstance source) {
		return new NodeInstance(source.id(), source.typeId(), source.config());
	}

	private EdgeDefinition copyEdgeDefinition(EdgeDefinition edge) {
		return new EdgeDefinition(edge.fromNodeId(), edge.fromPortId(), edge.toNodeId(), edge.toPortId());
	}

	private boolean connectInternal(PendingConnection first, PendingConnection second) {
		if (first.direction() == second.direction()) {
			showTranslatedMessage("mcng.ui.message.connect_output_to_input", "Connect an output port to an input port");
			return false;
		}

		PendingConnection output = first.direction() == PortDirection.OUTPUT ? first : second;
		PendingConnection input = first.direction() == PortDirection.INPUT ? first : second;
		NodeInstance fromNode = currentState().nodes.get(output.nodeId());
		NodeInstance toNode = currentState().nodes.get(input.nodeId());
		requirePort(fromNode, output.portId(), PortDirection.OUTPUT);
		requirePort(toNode, input.portId(), PortDirection.INPUT);

		GraphDefinition candidateGraph = candidateGraphForConnection(output, input);
		if (candidateGraph == null) {
			showTranslatedMessage("mcng.ui.message.reroute_has_source", "Reroute chain already has an incoming source");
			return false;
		}
		PortChannel fromChannel;
		PortChannel toChannel;
		try {
			fromChannel = resolvePortChannel(candidateGraph, output.nodeId(), output.portId(), PortDirection.OUTPUT);
			toChannel = resolvePortChannel(candidateGraph, input.nodeId(), input.portId(), PortDirection.INPUT);
		} catch (IllegalArgumentException exception) {
			host.showMessage(exception.getMessage());
			return false;
		}
		if (fromChannel != toChannel) {
			showTranslatedMessage(
				"mcng.ui.message.port_channel_mismatch",
				"Port channels are not compatible: %s -> %s",
				channelLabel(fromChannel),
				channelLabel(toChannel)
			);
			return false;
		}

		if (fromChannel == PortChannel.DATA) {
			ResolvedPortType fromResolution = resolvePortType(currentDepth(), candidateGraph, output.nodeId(), output.portId(), PortDirection.OUTPUT);
			ResolvedPortType toResolution = resolvePortType(currentDepth(), candidateGraph, input.nodeId(), input.portId(), PortDirection.INPUT);
			if (!GraphPortTypeResolver.canConnect(fromResolution, toResolution, portTypes)) {
				PortType<?> fromType = fromResolution.effectiveType();
				PortType<?> toType = toResolution.effectiveType();
				showTranslatedMessage(
					"mcng.ui.message.port_type_mismatch",
					"Port types are not compatible: %s -> %s",
					portTypeLabel(fromType),
					portTypeLabel(toType)
				);
				return false;
			}
		}

		currentState().edges.clear();
		currentState().edges.addAll(candidateGraph.edges());
		return true;
	}

	private GraphDefinition candidateGraphForConnection(PendingConnection output, PendingConnection input) {
		List<EdgeDefinition> edges = currentState().edges.stream()
			.map(this::copyEdgeDefinition)
			.collect(ArrayList::new, List::add, List::addAll);
		NodeInstance inputNode = currentState().nodes.get(input.nodeId());
		if (isReroute(input.nodeId(), inputNode) && input.portId().equals(BuiltinNodeTypes.VALUE_PORT)) {
			if (!prepareRerouteInput(edges, input.nodeId(), true, new LinkedHashSet<>())) {
				return null;
			}
		} else {
			edges.removeIf(edge -> edge.toNodeId().equals(input.nodeId()) && edge.toPortId().equals(input.portId()));
		}

		EdgeDefinition candidateEdge = new EdgeDefinition(output.nodeId(), output.portId(), input.nodeId(), input.portId());
		edges.removeIf(edge -> sameEdge(edge, candidateEdge));
		edges.add(candidateEdge);
		return new GraphDefinition(new ArrayList<>(currentState().nodes.values()), edges);
	}

	private boolean prepareRerouteInput(List<EdgeDefinition> edges, NodeId rerouteNodeId, boolean allowReplaceExternal, Set<NodeId> visiting) {
		if (!visiting.add(rerouteNodeId)) {
			return false;
		}
		EdgeDefinition incoming = edges.stream()
			.filter(edge -> edge.toNodeId().equals(rerouteNodeId) && edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT))
			.findFirst()
			.orElse(null);
		if (incoming == null) {
			return true;
		}

		NodeInstance sourceNode = currentState().nodes.get(incoming.fromNodeId());
		if (!isReroute(incoming.fromNodeId(), sourceNode)) {
			if (!allowReplaceExternal) {
				return false;
			}
			edges.remove(incoming);
			return true;
		}
		if (!prepareRerouteInput(edges, incoming.fromNodeId(), false, visiting)) {
			return false;
		}

		edges.remove(incoming);
		EdgeDefinition reversed = new EdgeDefinition(rerouteNodeId, BuiltinNodeTypes.VALUE_PORT, incoming.fromNodeId(), BuiltinNodeTypes.VALUE_PORT);
		edges.removeIf(edge -> sameEdge(edge, reversed));
		edges.add(reversed);
		return true;
	}

	private boolean sameEdge(EdgeDefinition left, EdgeDefinition right) {
		return left.fromNodeId().equals(right.fromNodeId())
			&& left.fromPortId().equals(right.fromPortId())
			&& left.toNodeId().equals(right.toNodeId())
			&& left.toPortId().equals(right.toPortId());
	}

	private PortChannel resolvePortChannel(int depth, NodeId nodeId, PortId portId, PortDirection direction) {
		return resolvePortChannel(graphAtDepth(depth), nodeId, portId, direction);
	}

	private PortChannel resolvePortChannel(GraphDefinition graph, NodeId nodeId, PortId portId, PortDirection direction) {
		return GraphPortTypeResolver.resolveChannel(graph, runtimeRegistry(), nodeId, portId, direction);
	}

	private ResolvedPortType resolvePortType(int depth, NodeId nodeId, PortId portId, PortDirection direction) {
		return resolvePortType(depth, graphAtDepth(depth), nodeId, portId, direction);
	}

	private ResolvedPortType resolvePortType(int depth, GraphDefinition graph, NodeId nodeId, PortId portId, PortDirection direction) {
		return resolvePortType(depth, graph, nodeId, portId, direction, Set.of());
	}

	private ResolvedPortType resolvePortType(int depth, GraphDefinition graph, NodeId nodeId, PortId portId, PortDirection direction, Set<ScopedPortRef> visiting) {
		ScopedPortRef currentRef = new ScopedPortRef(depth, new PortRef(nodeId, portId, direction));
		if (visiting.contains(currentRef)) {
			PortDefinition definition = requirePortDefinition(graph, nodeId, portId, direction);
			if (resolvePortChannel(graph, nodeId, portId, direction) == PortChannel.CONTROL) {
				return new ResolvedPortType(definition, MCNGPortTypes.ANY, false, false);
			}
			if (definition.numericFamily()) {
				return new ResolvedPortType(definition, MCNGPortTypes.ANY, false, true);
			}
			return definition.genericGroupId() != null
				? new ResolvedPortType(definition, definition.type(), true, false)
				: new ResolvedPortType(definition, definition.type(), false, false);
		}

		Set<ScopedPortRef> nextVisiting = new LinkedHashSet<>(visiting);
		nextVisiting.add(currentRef);

		NodeInstance node = findNode(graph, nodeId);
		if (node == null) {
			throw new IllegalArgumentException("Unknown node " + nodeId);
		}
		PortDefinition definition = requirePortDefinition(graph, nodeId, portId, direction);
		if (resolvePortChannel(graph, nodeId, portId, direction) == PortChannel.CONTROL) {
			return new ResolvedPortType(definition, MCNGPortTypes.ANY, false, false);
		}
		ResolvedPortType dynamic = BuiltinNodeTypes.resolveDynamicPortType(node, definition, new DynamicPortTypeResolverContext() {
			@Override
			public NodeInstance node(NodeId candidate) {
				NodeInstance candidateNode = findNode(graph, candidate);
				if (candidateNode == null) {
					throw new IllegalArgumentException("Unknown node " + candidate);
				}
				return candidateNode;
			}

			@Override
			public ResolvedPortType resolve(NodeId candidateNodeId, PortId candidatePortId, PortDirection candidateDirection) {
				return resolvePortType(depth, graph, candidateNodeId, candidatePortId, candidateDirection, nextVisiting);
			}

			@Override
			public Object readInlineInputValue(NodeId candidateNodeId, PortId candidatePortId) {
				NodeInstance candidateNode = findNode(graph, candidateNodeId);
				if (candidateNode == null) {
					throw new IllegalArgumentException("Unknown node " + candidateNodeId);
				}
				PortDefinition candidatePort = requirePortDefinition(graph, candidateNodeId, candidatePortId, PortDirection.INPUT);
				return NodeConfigValues.readInlineInputValue(candidateNode.config(), candidatePort);
			}

			@Override
			public PortType<?> variableType(String variableId) {
				return visibleVariableType(depth, variableId);
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

		PortType<?> parentResolved = resolveFromParentSubgraphContext(depth, nodeId, portId, direction, nextVisiting);
		if (parentResolved != null) {
			return new ResolvedPortType(definition, parentResolved, false, definition.numericFamily());
		}

		PortType<?> resolved = resolveGenericGroupType(depth, graph, new GroupKey(nodeId, definition.genericGroupId()), Set.of(), nextVisiting);
		if (resolved == null) {
			return definition.numericFamily()
				? new ResolvedPortType(definition, fallbackNumericType(node, definition), false, true)
				: new ResolvedPortType(definition, definition.type(), true, false);
		}
		return new ResolvedPortType(definition, resolved, false, definition.numericFamily());
	}

	private PortType<?> fallbackNumericType(NodeInstance node, PortDefinition definition) {
		PortType<?> inferredInlineType = inferNumericInlineType(node, definition);
		return inferredInlineType != null ? inferredInlineType : MCNGPortTypes.ANY;
	}

	private PortType<?> inferNumericInlineType(NodeInstance node, PortDefinition definition) {
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

	private PortType<?> resolveGenericGroupType(int depth, GraphDefinition graph, GroupKey groupKey, Set<ScopedGroupKey> visiting, Set<ScopedPortRef> portVisiting) {
		ScopedGroupKey scopedGroupKey = new ScopedGroupKey(depth, groupKey);
		if (visiting.contains(scopedGroupKey)) {
			return null;
		}
		if (isRerouteGroup(graph, groupKey)) {
			return resolveRerouteComponentType(depth, graph, groupKey.nodeId(), visiting, portVisiting);
		}

		Set<ScopedGroupKey> visited = new LinkedHashSet<>(visiting);
		visited.add(scopedGroupKey);

		for (EdgeDefinition edge : graph.edges()) {
			PortType<?> inferred = inferFromEdge(depth, graph, groupKey, edge, visited, portVisiting);
			if (inferred != null) {
				return inferred;
			}
		}
		PortType<?> inferredInlineType = inferNumericGroupTypeFromInlineInputs(graph, groupKey);
		if (inferredInlineType != null) {
			return inferredInlineType;
		}
		return null;
	}

	private PortType<?> inferNumericGroupTypeFromInlineInputs(GraphDefinition graph, GroupKey groupKey) {
		NodeInstance node = findNode(graph, groupKey.nodeId());
		if (node == null) {
			return null;
		}
		NodeType<?> nodeType = runtimeRegistry().find(node.typeId()).orElse(null);
		if (nodeType == null) {
			return null;
		}

		PortType<?> resolved = null;
		for (PortDefinition input : nodeType.inputs(node)) {
			if (!groupKey.groupId().equals(input.genericGroupId())) {
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

	private PortType<?> resolveRerouteComponentType(
		int depth,
		GraphDefinition graph,
		NodeId rerouteNodeId,
		Set<ScopedGroupKey> visiting,
		Set<ScopedPortRef> portVisiting
	) {
		RerouteComponent component = collectRerouteComponent(graph, rerouteNodeId);
		for (PortRef anchor : component.anchors()) {
			PortDefinition definition = requirePortDefinition(graph, anchor.nodeId(), anchor.portId(), anchor.direction());
			if (definition.channel() != PortChannel.DATA) {
				continue;
			}
			PortType<?> inferred = inferFromAnchor(depth, graph, anchor, visiting, portVisiting);
			if (inferred != null) {
				return inferred;
			}
		}
		return null;
	}

	private PortType<?> inferFromAnchor(
		int depth,
		GraphDefinition graph,
		PortRef anchor,
		Set<ScopedGroupKey> visiting,
		Set<ScopedPortRef> portVisiting
	) {
		PortDefinition definition = requirePortDefinition(graph, anchor.nodeId(), anchor.portId(), anchor.direction());
		if (definition.genericGroupId() != null) {
			PortType<?> resolved = resolveGenericGroupType(depth, graph, new GroupKey(anchor.nodeId(), definition.genericGroupId()), visiting, portVisiting);
			if (resolved == null || resolved.equals(MCNGPortTypes.ANY)) {
				return null;
			}
			return resolved;
		}
		if (definition.numericFamily() && anchor.direction() == PortDirection.INPUT) {
			return null;
		}
		ResolvedPortType resolved = resolvePortType(depth, graph, anchor.nodeId(), definition.id(), anchor.direction(), portVisiting);
		if (resolved.unresolvedGeneric() || resolved.unresolvedNumeric()) {
			return null;
		}
		return resolved.effectiveType();
	}

	private RerouteComponent collectRerouteComponent(GraphDefinition graph, NodeId rerouteNodeId) {
		Set<NodeId> rerouteNodeIds = new LinkedHashSet<>();
		List<PortRef> anchors = new ArrayList<>();
		ArrayList<NodeId> queue = new ArrayList<>();
		queue.add(rerouteNodeId);
		int index = 0;
		while (index < queue.size()) {
			NodeId currentNodeId = queue.get(index++);
			if (!rerouteNodeIds.add(currentNodeId)) {
				continue;
			}
			NodeInstance currentNode = findNode(graph, currentNodeId);
			if (!isReroute(currentNodeId, currentNode)) {
				continue;
			}
			for (EdgeDefinition edge : graph.edges()) {
				PortRef opposite = rerouteOpposite(edge, currentNodeId);
				if (opposite == null) {
					continue;
				}
				NodeInstance oppositeNode = findNode(graph, opposite.nodeId());
				if (oppositeNode != null && isReroute(opposite.nodeId(), oppositeNode)) {
					queue.add(opposite.nodeId());
					continue;
				}
				anchors.add(opposite);
			}
		}
		return new RerouteComponent(Set.copyOf(rerouteNodeIds), List.copyOf(anchors));
	}

	private PortRef rerouteOpposite(EdgeDefinition edge, NodeId rerouteNodeId) {
		if (edge.fromNodeId().equals(rerouteNodeId) && edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)) {
			return new PortRef(edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
		}
		if (edge.toNodeId().equals(rerouteNodeId) && edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)) {
			return new PortRef(edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
		}
		return null;
	}

	private PortType<?> inferFromEdge(
		int depth,
		GraphDefinition graph,
		GroupKey groupKey,
		EdgeDefinition edge,
		Set<ScopedGroupKey> visiting,
		Set<ScopedPortRef> portVisiting
	) {
		PortDefinition fromDefinition = findPortDefinition(graph, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT);
		PortDefinition toDefinition = findPortDefinition(graph, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT);
		if (fromDefinition == null || toDefinition == null) {
			return null;
		}
		if (resolvePortChannel(graph, edge.fromNodeId(), edge.fromPortId(), PortDirection.OUTPUT) != PortChannel.DATA
			|| resolvePortChannel(graph, edge.toNodeId(), edge.toPortId(), PortDirection.INPUT) != PortChannel.DATA) {
			return null;
		}

		if (matchesGroup(edge.fromNodeId(), fromDefinition, groupKey)) {
			return inferFromOpposite(depth, graph, edge.toNodeId(), toDefinition, PortDirection.INPUT, visiting, portVisiting);
		}
		if (matchesGroup(edge.toNodeId(), toDefinition, groupKey)) {
			return inferFromOpposite(depth, graph, edge.fromNodeId(), fromDefinition, PortDirection.OUTPUT, visiting, portVisiting);
		}
		return null;
	}

	private PortType<?> inferFromOpposite(
		int depth,
		GraphDefinition graph,
		NodeId nodeId,
		PortDefinition definition,
		PortDirection direction,
		Set<ScopedGroupKey> visiting,
		Set<ScopedPortRef> portVisiting
	) {
		if (definition.genericGroupId() != null) {
			PortType<?> resolved = resolveGenericGroupType(depth, graph, new GroupKey(nodeId, definition.genericGroupId()), visiting, portVisiting);
			if (resolved != null && !resolved.equals(com.github.squi2rel.mcng.core.MCNGPortTypes.ANY)) {
				return resolved;
			}

			PortType<?> parentResolved = resolveFromParentSubgraphContext(depth, nodeId, definition.id(), direction, portVisiting);
			if (parentResolved != null && !parentResolved.equals(com.github.squi2rel.mcng.core.MCNGPortTypes.ANY)) {
				return parentResolved;
			}
			return null;
		}
		if (definition.numericFamily() && direction == PortDirection.INPUT) {
			return null;
		}
		ResolvedPortType resolved = resolvePortType(depth, graph, nodeId, definition.id(), direction, portVisiting);
		if (resolved.unresolvedGeneric() || resolved.unresolvedNumeric()) {
			return null;
		}
		return resolved.effectiveType();
	}

	private PortType<?> resolveFromParentSubgraphContext(int depth, NodeId nodeId, PortId portId, PortDirection direction, Set<ScopedPortRef> visiting) {
		if (depth == 0 || !new PortId("value").equals(portId)) {
			return null;
		}

		DefinitionState definition = definitionAtDepth(depth);
		if (definition == null || definition.kind != DocumentNodeDefinitionKind.SUBGRAPH) {
			return null;
		}

		NodeInstance node = findNode(graphAtDepth(depth), nodeId);
		if (node == null) {
			return null;
		}

		PortDirection parentDirection;
		if (DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID.equals(node.typeId()) && direction == PortDirection.OUTPUT) {
			parentDirection = PortDirection.INPUT;
		} else if (DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID.equals(node.typeId()) && direction == PortDirection.INPUT) {
			parentDirection = PortDirection.OUTPUT;
		} else {
			return null;
		}

		NodeId parentNodeId = contextNodePath.get(depth - 1);
		ResolvedPortType resolution = resolvePortType(depth - 1, graphAtDepth(depth - 1), parentNodeId, new PortId(node.id().value()), parentDirection, visiting);
		return resolution.unresolvedGeneric() || resolution.unresolvedNumeric() ? null : resolution.effectiveType();
	}

	private RerouteOrientation legacyRerouteOrientation(NodeId nodeId, JsonObject config) {
		Boolean legacyVertical = explicitLegacyRerouteVertical(config);
		boolean vertical = legacyVertical != null && legacyVertical;
		Boolean legacyFlipped = explicitLegacyRerouteFlip(config);
		NodeWidget.PortSide inputSide = legacyFlipped != null
			? legacyInputSide(vertical, legacyFlipped)
			: inferRerouteInputSide(nodeId, vertical);
		return RerouteOrientation.fromInputSide(inputSide);
	}

	private NodeWidget.PortSide legacyInputSide(boolean vertical, boolean flipped) {
		if (vertical) {
			return flipped ? NodeWidget.PortSide.BOTTOM : NodeWidget.PortSide.TOP;
		}
		return flipped ? NodeWidget.PortSide.RIGHT : NodeWidget.PortSide.LEFT;
	}

	private NodeWidget.PortSide inferRerouteInputSide(NodeId nodeId, boolean vertical) {
		NodePosition position = currentState().positions.getOrDefault(nodeId, new NodePosition(0, 0));
		int score = 0;
		for (EdgeDefinition edge : currentState().edges) {
			if (edge.toNodeId().equals(nodeId) && edge.toPortId().equals(BuiltinNodeTypes.VALUE_PORT)) {
				NodePosition source = currentState().positions.getOrDefault(edge.fromNodeId(), new NodePosition(0, 0));
				score += vertical
					? Double.compare(source.y(), position.y())
					: Double.compare(source.x(), position.x());
			}
			if (edge.fromNodeId().equals(nodeId) && edge.fromPortId().equals(BuiltinNodeTypes.VALUE_PORT)) {
				NodePosition target = currentState().positions.getOrDefault(edge.toNodeId(), new NodePosition(0, 0));
				score -= vertical
					? Double.compare(target.y(), position.y())
					: Double.compare(target.x(), position.x());
			}
		}
		if (vertical) {
			return score > 0 ? NodeWidget.PortSide.BOTTOM : NodeWidget.PortSide.TOP;
		}
		return score > 0 ? NodeWidget.PortSide.RIGHT : NodeWidget.PortSide.LEFT;
	}

	private boolean setRerouteOrientation(NodeId nodeId, RerouteOrientation orientation) {
		NodeInstance node = currentState().nodes.get(nodeId);
		if (!isReroute(nodeId, node)) {
			return false;
		}
		RerouteOrientation current = explicitRerouteOrientation(node.config());
		if (current == orientation) {
			return false;
		}
		JsonObject updated = node.config().deepCopy();
		updated.addProperty(REROUTE_ORIENTATION_KEY, orientation.id());
		updated.remove(LEGACY_REROUTE_FLIPPED_KEY);
		updated.remove(LEGACY_REROUTE_VERTICAL_KEY);
		currentState().nodes.put(nodeId, new NodeInstance(node.id(), node.typeId(), updated));
		return true;
	}

	private boolean isReroute(NodeId nodeId, NodeInstance node) {
		return node != null
			&& currentState().nodes.containsKey(nodeId)
			&& BuiltinNodeTypes.REROUTE.id().equals(node.typeId());
	}

	private static RerouteOrientation explicitRerouteOrientation(JsonObject config) {
		if (config == null || !config.has(REROUTE_ORIENTATION_KEY)) {
			return null;
		}
		try {
			return RerouteOrientation.parse(config.get(REROUTE_ORIENTATION_KEY).getAsString());
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static Boolean explicitLegacyRerouteVertical(JsonObject config) {
		if (config == null || !config.has(LEGACY_REROUTE_VERTICAL_KEY)) {
			return null;
		}
		try {
			return config.get(LEGACY_REROUTE_VERTICAL_KEY).getAsBoolean();
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static Boolean explicitLegacyRerouteFlip(JsonObject config) {
		if (config == null || !config.has(LEGACY_REROUTE_FLIPPED_KEY)) {
			return null;
		}
		try {
			return config.get(LEGACY_REROUTE_FLIPPED_KEY).getAsBoolean();
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static PortDirection opposite(PortDirection direction) {
		return direction == PortDirection.INPUT ? PortDirection.OUTPUT : PortDirection.INPUT;
	}

	private NodeInstance requireNode(NodeId nodeId) {
		NodeInstance node = currentState().nodes.get(nodeId);
		if (node == null) {
			throw new IllegalArgumentException("Unknown node " + nodeId);
		}
		return node;
	}

	private PortDefinition requirePort(NodeInstance node, PortId portId, PortDirection direction) {
		List<PortDefinition> ports = direction == PortDirection.INPUT ? nodeType(node).inputs(node) : nodeType(node).outputs(node);
		return ports.stream()
			.filter(port -> port.id().equals(portId))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown " + direction + " port " + portId + " on " + node.typeId()));
	}

	private void pruneInvalidNodePorts(NodeId nodeId) {
		NodeInstance node = currentState().nodes.get(nodeId);
		if (node == null) {
			return;
		}
		NodeType<?> nodeType = runtimeRegistry().find(node.typeId()).orElse(null);
		if (nodeType == null) {
			return;
		}

		Set<PortId> validInputs = nodeType.inputs(node).stream()
			.map(PortDefinition::id)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<PortId> validOutputs = nodeType.outputs(node).stream()
			.map(PortDefinition::id)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

		currentState().edges.removeIf(edge ->
			(edge.toNodeId().equals(nodeId) && !validInputs.contains(edge.toPortId()))
				|| (edge.fromNodeId().equals(nodeId) && !validOutputs.contains(edge.fromPortId()))
		);
		if (pendingConnection != null && pendingConnection.nodeId().equals(nodeId)) {
			boolean valid = pendingConnection.direction() == PortDirection.INPUT
				? validInputs.contains(pendingConnection.portId())
				: validOutputs.contains(pendingConnection.portId());
			if (!valid) {
				pendingConnection = null;
			}
		}
	}

	private NodeInstance findNode(GraphDefinition graph, NodeId nodeId) {
		return graph.nodes().stream().filter(candidate -> candidate.id().equals(nodeId)).findFirst().orElse(null);
	}

	private PortDefinition requirePortDefinition(GraphDefinition graph, NodeId nodeId, PortId portId, PortDirection direction) {
		PortDefinition definition = findPortDefinition(graph, nodeId, portId, direction);
		if (definition == null) {
			throw new IllegalArgumentException("Unknown " + direction + " port " + portId + " on " + nodeId);
		}
		return definition;
	}

	private PortDefinition findPortDefinition(GraphDefinition graph, NodeId nodeId, PortId portId, PortDirection direction) {
		NodeInstance node = findNode(graph, nodeId);
		if (node == null) {
			return null;
		}
		NodeType<?> nodeType = runtimeRegistry().find(node.typeId()).orElse(null);
		if (nodeType == null) {
			return null;
		}
		List<PortDefinition> ports = direction == PortDirection.INPUT ? nodeType.inputs(node) : nodeType.outputs(node);
		return ports.stream().filter(port -> port.id().equals(portId)).findFirst().orElse(null);
	}

	private boolean matchesGroup(NodeId nodeId, PortDefinition definition, GroupKey groupKey) {
		return definition.genericGroupId() != null
			&& nodeId.equals(groupKey.nodeId())
			&& definition.genericGroupId().equals(groupKey.groupId());
	}

	private boolean isRerouteGroup(GraphDefinition graph, GroupKey groupKey) {
		NodeInstance node = findNode(graph, groupKey.nodeId());
		if (!isReroute(groupKey.nodeId(), node)) {
			return false;
		}
		PortDefinition input = findPortDefinition(graph, groupKey.nodeId(), BuiltinNodeTypes.VALUE_PORT, PortDirection.INPUT);
		return input != null && groupKey.groupId().equals(input.genericGroupId());
	}

	@SuppressWarnings("unchecked")
	private NodeInstance createNode(NodeId nodeId, NodeType<?> nodeType) {
		NodeType<Object> typed = (NodeType<Object>) nodeType;
		return new NodeInstance(nodeId, typed.id(), typed.configCodec().toJson(typed.defaultConfig()));
	}

	private NodeInstance createGraphPortNode(NodeId nodeId, NodeType<JsonObject> nodeType, String defaultName) {
		JsonObject config = nodeType.defaultConfig().deepCopy();
		config.addProperty("name", defaultName);
		return new NodeInstance(nodeId, nodeType.id(), config);
	}

	private void loadDocumentState(GraphDocument document) {
		root.load(document.graph(), document.layout());
		definitions.clear();
		definitionParents.clear();
		for (DocumentNodeDefinition definition : document.definitions()) {
			loadDefinition(definition, null);
		}
		loadSubgraphs(document.rootScope().subgraphs(), null);
		rootVariables.clear();
		for (GraphVariableDefinition variable : document.variables()) {
			rootVariables.put(variable.id(), variable);
		}
	}

	private void resetForLoadedDocument(boolean clearLocalClipboard) {
		contextPath.clear();
		contextNodePath.clear();
		debugMessages.clear();
		pendingConnection = null;
		clearSelection();
		if (!root.nodes.isEmpty()) {
			selectNode(root.nodes.values().iterator().next().id());
		}
		if (clearLocalClipboard) {
			localClipboard = null;
		}
	}

	private void applySnapshot(EditorSnapshot snapshot) {
		loadDocumentState(snapshot.document());
		contextPath.clear();
		contextNodePath.clear();
		restoreContext(snapshot.contextPath(), snapshot.contextNodePath());
		restoreSelection(snapshot.selectedNodeIds(), snapshot.primarySelectedNodeId());
		debugMessages.clear();
		pendingConnection = null;
		refreshRuntime(snapshot.document());
	}

	private void restoreContext(List<String> targetContextPath, List<NodeId> targetContextNodePath) {
		MutableGraphState scope = root;
		int limit = Math.min(targetContextPath.size(), targetContextNodePath.size());
		for (int index = 0; index < limit; index++) {
			String definitionId = targetContextPath.get(index);
			NodeId containerNodeId = targetContextNodePath.get(index);
			DefinitionState definition = definitions.get(definitionId);
			if (definition == null) {
				break;
			}
			NodeInstance containerNode = scope.nodes.get(containerNodeId);
			if (containerNode == null || !matchesDefinitionNode(containerNode, definitionId)) {
				break;
			}
			contextPath.add(definitionId);
			contextNodePath.add(containerNodeId);
			scope = definition.graphState;
		}
	}

	private boolean matchesDefinitionNode(NodeInstance node, String definitionId) {
		return DocumentNodeTypes.definitionTypeId(definitionId).equals(node.typeId())
			|| DocumentNodeTypes.subgraphTypeId(definitionId).equals(node.typeId());
	}

	private void restoreSelection(List<NodeId> snapshotSelection, NodeId snapshotPrimarySelection) {
		selectedNodeIds.clear();
		primarySelectedNodeId = null;
		for (NodeId nodeId : snapshotSelection) {
			if (currentState().nodes.containsKey(nodeId)) {
				selectedNodeIds.add(nodeId);
			}
		}
		if (snapshotPrimarySelection != null && selectedNodeIds.contains(snapshotPrimarySelection)) {
			primarySelectedNodeId = snapshotPrimarySelection;
		} else {
			primarySelectedNodeId = selectedNodeIds.stream().findFirst().orElse(null);
		}
	}

	private EditorSnapshot captureSnapshot() {
		return captureSnapshot(document());
	}

	private EditorSnapshot captureSnapshot(GraphDocument document) {
		return new EditorSnapshot(
			document,
			List.copyOf(contextPath),
			List.copyOf(contextNodePath),
			List.copyOf(selectedNodeIds),
			primarySelectedNodeId
		);
	}

	private void pushHistory(Deque<HistoryEntry> stack, HistoryEntry entry) {
		if (entry == null) {
			return;
		}
		stack.addLast(entry);
		while (stack.size() > MAX_HISTORY_ENTRIES) {
			stack.removeFirst();
		}
	}

	private void clearHistoryState() {
		undoStack.clear();
		redoStack.clear();
		compositeEditDepth = 0;
		compositeEditStart = null;
		compositeEditLatest = null;
		compositeEditChanged = false;
	}

	private void finishOpenCompositeEdit() {
		while (compositeEditDepth > 0) {
			endCompositeEdit();
		}
	}

	private boolean finishMutation(EditorSnapshot before) {
		GraphDocument after = document();
		if (before.document().equals(after)) {
			return false;
		}
		EditorSnapshot afterSnapshot = captureSnapshot(after);
		if (compositeEditDepth > 0) {
			if (!compositeEditChanged) {
				redoStack.clear();
			}
			compositeEditChanged = true;
			compositeEditLatest = afterSnapshot;
		} else {
			pushHistory(undoStack, new HistoryEntry(before, afterSnapshot));
			redoStack.clear();
		}
		persist(after);
		return true;
	}

	private void loadDefinition(DocumentNodeDefinition definition, ParentScopeRef parent) {
		DefinitionState state = new DefinitionState(
			definition.id(),
			definition.displayName(),
			DocumentNodeDefinitionKind.CUSTOM_NODE,
			new MutableGraphState(definition.graph(), definition.layout())
		);
		for (GraphVariableDefinition variable : definition.variables()) {
			state.variables.put(variable.id(), variable);
		}
		definitions.put(definition.id(), state);
		definitionParents.put(definition.id(), parent);
		loadSubgraphs(definition.subgraphs(), new ParentScopeRef(definition.id()));
	}

	private void loadSubgraphs(List<SubgraphDefinition> subgraphs, ParentScopeRef parent) {
		for (SubgraphDefinition subgraph : subgraphs) {
			DefinitionState state = new DefinitionState(
				subgraph.id(),
				subgraph.displayName(),
				DocumentNodeDefinitionKind.SUBGRAPH,
				new MutableGraphState(subgraph.graph(), subgraph.layout())
			);
			for (GraphVariableDefinition variable : subgraph.scope().variables()) {
				state.variables.put(variable.id(), variable);
			}
			definitions.put(subgraph.id(), state);
			definitionParents.put(subgraph.id(), parent);
			loadSubgraphs(subgraph.scope().subgraphs(), new ParentScopeRef(subgraph.id()));
		}
	}

	private DocumentNodeDefinition toCustomDefinition(DefinitionState definition) {
		return new DocumentNodeDefinition(
			definition.id,
			definition.displayName,
			new GraphScope(
				definition.graphState.graph(),
				definition.graphState.layout(),
				new ArrayList<>(definition.variables.values()),
				subgraphsForParent(new ParentScopeRef(definition.id))
			)
		);
	}

	private List<SubgraphDefinition> subgraphsForParent(ParentScopeRef parent) {
		List<SubgraphDefinition> subgraphs = new ArrayList<>();
		for (DefinitionState definition : definitions.values()) {
			if (definition.kind != DocumentNodeDefinitionKind.SUBGRAPH) {
				continue;
			}
			if (!Objects.equals(definitionParents.get(definition.id), parent)) {
				continue;
			}
			subgraphs.add(new SubgraphDefinition(
				definition.id,
				definition.displayName,
				new GraphScope(
					definition.graphState.graph(),
					definition.graphState.layout(),
					new ArrayList<>(definition.variables.values()),
					subgraphsForParent(new ParentScopeRef(definition.id))
				)
			));
		}
		return subgraphs;
	}

	private PortType<?> visibleVariableType(int depth, String variableId) {
		DefinitionState definition = depth <= 0 ? null : definitionAtDepth(depth);
		if (definition != null) {
			GraphVariableDefinition local = definition.variables.get(variableId);
			if (local != null) {
				return portTypes.findType(local.typeId()).orElse(null);
			}
		}
		for (int index = depth - 1; index >= 1; index--) {
			DefinitionState ancestor = definitionAtDepth(index);
			if (ancestor == null || ancestor.kind != DocumentNodeDefinitionKind.SUBGRAPH) {
				continue;
			}
			GraphVariableDefinition local = ancestor.variables.get(variableId);
			if (local != null) {
				return portTypes.findType(local.typeId()).orElse(null);
			}
		}
		GraphVariableDefinition rootVariable = rootVariables.get(variableId);
		return rootVariable != null ? portTypes.findType(rootVariable.typeId()).orElse(null) : null;
	}

	private MutableGraphState currentState() {
		DefinitionState definition = currentDefinition();
		return definition == null ? root : definition.graphState;
	}

	private DefinitionState currentDefinition() {
		return contextPath.isEmpty() ? null : definitions.get(contextPath.getLast());
	}

	private Map<String, GraphVariableDefinition> currentVariableState() {
		DefinitionState definition = currentDefinition();
		return definition == null ? rootVariables : definition.variables;
	}

	private ParentScopeRef currentParentRef() {
		return contextPath.isEmpty() ? null : new ParentScopeRef(contextPath.getLast());
	}

	private int currentDepth() {
		return contextPath.size();
	}

	private DefinitionState definitionAtDepth(int depth) {
		return depth <= 0 ? null : definitions.get(contextPath.get(depth - 1));
	}

	private GraphDefinition graphAtDepth(int depth) {
		return depth == 0 ? root.graph() : definitionAtDepth(depth).graphState.graph();
	}

	private String nextDefinitionName(DocumentNodeDefinitionKind kind) {
		String prefix = kind == DocumentNodeDefinitionKind.SUBGRAPH
			? translate("mcng.ui.default_name.subgraph", "Subgraph")
			: translate("mcng.ui.default_name.custom_node", "Custom Node");
		int index = 1;
		Set<String> used = definitions.values().stream().map(definition -> definition.displayName).collect(LinkedHashSet::new, Set::add, Set::addAll);
		while (used.contains(prefix + " " + index)) {
			index++;
		}
		return prefix + " " + index;
	}

	private String nextVariableId() {
		int index = 1;
		while (currentVariableState().containsKey("var_" + index)) {
			index++;
		}
		return "var_" + index;
	}

	private JsonElement defaultVariableValue(String typeId) {
		if (MCNGPortTypes.LONG.id().equals(typeId)) {
			return NodeConfigValues.longValue(0L);
		}
		if (MCNGPortTypes.DOUBLE.id().equals(typeId)) {
			return NodeConfigValues.doubleValue(0.0);
		}
		if (MCNGPortTypes.STRING.id().equals(typeId)) {
			return NodeConfigValues.stringValue("");
		}
		if (MCNGPortTypes.BOOLEAN.id().equals(typeId)) {
			return NodeConfigValues.booleanValue(false);
		}
		if (MCNGPortTypes.ANY.id().equals(typeId)) {
			return NodeConfigValues.stringValue("");
		}
		return NodeConfigValues.intValue(0);
	}

	private Bounds selectionBounds(Set<NodeId> selection, Map<NodeId, NodePosition> positions, Map<NodeId, NodeSize> sizes) {
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (NodeId nodeId : selection) {
			NodePosition position = positions.getOrDefault(nodeId, new NodePosition(0, 0));
			NodeSize size = sizes.getOrDefault(nodeId, DEFAULT_SELECTION_NODE_SIZE);
			minX = Math.min(minX, position.x());
			minY = Math.min(minY, position.y());
			maxX = Math.max(maxX, position.x() + size.width());
			maxY = Math.max(maxY, position.y() + size.height());
		}
		return new Bounds(minX, minY, maxX, maxY);
	}

	private void persist() {
		persist(document());
	}

	private void persist(GraphDocument document) {
		refreshRuntime(document);
		host.onDocumentChanged(document);
	}

	private void cancelExecutionSilently() {
		if (runningExecution != null) {
			runningExecution.cancel();
			runningExecution = null;
		}
		lastExecutionSnapshot = emptySnapshot();
		refreshExecutionHighlights();
	}

	private void refreshExecutionHighlights() {
		if (lastExecutionSnapshot.frontier().isEmpty()) {
			executingVisibleNodeIds = Set.of();
			executingVisibleRerouteNodeIds = Set.of();
			return;
		}

		Set<NodeId> visibleNodeIds = new LinkedHashSet<>();
		for (ExecutionPosition position : lastExecutionSnapshot.frontier()) {
			NodeId visibleNodeId = visibleNodeFor(position);
			if (visibleNodeId != null) {
				visibleNodeIds.add(visibleNodeId);
			}
		}
		executingVisibleNodeIds = Set.copyOf(visibleNodeIds);
		executingVisibleRerouteNodeIds = collectExecutingVisibleReroutes(visibleNodeIds);
	}

	private Set<NodeId> collectExecutingVisibleReroutes(Set<NodeId> activeVisibleNodes) {
		if (activeVisibleNodes.isEmpty()) {
			return Set.of();
		}

		GraphDefinition graph = currentState().graph();
		Set<NodeId> activeReroutes = new LinkedHashSet<>();
		ArrayList<NodeId> queue = new ArrayList<>();
		for (EdgeDefinition edge : graph.edges()) {
			if (BuiltinNodeTypes.VALUE_PORT.equals(edge.toPortId())
				&& activeVisibleNodes.contains(edge.fromNodeId())
				&& isReroute(edge.toNodeId(), currentState().nodes.get(edge.toNodeId()))) {
				queue.add(edge.toNodeId());
			}
			if (BuiltinNodeTypes.VALUE_PORT.equals(edge.fromPortId())
				&& activeVisibleNodes.contains(edge.toNodeId())
				&& isReroute(edge.fromNodeId(), currentState().nodes.get(edge.fromNodeId()))) {
				queue.add(edge.fromNodeId());
			}
		}

		int index = 0;
		while (index < queue.size()) {
			NodeId rerouteNodeId = queue.get(index++);
			if (!activeReroutes.add(rerouteNodeId)) {
				continue;
			}
			for (EdgeDefinition edge : graph.edges()) {
				PortRef anchor = rerouteOpposite(edge, rerouteNodeId);
				if (anchor == null) {
					continue;
				}
				NodeInstance oppositeNode = currentState().nodes.get(anchor.nodeId());
				if (isReroute(anchor.nodeId(), oppositeNode)) {
					queue.add(anchor.nodeId());
				}
			}
		}
		return Set.copyOf(activeReroutes);
	}

	private NodeId visibleNodeFor(ExecutionPosition position) {
		if (position.definitionPath().equals(contextPath)) {
			return visibleNodeIdFromRuntimeNode(position.nodeId(), currentPlanContextPath());
		}
		if (position.definitionPath().size() < contextPath.size()) {
			return null;
		}
		for (int index = 0; index < contextPath.size(); index++) {
			if (!contextPath.get(index).equals(position.definitionPath().get(index))) {
				return null;
			}
		}
		int currentCustomDepth = currentCustomDepth();
		if (position.invocationPath().size() > currentCustomDepth) {
			return position.invocationPath().get(currentCustomDepth);
		}
		if (position.definitionPath().size() > contextPath.size()) {
			String nextScopeId = position.definitionPath().get(contextPath.size());
			return containerNodeIdForSubgraph(nextScopeId);
		}
		return visibleNodeIdFromRuntimeNode(position.nodeId(), currentPlanContextPath());
	}

	private int currentCustomDepth() {
		int depth = 0;
		for (String definitionId : contextPath) {
			DefinitionState definition = definitions.get(definitionId);
			if (definition != null && definition.kind == DocumentNodeDefinitionKind.CUSTOM_NODE) {
				depth++;
			}
		}
		return depth;
	}

	private String currentCustomDefinitionId() {
		for (int index = contextPath.size() - 1; index >= 0; index--) {
			DefinitionState definition = definitions.get(contextPath.get(index));
			if (definition != null && definition.kind == DocumentNodeDefinitionKind.CUSTOM_NODE) {
				return definition.id;
			}
		}
		return null;
	}

	private List<String> currentPlanContextPath() {
		String customDefinitionId = currentCustomDefinitionId();
		if (customDefinitionId == null) {
			return List.copyOf(contextPath);
		}
		int index = contextPath.indexOf(customDefinitionId);
		return index < 0 ? List.of() : List.copyOf(contextPath.subList(index + 1, contextPath.size()));
	}

	private NodeId containerNodeIdForSubgraph(String subgraphId) {
		String typeId = DocumentNodeTypes.subgraphTypeId(subgraphId);
		return currentState().nodes.values().stream()
			.filter(node -> typeId.equals(node.typeId()))
			.map(NodeInstance::id)
			.findFirst()
			.orElse(null);
	}

	private NodeId visibleNodeIdFromRuntimeNode(NodeId runtimeNodeId, List<String> planContext) {
		if (planContext.isEmpty()) {
			return runtimeNodeId;
		}
		String prefix = FLAT_NODE_PREFIX + String.join("/", planContext) + "/";
		return runtimeNodeId.value().startsWith(prefix)
			? new NodeId(runtimeNodeId.value().substring(prefix.length()))
			: runtimeNodeId;
	}

	private static ExecutionSnapshot emptySnapshot() {
		return new ExecutionSnapshot(Map.of(), Map.of(), List.of(), List.of(), List.of(), ExecutionSessionStatus.COMPLETED);
	}

	public record PendingConnection(NodeId nodeId, PortId portId, PortDirection direction) {
	}

	public record Breadcrumb(String label, String definitionId) {
	}

	private record EditorSnapshot(
		GraphDocument document,
		List<String> contextPath,
		List<NodeId> contextNodePath,
		List<NodeId> selectedNodeIds,
		NodeId primarySelectedNodeId
	) {
		private boolean matches(
			GraphDocument document,
			List<String> contextPath,
			List<NodeId> contextNodePath,
			Set<NodeId> selectedNodeIds,
			NodeId primarySelectedNodeId
		) {
			return this.document.equals(document)
				&& this.contextPath.equals(contextPath)
				&& this.contextNodePath.equals(contextNodePath)
				&& this.selectedNodeIds.equals(List.copyOf(selectedNodeIds))
				&& Objects.equals(this.primarySelectedNodeId, primarySelectedNodeId);
		}
	}

	private record HistoryEntry(EditorSnapshot before, EditorSnapshot after) {
	}

	private static final class MutableGraphState {
		private final Map<NodeId, NodeInstance> nodes = new LinkedHashMap<>();
		private final Map<NodeId, NodePosition> positions = new LinkedHashMap<>();
		private final Map<NodeId, NodeSize> sizes = new LinkedHashMap<>();
		private final List<EdgeDefinition> edges = new ArrayList<>();

		private MutableGraphState() {
		}

		private MutableGraphState(GraphDefinition graph, GraphLayout layout) {
			load(graph, layout);
		}

		private void load(GraphDefinition graph, GraphLayout layout) {
			nodes.clear();
			positions.clear();
			sizes.clear();
			edges.clear();
			graph.nodes().forEach(node -> nodes.put(node.id(), node));
			positions.putAll(layout.nodePositions());
			sizes.putAll(layout.nodeSizes());
			edges.addAll(graph.edges());
		}

		private GraphDefinition graph() {
			return new GraphDefinition(new ArrayList<>(nodes.values()), new ArrayList<>(edges));
		}

		private GraphLayout layout() {
			return new GraphLayout(new LinkedHashMap<>(positions), new LinkedHashMap<>(sizes));
		}
	}

	private static final class DefinitionState {
		private final String id;
		private String displayName;
		private final DocumentNodeDefinitionKind kind;
		private final MutableGraphState graphState;
		private final Map<String, GraphVariableDefinition> variables = new LinkedHashMap<>();

		private DefinitionState(String id, String displayName, DocumentNodeDefinitionKind kind, MutableGraphState graphState) {
			this.id = id;
			this.displayName = displayName;
			this.kind = kind;
			this.graphState = graphState;
		}
	}

	private record ParentScopeRef(String id) {
	}

	private record PortIdKey(NodeId nodeId, PortId portId) {
	}

	private record LocalClipboard(GraphDefinition graph, GraphLayout layout, List<ClipboardDefinition> definitions) {
	}

	private record ClipboardDefinition(String sourceId, String displayName, GraphScope scope) {
	}

	private record GroupKey(NodeId nodeId, String groupId) {
	}

	private record ScopedGroupKey(int depth, GroupKey groupKey) {
	}

	private record PortRef(NodeId nodeId, PortId portId, PortDirection direction) {
	}

	private record RerouteComponent(Set<NodeId> rerouteNodeIds, List<PortRef> anchors) {
	}

	private record ScopedPortRef(int depth, PortRef portRef) {
	}

	private record Bounds(double minX, double minY, double maxX, double maxY) {
		private double width() {
			return maxX - minX;
		}

		private double centerX() {
			return (minX + maxX) / 2.0;
		}

		private double centerY() {
			return (minY + maxY) / 2.0;
		}
	}

	private final class RecordingExecutionContext implements com.github.squi2rel.mcng.core.NodeExecutionContext {
		@Override
		public void publishDebug(NodeId nodeId, String message) {
			debugMessages.add(nodeId.value() + ": " + message);
		}
	}
}
