package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodeType;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.Optional;

public record NodeBodyMeasureContext(
	NodeInstance node,
	NodeType<?> nodeType,
	Optional<GraphEditorSession> session,
	GraphEditorI18n i18n,
	GraphEditorUiConfig uiConfig,
	GraphEditorTheme theme,
	double zoom,
	boolean preview,
	int availableWidth
) {
	public NodeBodyMeasureContext {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(nodeType, "nodeType");
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(i18n, "i18n");
		Objects.requireNonNull(uiConfig, "uiConfig");
		Objects.requireNonNull(theme, "theme");
		if (availableWidth <= 0) {
			throw new IllegalArgumentException("availableWidth must be positive");
		}
	}

	public JsonObject configCopy() {
		return node.config().deepCopy();
	}

	public String translate(String key, String fallback, Object... args) {
		return i18n.translate(key, fallback, args);
	}
}
