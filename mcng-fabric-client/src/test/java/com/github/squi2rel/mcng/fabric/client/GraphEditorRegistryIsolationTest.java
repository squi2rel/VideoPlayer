package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeRegistrar;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorRegistryIsolationTest {
	@Test
	void sessionsCanUseDifferentRuntimeRegistries() {
		NodeTypeRegistry coreOnly = new NodeTypeRegistry();
		BuiltinNodeRegistrar.registerCoreNodes(coreOnly);
		NodeTypeRegistry corePlusDebug = new NodeTypeRegistry();
		BuiltinNodeRegistrar.registerCoreNodes(corePlusDebug);
		BuiltinNodeRegistrar.registerDebugNodes(corePlusDebug);
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();

		GraphEditorSession left = new GraphEditorSession(coreOnly, portTypes, new GraphJsonCodec(), emptyDocument(), new TestHost());
		GraphEditorSession right = new GraphEditorSession(corePlusDebug, portTypes, new GraphJsonCodec(), emptyDocument(), new TestHost());

		assertFalse(left.availableNodeTypes().stream().anyMatch(nodeType -> nodeType.id().equals(com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.DEBUG_OUTPUT.id())));
		assertTrue(right.availableNodeTypes().stream().anyMatch(nodeType -> nodeType.id().equals(com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.DEBUG_OUTPUT.id())));
	}

	private static GraphDocument emptyDocument() {
		return GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()));
	}

	private static final class TestHost implements GraphEditorHost {
		@Override
		public void onDocumentChanged(GraphDocument document) {
		}

		@Override
		public void copyToClipboard(String value) {
		}

		@Override
		public String readClipboard() {
			return "";
		}

		@Override
		public void showMessage(String message) {
		}
	}
}
