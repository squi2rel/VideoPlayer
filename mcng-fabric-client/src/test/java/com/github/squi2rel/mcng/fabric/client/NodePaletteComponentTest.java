package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodePaletteComponentTest {
	@Test
	void mouseWheelScrollsPaletteContent() throws ReflectiveOperationException {
		NodePaletteComponent palette = createPalette();

		assertTrue(palette.mouseScrolled(40, 120, 0.0, -1.0));
		assertEquals(18.0, scrollOffset(palette));
	}

	@Test
	void fallsBackToHorizontalAxisWhenVerticalDeltaIsZero() throws ReflectiveOperationException {
		NodePaletteComponent palette = createPalette();

		assertTrue(palette.mouseScrolled(40, 120, -1.0, 0.0));
		assertEquals(18.0, scrollOffset(palette));
	}

	private static NodePaletteComponent createPalette() {
		List<NodePaletteEntry> entries = IntStream.range(0, 20)
			.mapToObj(index -> new NodePaletteEntry("test:" + index, "Node " + index, "test:" + index, "Test", index, List.of()))
			.toList();
		NodePaletteComponent palette = new NodePaletteComponent(
			() -> List.of(new NodePaletteSection("Test", 0, entries)),
			NodeTypeRegistry::new,
			MCNGPortTypes.createRegistry(),
			GraphEditorUiConfig::defaultConfig,
			GraphEditorI18n::identity,
			() -> "",
			value -> {
			}
		);
		palette.setBounds(new GraphEditorBounds(0, 0, 320, 160));
		palette.toggle();
		return palette;
	}

	private static double scrollOffset(NodePaletteComponent palette) throws ReflectiveOperationException {
		Field stateField = NodePaletteComponent.class.getDeclaredField("state");
		stateField.setAccessible(true);
		Object state = stateField.get(palette);
		Field scrollOffsetField = state.getClass().getDeclaredField("scrollOffset");
		scrollOffsetField.setAccessible(true);
		return scrollOffsetField.getDouble(state);
	}
}
