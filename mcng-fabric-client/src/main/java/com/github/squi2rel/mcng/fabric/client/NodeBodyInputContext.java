package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodeType;
import com.google.gson.JsonObject;

import java.util.Optional;
import java.util.Objects;

public record NodeBodyInputContext(
	NodeWidget.Bounds bounds,
	NodeInstance node,
	NodeType<?> nodeType,
	GraphEditorSession session,
	GraphEditorI18n i18n,
	GraphEditorUiConfig uiConfig,
	GraphEditorTheme theme,
	double zoom
) {
	public NodeBodyInputContext {
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(nodeType, "nodeType");
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(i18n, "i18n");
		Objects.requireNonNull(uiConfig, "uiConfig");
		Objects.requireNonNull(theme, "theme");
	}

	public NodeId nodeId() {
		return node.id();
	}

	public JsonObject configCopy() {
		return node.config().deepCopy();
	}

	public void updateConfig(JsonObject config) {
		session.updateNodeConfig(node.id(), config);
	}

	public boolean supportsFileDialogs() {
		return session.supportsFileDialogs();
	}

	public Optional<String> chooseFile(GraphFileDialogRequest request) {
		return session.chooseFile(request);
	}

	public void showMessage(String message) {
		session.showMessage(message);
	}

	public String translate(String key, String fallback, Object... args) {
		return i18n.translate(key, fallback, args);
	}
}
