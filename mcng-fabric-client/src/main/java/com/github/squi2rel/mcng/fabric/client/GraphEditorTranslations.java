package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.GraphError;
import com.github.squi2rel.mcng.core.GraphErrorCode;
import com.github.squi2rel.mcng.core.NodeEditorControl;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.PortDefinition;

import java.util.Locale;

final class GraphEditorTranslations {
	private static final String UI_PREFIX = "mcng.ui.";
	private static final String NODE_PREFIX = "mcng.node.";
	private static final String ERROR_PREFIX = "mcng.error.code.";
	private static final String PORT_TYPE_PREFIX = "mcng.port_type.";

	private GraphEditorTranslations() {
	}

	static String ui(GraphEditorI18n i18n, String key, String fallback, Object... args) {
		return i18n.translate(UI_PREFIX + key, fallback, args);
	}

	static String nodeTitle(GraphEditorI18n i18n, NodeType<?> nodeType) {
		if (isDynamicDocumentNodeType(nodeType.id())) {
			return DocumentNodeTypes.readDefinitionDisplayName(nodeType);
		}
		return i18n.translate(nodeTitleKey(nodeType.id()), DocumentNodeTypes.readDefinitionDisplayName(nodeType));
	}

	static String portLabel(GraphEditorI18n i18n, NodeType<?> nodeType, PortDefinition port) {
		if (isDynamicDocumentNodeType(nodeType.id())) {
			return port.name();
		}
		return i18n.translate(portKey(nodeType.id(), port.id().value()), port.name());
	}

	static String controlLabel(GraphEditorI18n i18n, NodeType<?> nodeType, NodeEditorControl control) {
		if (DocumentNodeTypes.DEFINITION_NAME_CONTROL_KEY.equals(control.key())) {
			return ui(i18n, "node.definition_name", control.label());
		}
		if (isDynamicDocumentNodeType(nodeType.id())) {
			return control.label();
		}
		return i18n.translate(controlKey(nodeType.id(), control.key()), control.label());
	}

	static String controlOptionLabel(GraphEditorI18n i18n, NodeType<?> nodeType, NodeEditorControl.CycleControl control, NodeEditorControl.Option option) {
		if (isDynamicDocumentNodeType(nodeType.id())) {
			return option.displayName();
		}
		return i18n.translate(controlOptionKey(nodeType.id(), control.key(), option.id()), option.displayName());
	}

	static String paletteSection(GraphEditorI18n i18n, NodePaletteDefinition definition) {
		return definition.sectionTranslationKey() == null
			? definition.sectionTitle()
			: i18n.translate(definition.sectionTranslationKey(), definition.sectionTitle());
	}

	static String errorCode(GraphEditorI18n i18n, GraphErrorCode code) {
		return i18n.translate(ERROR_PREFIX + sanitize(code.name()), humanize(code.name()));
	}

	static String formatError(GraphEditorI18n i18n, GraphError error) {
		String prefix = errorCode(i18n, error.code());
		if (error.message() == null || error.message().isBlank()) {
			return prefix;
		}
		return prefix + ": " + error.message();
	}

	static String shortPortTypeLabel(GraphEditorI18n i18n, String typeId) {
		return i18n.translate(PORT_TYPE_PREFIX + sanitize(typeId) + ".short", fallbackPortTypeLabel(typeId));
	}

	static String nodeTitleKey(String nodeTypeId) {
		return nodePrefix(nodeTypeId) + ".title";
	}

	static String portKey(String nodeTypeId, String portId) {
		return nodePrefix(nodeTypeId) + ".port." + sanitize(portId);
	}

	static String controlKey(String nodeTypeId, String controlKey) {
		return nodePrefix(nodeTypeId) + ".control." + sanitize(controlKey);
	}

	static String controlOptionKey(String nodeTypeId, String controlKey, String optionId) {
		return controlKey(nodeTypeId, controlKey) + ".option." + sanitize(optionId);
	}

	static String sanitize(String raw) {
		if (raw == null || raw.isBlank()) {
			return "unknown";
		}
		String lower = raw.toLowerCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(lower.length());
		boolean dotPending = false;
		for (int index = 0; index < lower.length(); index++) {
			char current = lower.charAt(index);
			if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9')) {
				if (dotPending && !builder.isEmpty()) {
					builder.append('.');
				}
				builder.append(current);
				dotPending = false;
			} else {
				dotPending = true;
			}
		}
		return builder.isEmpty() ? "unknown" : builder.toString();
	}

	private static boolean isDynamicDocumentNodeType(String nodeTypeId) {
		return DocumentNodeTypes.isDefinitionType(nodeTypeId) || DocumentNodeTypes.isSubgraphType(nodeTypeId);
	}

	private static String nodePrefix(String nodeTypeId) {
		return NODE_PREFIX + sanitize(nodeTypeId);
	}

	private static String humanize(String raw) {
		String[] words = raw.split("_+");
		StringBuilder builder = new StringBuilder(raw.length());
		for (String word : words) {
			if (word.isBlank()) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append(' ');
			}
			builder.append(Character.toUpperCase(word.charAt(0)));
			if (word.length() > 1) {
				builder.append(word.substring(1).toLowerCase(Locale.ROOT));
			}
		}
		return builder.isEmpty() ? raw : builder.toString();
	}

	private static String fallbackPortTypeLabel(String typeId) {
		if (typeId == null || typeId.isBlank()) {
			return "unknown";
		}
		int separator = Math.max(typeId.lastIndexOf(':'), typeId.lastIndexOf('/'));
		return separator >= 0 && separator < typeId.length() - 1 ? typeId.substring(separator + 1) : typeId;
	}
}
