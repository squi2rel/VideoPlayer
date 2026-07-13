package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeRegistrar;
import net.minecraft.client.gui.screen.Screen;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCNGDebugScreenLayoutTest {
	@Test
	void debugPanelButtonsStayInsidePanelAndDoNotOverlapVariables() throws ReflectiveOperationException {
		MCNGDebugScreen screen = createScreen();
		setScreenSize(screen, 1280, 720);
		GraphEditorSession session = session(screen);
		for (int index = 0; index < 5; index++) {
			session.createVariable();
		}

		Object layout = invoke(screen, "debugPanelLayout");
		Rect panel = rect(layout);
		List<?> buttons = invokeList(screen, "debugButtons", layout);
		List<?> rows = invokeList(screen, "variableRows", layout);

		for (Object button : buttons) {
			assertTrue(contains(panel, rect(button)), "button escaped debug panel: " + button);
		}
		for (Object row : rows) {
			assertTrue(contains(panel, rect(row)), "variable row escaped debug panel: " + row);
		}
		for (Object button : buttons) {
			for (Object row : rows) {
				assertFalse(intersects(rect(button), rect(row)), "debug button overlaps variable row");
			}
		}
	}

	private static MCNGDebugScreen createScreen() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeRegistrar.registerCoreNodes(registry);
		BuiltinNodeRegistrar.registerDebugNodes(registry);
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		NodePaletteRegistry paletteRegistry = new NodePaletteRegistry();
		BuiltinNodePaletteRegistrar.registerAll(paletteRegistry);
		GraphDocument document = GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of()));
		Consumer<GraphDocument> noopPersist = value -> {
		};
		Consumer<String> noopStatus = value -> {
		};
		return new MCNGDebugScreen(
			registry,
			portTypes,
			paletteRegistry,
			new GraphJsonCodec(),
			GraphEditorUiConfig.defaultConfig(),
			document,
			noopPersist,
			noopStatus
		);
	}

	private static GraphEditorSession session(MCNGDebugScreen screen) throws ReflectiveOperationException {
		Field field = MCNGDebugScreen.class.getDeclaredField("session");
		field.setAccessible(true);
		return (GraphEditorSession) field.get(screen);
	}

	private static void setScreenSize(MCNGDebugScreen screen, int width, int height) throws ReflectiveOperationException {
		Field widthField = Screen.class.getDeclaredField("width");
		Field heightField = Screen.class.getDeclaredField("height");
		widthField.setAccessible(true);
		heightField.setAccessible(true);
		widthField.setInt(screen, width);
		heightField.setInt(screen, height);
	}

	private static Object invoke(MCNGDebugScreen screen, String methodName) throws ReflectiveOperationException {
		Method method = MCNGDebugScreen.class.getDeclaredMethod(methodName);
		method.setAccessible(true);
		return method.invoke(screen);
	}

	@SuppressWarnings("unchecked")
	private static List<?> invokeList(MCNGDebugScreen screen, String methodName, Object layout) throws ReflectiveOperationException {
		Method method = MCNGDebugScreen.class.getDeclaredMethod(methodName, layout.getClass());
		method.setAccessible(true);
		return (List<?>) method.invoke(screen, layout);
	}

	private static Rect rect(Object value) throws ReflectiveOperationException {
		Method x = value.getClass().getDeclaredMethod("x");
		Method y = value.getClass().getDeclaredMethod("y");
		Method width = value.getClass().getDeclaredMethod("width");
		Method height = value.getClass().getDeclaredMethod("height");
		x.setAccessible(true);
		y.setAccessible(true);
		width.setAccessible(true);
		height.setAccessible(true);
		return new Rect((int) x.invoke(value), (int) y.invoke(value), (int) width.invoke(value), (int) height.invoke(value));
	}

	private static boolean contains(Rect outer, Rect inner) {
		return inner.x >= outer.x
			&& inner.y >= outer.y
			&& inner.x + inner.width <= outer.x + outer.width
			&& inner.y + inner.height <= outer.y + outer.height;
	}

	private static boolean intersects(Rect left, Rect right) {
		return left.x < right.x + right.width
			&& left.x + left.width > right.x
			&& left.y < right.y + right.height
			&& left.y + left.height > right.y;
	}

	private record Rect(int x, int y, int width, int height) {
	}
}
