package com.github.squi2rel.mcng.core;

public record GraphError(GraphErrorCode code, String message, NodeId nodeId, PortId portId) {
	public static GraphError of(GraphErrorCode code, String message, NodeId nodeId, PortId portId) {
		return new GraphError(code, message, nodeId, portId);
	}
}
