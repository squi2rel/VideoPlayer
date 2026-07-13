package com.github.squi2rel.mcng.core;

import java.util.Objects;

public record EdgeDefinition(NodeId fromNodeId, PortId fromPortId, NodeId toNodeId, PortId toPortId) {
	public EdgeDefinition {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		Objects.requireNonNull(fromPortId, "fromPortId");
		Objects.requireNonNull(toNodeId, "toNodeId");
		Objects.requireNonNull(toPortId, "toPortId");
	}
}
