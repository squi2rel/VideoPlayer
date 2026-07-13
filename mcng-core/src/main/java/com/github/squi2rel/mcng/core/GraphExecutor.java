package com.github.squi2rel.mcng.core;

import java.util.List;
import java.util.Objects;

public final class GraphExecutor {
	private final NodeTypeRegistry registry;
	private final PortTypeRegistry portTypes;
	private final GraphExecutionOptions options;

	public GraphExecutor(NodeTypeRegistry registry, PortTypeRegistry portTypes) {
		this(registry, portTypes, GraphExecutionOptions.DEFAULT);
	}

	public GraphExecutor(NodeTypeRegistry registry, PortTypeRegistry portTypes, GraphExecutionOptions options) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.portTypes = Objects.requireNonNull(portTypes, "portTypes");
		this.options = Objects.requireNonNull(options, "options");
	}

	public CompiledGraphDocument compile(GraphDocument document) {
		return CompiledGraphDocument.compile(registry, portTypes, document, options);
	}

	public ExecutionResult execute(GraphDefinition graph, NodeExecutionContext context) {
		return execute(graph, List.of(), context);
	}

	public ExecutionResult execute(GraphDefinition graph, List<GraphVariableDefinition> variables, NodeExecutionContext context) {
		return compileDocument(graph, variables).startExecution(context).runToCompletion().toExecutionResult();
	}

	public ExecutionResult execute(GraphDefinition graph, NodeExecutionContext context, ExecutionRuntime runtime) {
		return GraphExecutionSession.startDataflow(compileDocument(graph, List.of()), context, runtime).runToCompletion().toExecutionResult();
	}

	public ExecutionResult triggerEvent(GraphDefinition graph, NodeId sourceNodeId, Object payload, NodeExecutionContext context) {
		return triggerEvent(graph, List.of(), sourceNodeId, payload, context);
	}

	public ExecutionResult triggerEvent(GraphDefinition graph, List<GraphVariableDefinition> variables, NodeId sourceNodeId, Object payload, NodeExecutionContext context) {
		return compileDocument(graph, variables).startEventExecution(sourceNodeId, payload, context).runToCompletion().toExecutionResult();
	}

	public ExecutionResult triggerEvent(GraphDefinition graph, NodeId sourceNodeId, Object payload, NodeExecutionContext context, ExecutionRuntime runtime) {
		return GraphExecutionSession.startEvent(compileDocument(graph, List.of()), sourceNodeId, payload, context, runtime).runToCompletion().toExecutionResult();
	}

	public ExecutionResult activateControl(GraphDefinition graph, NodeId sourceNodeId, NodeExecutionContext context, ExecutionRuntime runtime) {
		return GraphExecutionSession.startControl(compileDocument(graph, List.of()), sourceNodeId, context, runtime).runToCompletion().toExecutionResult();
	}

	private CompiledGraphDocument compileDocument(GraphDefinition graph, List<GraphVariableDefinition> variables) {
		GraphDocument document = GraphDocument.of(
			graph,
			new GraphLayout(java.util.Map.of()),
			DocumentNodeTypes.definitionsFromRegistry(registry),
			variables
		);
		return compile(document);
	}
}
