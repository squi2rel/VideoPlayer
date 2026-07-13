package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.DocumentNodeDefinitionKind;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodePaletteCatalogTest {
	@Test
	void groupsRegisteredPaletteEntriesIntoExpectedSections() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		NodePaletteRegistry paletteRegistry = paletteRegistry();
		GraphEditorSession session = new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), emptyDocument(), new TestHost());

		List<NodePaletteSection> sections = NodePaletteCatalog.buildSections(session, paletteRegistry);

		assertEquals(List.of("Constants", "Operations", "Control", "Variables", "Events", "Debug"), sections.stream().map(NodePaletteSection::title).toList());
		assertEquals(List.of("Numeric Constant", "Boolean Constant", "String Constant", "Port Type"), sections.getFirst().entries().stream().map(NodePaletteEntry::displayName).toList());
		assertEquals(
			List.of(
				"Add",
				"Subtract",
				"Multiply",
				"Cast",
				"Round",
				"Concat",
				"Less Than",
				"Equals",
				"Select",
				"And",
				"Or",
				"Identity",
				"Make List",
				"List Append",
				"List Get",
				"Map Put",
				"Map Get",
				"Map Keys",
				"Type Of",
				"Convert Type",
				"Reroute",
				"Not"
			),
			sections.get(1).entries().stream().map(NodePaletteEntry::displayName).toList()
		);
	}

	@Test
	void filtersByDisplayNameTypeIdGroupAndSearchKeywords() {
		GraphEditorSession session = new GraphEditorSession(registry(), MCNGPortTypes.createRegistry(), new GraphJsonCodec(), emptyDocument(), new TestHost());
		List<NodePaletteSection> sections = NodePaletteCatalog.buildSections(session, paletteRegistry());

		assertEquals(List.of("Debug"), NodePaletteCatalog.filterSections(sections, "debug").stream().map(NodePaletteSection::title).toList());
		assertEquals(List.of("Image Preview"), NodePaletteCatalog.filterSections(sections, "demo").getFirst().entries().stream().map(NodePaletteEntry::displayName).toList());
		assertEquals(List.of("Numeric Constant"), NodePaletteCatalog.filterSections(sections, "numeric_constant").getFirst().entries().stream().map(NodePaletteEntry::displayName).toList());
		assertTrue(NodePaletteCatalog.filterSections(sections, "math").getFirst().entries().stream().anyMatch(entry -> entry.displayName().equals("Add")));
	}

	@Test
	void customDefinitionsAppearInPaletteAndResolvedRegistry() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), emptyDocument(), new TestHost());

		var definition = session.createBlankDefinition(DocumentNodeDefinitionKind.CUSTOM_NODE, 40, 40);
		session.exitToBreadcrumb(null);
		List<NodePaletteSection> sections = NodePaletteCatalog.buildSections(session, paletteRegistry());
		boolean hasCustomEntry = sections.stream()
			.filter(section -> section.title().equals("Custom"))
			.flatMap(section -> section.entries().stream())
			.anyMatch(entry -> entry.nodeTypeId().equals(com.github.squi2rel.mcng.core.DocumentNodeTypes.definitionTypeId(definition.id())));

		assertTrue(session.resolvedRegistry().find(com.github.squi2rel.mcng.core.DocumentNodeTypes.definitionTypeId(definition.id())).isPresent());
		assertTrue(hasCustomEntry);
	}

	@Test
	void editorsCanExposeDifferentPaletteEntriesForSameRuntimeRegistry() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), emptyDocument(), new TestHost());

		NodePaletteRegistry fullPalette = paletteRegistry();
		NodePaletteRegistry minimalPalette = new NodePaletteRegistry()
			.register(new NodePaletteDefinition(com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes.ADD.id(), "Math", 100, 10));

		assertTrue(NodePaletteCatalog.buildSections(session, fullPalette).stream().flatMap(section -> section.entries().stream()).anyMatch(entry -> entry.displayName().equals("Debug Output")));
		assertTrue(NodePaletteCatalog.buildSections(session, fullPalette).stream().flatMap(section -> section.entries().stream()).anyMatch(entry -> entry.displayName().equals("Image Preview")));
		assertEquals(
			List.of("Add"),
			NodePaletteCatalog.buildSections(session, minimalPalette).getFirst().entries().stream().map(NodePaletteEntry::displayName).toList()
		);
	}

	@Test
	void paletteUsesHostTranslationsAndStillSupportsFallbackSearchTerms() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphEditorSession session = new GraphEditorSession(
			registry,
			portTypes,
			new GraphJsonCodec(),
			emptyDocument(),
			new TranslatingHost(Map.of(
				"mcng.ui.palette.section.operations", "运算",
				"mcng.node.mcng.add.title", "加法"
			))
		);

		List<NodePaletteSection> sections = NodePaletteCatalog.buildSections(session, paletteRegistry());

		assertTrue(sections.stream().anyMatch(section -> section.title().equals("运算")));
		assertTrue(sections.stream().filter(section -> section.title().equals("运算")).flatMap(section -> section.entries().stream()).anyMatch(entry -> entry.displayName().equals("加法")));
		assertTrue(NodePaletteCatalog.filterSections(sections, "add").stream().flatMap(section -> section.entries().stream()).anyMatch(entry -> entry.displayName().equals("加法")));
	}

	private static NodeTypeRegistry registry() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeRegistrar.registerCoreNodes(registry);
		BuiltinNodeRegistrar.registerDebugNodes(registry);
		return registry;
	}

	private static NodePaletteRegistry paletteRegistry() {
		NodePaletteRegistry registry = new NodePaletteRegistry();
		BuiltinNodePaletteRegistrar.registerAll(registry);
		return registry;
	}

	private static GraphDocument emptyDocument() {
		return GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()));
	}

	private static class TestHost implements GraphEditorHost {
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

	private static final class TranslatingHost extends TestHost {
		private final Map<String, String> translations;

		private TranslatingHost(Map<String, String> translations) {
			this.translations = translations;
		}

		@Override
		public GraphEditorI18n i18n() {
			return (key, fallback, args) -> translations.getOrDefault(key, GraphEditorI18n.formatFallback(fallback, key, args));
		}
	}
}
