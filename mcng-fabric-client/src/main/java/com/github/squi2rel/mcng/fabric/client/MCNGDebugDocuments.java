package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphBuilder;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.ManualTriggerConfig;

public final class MCNGDebugDocuments {
	private MCNGDebugDocuments() {
	}

	public static GraphDocument createDefaultDocument(NodeTypeRegistry registry, PortTypeRegistry portTypes) {
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		var add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(40, 70));
		var resultDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 70));
		var trigger = builder.addNode(BuiltinNodeTypes.MANUAL_TRIGGER, new ManualTriggerConfig("pulse", true, "boxed"), new NodePosition(40, 250));
		var triggerDebug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(320, 300));

		builder.setInlineInput(add, "left", NodeConfigValues.numberValue(2.0));
		builder.setInlineInput(add, "right", NodeConfigValues.numberValue(3.0));
		builder.addEdge(add, "value", resultDebug, "value");
		builder.addEdge(trigger, "message", triggerDebug, "value");
		return builder.buildDocument();
	}
}
