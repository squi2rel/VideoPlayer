package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NodeType;
import com.google.gson.JsonObject;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Objects;
import java.util.Optional;

public record NodeBodyRenderContext(
	DrawContext drawContext,
	TextRenderer textRenderer,
	NodeWidget.Bounds bounds,
	NodeInstance node,
	NodeType<?> nodeType,
	Optional<GraphEditorSession> session,
	GraphEditorI18n i18n,
	GraphEditorUiConfig uiConfig,
	GraphEditorTheme theme,
	double zoom,
	boolean selected,
	boolean executing,
	boolean hasError,
	boolean preview
) {
	public NodeBodyRenderContext {
		Objects.requireNonNull(drawContext, "drawContext");
		Objects.requireNonNull(textRenderer, "textRenderer");
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(nodeType, "nodeType");
		Objects.requireNonNull(session, "session");
		Objects.requireNonNull(i18n, "i18n");
		Objects.requireNonNull(uiConfig, "uiConfig");
		Objects.requireNonNull(theme, "theme");
	}

	public JsonObject configCopy() {
		return node.config().deepCopy();
	}

	public String translate(String key, String fallback, Object... args) {
		return i18n.translate(key, fallback, args);
	}
}
