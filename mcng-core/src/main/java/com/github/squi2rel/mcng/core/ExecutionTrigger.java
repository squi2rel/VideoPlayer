package com.github.squi2rel.mcng.core;

public record ExecutionTrigger(ExecutionTriggerKind kind, NodeId sourceNodeId, Object payload) {
	public static ExecutionTrigger fullEvaluation() {
		return new ExecutionTrigger(ExecutionTriggerKind.FULL_EVALUATION, null, null);
	}

	public static ExecutionTrigger control() {
		return new ExecutionTrigger(ExecutionTriggerKind.CONTROL, null, null);
	}

	public static ExecutionTrigger event(NodeId sourceNodeId, Object payload) {
		return new ExecutionTrigger(ExecutionTriggerKind.EVENT, sourceNodeId, payload);
	}
}
