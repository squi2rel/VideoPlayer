package com.github.squi2rel.mcng.core;

public interface NodeExecutionContext {
	void publishDebug(NodeId nodeId, String message);

	default Object resolveGraphInput(NodeId nodeId) {
		return null;
	}
}
