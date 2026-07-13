package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphBuilder;
import com.github.squi2rel.mcng.core.GraphDefinition;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeRegistrar;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorComponentTest {
	@Test
	void resizingEditorDoesNotResetPaletteScrollOffset() throws ReflectiveOperationException {
		GraphEditorComponent editor = createEditor();
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));

		NodePaletteComponent palette = palette(editor);
		palette.toggle();
		palette.mouseScrolled(40, 120, 0.0, -1.0);
		assertEquals(18.0, scrollOffset(palette));

		editor.setBounds(new GraphEditorBounds(0, 0, 960, 540));
		assertEquals(18.0, scrollOffset(palette));
	}

	@Test
	void rightClickCancelsPendingConnectionWithoutOpeningMenu() throws ReflectiveOperationException {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("4.0"), new NodePosition(20, 20));
		builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));

		GraphEditorComponent editor = createEditor(builder.buildDocument());
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));
		editor.session().toggleConnectionCandidate(source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT);

		assertTrue(editor.mouseClicked(200, 200, 1));
		assertNull(editor.session().pendingConnection());
		assertFalse(contextMenu(editor).isOpen());

		editor.mouseReleased(200, 200, 1);
		assertFalse(contextMenu(editor).isOpen());
	}

	@Test
	void rightClickingOutputPortClearsOutgoingEdges() throws ReflectiveOperationException {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("4.0"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		NodeId debug = builder.addNode(BuiltinNodeTypes.DEBUG_OUTPUT, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 140));
		builder.addEdge(source, "value", add, "left");
		builder.addEdge(source, "value", debug, "value");

		GraphEditorComponent editor = createEditor(builder.buildDocument());
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));
		double[] point = portScreenPosition(editor, source, BuiltinNodeTypes.VALUE_PORT, PortDirection.OUTPUT);

		assertTrue(editor.mouseClicked(point[0], point[1], 1));
		assertTrue(editor.session().edges().isEmpty());
	}

	@Test
	void rightClickingInputPortClearsIncomingEdges() throws ReflectiveOperationException {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId source = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("4.0"), new NodePosition(20, 20));
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(220, 20));
		builder.addEdge(source, "value", add, "left");

		GraphEditorComponent editor = createEditor(builder.buildDocument());
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));
		double[] point = portScreenPosition(editor, add, BuiltinNodeTypes.LEFT_PORT, PortDirection.INPUT);

		assertTrue(editor.mouseClicked(point[0], point[1], 1));
		assertTrue(editor.session().edges().isEmpty());
	}

	@Test
	void clickingASelectedNodeCollapsesMultiSelectionToThatNode() {
		GraphBuilder builder = new GraphBuilder(registry(), portTypes());
		NodeId left = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("1.0"), new NodePosition(20, 20));
		NodeId right = builder.addNode(BuiltinNodeTypes.NUMERIC_CONSTANT, new BuiltinNodeTypes.NumericConstantConfig("2.0"), new NodePosition(220, 20));

		GraphEditorSession session = new GraphEditorSession(
			registry(),
			portTypes(),
			new GraphJsonCodec(),
			builder.buildDocument(),
			new TestHost()
		);
		GraphInteractionController controller = new GraphInteractionController();
		GraphCanvasComponent canvas = new GraphCanvasComponent(session, controller, GraphEditorUiConfig::defaultConfig);
		canvas.init(null, new GraphEditorBounds(0, 0, 800, 480));
		session.selectNodes(List.of(left, right), false);
		NodeWidget widget = new NodeWidget(session.node(left), session.nodeType(session.node(left)), session, new GraphLayout(session.positions()), canvas.viewport());
		double localX = canvas.viewport().toScreenX(widget.x() + (widget.width() / 2.0));
		double localY = canvas.viewport().toScreenY(widget.y() + (widget.height() / 2.0));

		assertTrue(controller.mouseClicked(canvas, localX, localY, 0));
		assertTrue(controller.mouseReleased(canvas, localX, localY, 0));
		assertEquals(java.util.Set.of(left), session.selectedNodeIds());
	}

	@Test
	void ctrlZUndoesLatestDocumentEdit() {
		GraphEditorComponent editor = createEditor();
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));
		editor.session().addNode(BuiltinNodeTypes.NUMERIC_CONSTANT.id(), 40, 40);

		assertEquals(1, editor.session().nodes().size());
		assertTrue(editor.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL));
		assertTrue(editor.session().nodes().isEmpty());
	}

	@Test
	void ctrlShiftZRedoesLatestDocumentEdit() {
		GraphEditorComponent editor = createEditor();
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));
		editor.session().addNode(BuiltinNodeTypes.NUMERIC_CONSTANT.id(), 40, 40);
		assertTrue(editor.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL));

		assertTrue(editor.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT));
		assertEquals(1, editor.session().nodes().size());
	}

	@Test
	void focusedPaletteSearchConsumesUndoShortcutBeforeSessionHistory() throws ReflectiveOperationException {
		GraphEditorComponent editor = createEditor();
		editor.init(null, new GraphEditorBounds(0, 0, 800, 480));
		editor.session().addNode(BuiltinNodeTypes.NUMERIC_CONSTANT.id(), 40, 40);

		NodePaletteComponent palette = palette(editor);
		palette.toggle();
		searchField(palette).setFocused(true);

		assertTrue(editor.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL));
		assertEquals(1, editor.session().nodes().size());
		assertTrue(editor.session().canUndo());
	}

	private static GraphEditorComponent createEditor() {
		return createEditor(GraphDocument.of(new GraphDefinition(List.of(), List.of()), new GraphLayout(Map.of())));
	}

	private static GraphEditorComponent createEditor(GraphDocument document) {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = portTypes();
		GraphEditorSession session = new GraphEditorSession(
			registry,
			portTypes,
			new GraphJsonCodec(),
			document,
			new TestHost()
		);
		NodePaletteRegistry paletteRegistry = new NodePaletteRegistry();
		BuiltinNodePaletteRegistrar.registerAll(paletteRegistry);
		return new GraphEditorComponent(session, paletteRegistry, GraphEditorUiConfig.defaultConfig());
	}

	private static NodeTypeRegistry registry() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeRegistrar.registerCoreNodes(registry);
		BuiltinNodeRegistrar.registerDebugNodes(registry);
		return registry;
	}

	private static PortTypeRegistry portTypes() {
		return MCNGPortTypes.createRegistry();
	}

	private static NodePaletteComponent palette(GraphEditorComponent editor) throws ReflectiveOperationException {
		Field field = GraphEditorComponent.class.getDeclaredField("palette");
		field.setAccessible(true);
		return (NodePaletteComponent) field.get(editor);
	}

	private static GraphCanvasComponent canvas(GraphEditorComponent editor) throws ReflectiveOperationException {
		Field field = GraphEditorComponent.class.getDeclaredField("canvas");
		field.setAccessible(true);
		return (GraphCanvasComponent) field.get(editor);
	}

	private static GraphContextMenuComponent contextMenu(GraphEditorComponent editor) throws ReflectiveOperationException {
		Field field = GraphEditorComponent.class.getDeclaredField("contextMenu");
		field.setAccessible(true);
		return (GraphContextMenuComponent) field.get(editor);
	}

	private static double scrollOffset(NodePaletteComponent palette) throws ReflectiveOperationException {
		Field stateField = NodePaletteComponent.class.getDeclaredField("state");
		stateField.setAccessible(true);
		Object state = stateField.get(palette);
		Field scrollOffsetField = state.getClass().getDeclaredField("scrollOffset");
		scrollOffsetField.setAccessible(true);
		return scrollOffsetField.getDouble(state);
	}

	private static GraphTextFieldComponent searchField(NodePaletteComponent palette) throws ReflectiveOperationException {
		Field field = NodePaletteComponent.class.getDeclaredField("searchField");
		field.setAccessible(true);
		return (GraphTextFieldComponent) field.get(palette);
	}

	private static double[] portScreenPosition(GraphEditorComponent editor, NodeId nodeId, PortId portId, PortDirection direction) throws ReflectiveOperationException {
		GraphCanvasComponent canvas = canvas(editor);
		var session = editor.session();
		var node = session.node(nodeId);
		NodeWidget widget = new NodeWidget(node, session.nodeType(node), session, new GraphLayout(session.positions()), canvas.viewport());
		var port = GraphCanvasComponent.findPortWidget(widget, portId, direction);
		return new double[] {
			canvas.bounds().x() + canvas.viewport().toScreenX(port.centerX()),
			canvas.bounds().y() + canvas.viewport().toScreenY(port.centerY())
		};
	}

	private static double[] nodeScreenPosition(GraphEditorComponent editor, NodeId nodeId) throws ReflectiveOperationException {
		GraphCanvasComponent canvas = canvas(editor);
		var session = editor.session();
		var node = session.node(nodeId);
		NodeWidget widget = new NodeWidget(node, session.nodeType(node), session, new GraphLayout(session.positions()), canvas.viewport());
		return new double[] {
			canvas.bounds().x() + canvas.viewport().toScreenX(widget.x() + (widget.width() / 2.0)),
			canvas.bounds().y() + canvas.viewport().toScreenY(widget.y() + (widget.height() / 2.0))
		};
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
