package com.github.squi2rel.mcng.core;

import java.util.Map;

public record GraphLayout(Map<NodeId, NodePosition> nodePositions, Map<NodeId, NodeSize> nodeSizes) {
	public GraphLayout {
		nodePositions = Map.copyOf(nodePositions);
		nodeSizes = Map.copyOf(nodeSizes);
	}

	public GraphLayout(Map<NodeId, NodePosition> nodePositions) {
		this(nodePositions, Map.of());
	}
}
