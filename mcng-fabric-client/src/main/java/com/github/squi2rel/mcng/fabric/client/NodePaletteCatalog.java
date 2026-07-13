package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeDefinition;
import com.github.squi2rel.mcng.core.DocumentNodeDefinitionKind;
import com.github.squi2rel.mcng.core.DocumentNodeTypes;
import com.github.squi2rel.mcng.core.NodeType;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class NodePaletteCatalog {
	private static final int GRAPH_SECTION_ORDER = 10_000;
	private static final int CUSTOM_SECTION_ORDER = 10_100;

	private NodePaletteCatalog() {
	}

	static List<NodePaletteSection> buildSections(GraphEditorSession session, NodePaletteRegistry paletteRegistry) {
		NodeTypeRegistry registry = session.resolvedRegistry();
		GraphEditorI18n i18n = session.i18n();
		Map<String, SectionBuilder> sections = new LinkedHashMap<>();

		for (NodePaletteDefinition definition : paletteRegistry.all()) {
			NodeType<?> nodeType = registry.find(definition.nodeTypeId()).orElse(null);
			if (nodeType == null) {
				continue;
			}
			String sectionTitle = GraphEditorTranslations.paletteSection(i18n, definition);
			sections.computeIfAbsent(
				sectionTitle,
				title -> new SectionBuilder(title, definition.sectionOrder())
			).entries().add(toEntry(i18n, nodeType, sectionTitle, definition.sectionTitle(), definition.searchKeywords(), definition.itemOrder()));
		}

		if (session.isInsideDefinition()) {
			String graphTitle = GraphEditorTranslations.ui(i18n, "palette.section.graph", "Graph");
			SectionBuilder graphSection = sections.computeIfAbsent(graphTitle, title -> new SectionBuilder(title, GRAPH_SECTION_ORDER));
			if (session.currentDefinitionKind().orElse(null) == DocumentNodeDefinitionKind.SUBGRAPH) {
				graphSection.entries().add(toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.SUBGRAPH_INPUT_TYPE_ID), graphTitle, "Graph", List.of("subgraph", "input"), -20));
				graphSection.entries().add(toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.SUBGRAPH_OUTPUT_TYPE_ID), graphTitle, "Graph", List.of("subgraph", "output"), -10));
				graphSection.entries().add(toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.SUBGRAPH_FLOW_INPUT_TYPE_ID), graphTitle, "Graph", List.of("flow", "control"), 0));
				graphSection.entries().add(toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.SUBGRAPH_FLOW_OUTPUT_TYPE_ID), graphTitle, "Graph", List.of("flow", "control"), 10));
			} else {
				graphSection.entries().add(toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.GRAPH_INPUT_TYPE_ID), graphTitle, "Graph", List.of(), -20));
				graphSection.entries().add(toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.GRAPH_OUTPUT_TYPE_ID), graphTitle, "Graph", List.of(), -10));
			}
		}

		List<NodePaletteEntry> customEntries = session.definitions().stream()
			.filter(definition -> definition.kind() == DocumentNodeDefinitionKind.CUSTOM_NODE)
			.filter(definition -> !definition.id().equals(session.currentDefinitionId()))
			.sorted(Comparator.comparing(DocumentNodeDefinition::displayName))
			.map(definition -> {
				String customTitle = GraphEditorTranslations.ui(i18n, "palette.section.custom", "Custom");
				return toEntry(i18n, registry.getOrThrow(DocumentNodeTypes.definitionTypeId(definition.id())), customTitle, "Custom", List.of("custom"), 0);
			})
			.toList();
		if (!customEntries.isEmpty()) {
			String customTitle = GraphEditorTranslations.ui(i18n, "palette.section.custom", "Custom");
			sections.computeIfAbsent(customTitle, title -> new SectionBuilder(title, CUSTOM_SECTION_ORDER)).entries().addAll(customEntries);
		}

		return sections.values().stream()
			.sorted(Comparator.comparingInt(SectionBuilder::order).thenComparing(SectionBuilder::title))
			.map(section -> new NodePaletteSection(
				section.title(),
				section.order(),
				section.entries().stream()
					.sorted(Comparator.comparingInt(NodePaletteEntry::itemOrder).thenComparing(NodePaletteEntry::displayName))
					.toList()
			))
			.toList();
	}

	static List<NodePaletteSection> filterSections(List<NodePaletteSection> sections, String query) {
		if (query == null || query.isBlank()) {
			return sections;
		}

		String normalized = query.toLowerCase(Locale.ROOT);
		List<NodePaletteSection> filtered = new ArrayList<>();
		for (NodePaletteSection section : sections) {
			boolean groupMatches = section.title().toLowerCase(Locale.ROOT).contains(normalized);
			List<NodePaletteEntry> entries = section.entries().stream()
				.filter(entry -> groupMatches || matches(entry, normalized))
				.toList();
			if (!entries.isEmpty()) {
				filtered.add(new NodePaletteSection(section.title(), section.order(), entries));
			}
		}
		return filtered;
	}

	private static boolean matches(NodePaletteEntry entry, String normalizedQuery) {
		return entry.displayName().toLowerCase(Locale.ROOT).contains(normalizedQuery)
			|| entry.nodeTypeId().toLowerCase(Locale.ROOT).contains(normalizedQuery)
			|| entry.subtitle().toLowerCase(Locale.ROOT).contains(normalizedQuery)
			|| entry.groupName().toLowerCase(Locale.ROOT).contains(normalizedQuery)
			|| entry.searchKeywords().stream().anyMatch(keyword -> keyword.toLowerCase(Locale.ROOT).contains(normalizedQuery));
	}

	private static NodePaletteEntry toEntry(GraphEditorI18n i18n, NodeType<?> nodeType, String groupTitle, String fallbackGroupTitle, List<String> searchKeywords, int itemOrder) {
		String fallbackDisplayName = DocumentNodeTypes.readDefinitionDisplayName(nodeType);
		List<String> resolvedSearchKeywords = new ArrayList<>(searchKeywords.size() + 3);
		resolvedSearchKeywords.addAll(searchKeywords);
		resolvedSearchKeywords.add(fallbackDisplayName);
		resolvedSearchKeywords.add(nodeType.id());
		resolvedSearchKeywords.add(fallbackGroupTitle);
		return new NodePaletteEntry(
			nodeType.id(),
			GraphEditorTranslations.nodeTitle(i18n, nodeType),
			nodeType.id(),
			groupTitle,
			itemOrder,
			resolvedSearchKeywords
		);
	}

	private record SectionBuilder(String title, int order, List<NodePaletteEntry> entries) {
		private SectionBuilder(String title, int order) {
			this(title, order, new ArrayList<>());
		}
	}
}
