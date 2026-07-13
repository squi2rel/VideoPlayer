package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.GraphBuilder;
import com.github.squi2rel.mcng.core.GraphDocument;
import com.github.squi2rel.mcng.core.GraphJsonCodec;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.MCNGPortTypes;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeSize;
import com.github.squi2rel.mcng.core.NodeTypeRegistry;
import com.github.squi2rel.mcng.core.PortTypeRegistry;
import com.github.squi2rel.mcng.core.builtin.BuiltinNodeTypes;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeBodyComponentIntegrationTest {
	@Test
	void nodeWidgetAllocatesBodyFromStoredLayoutSize() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));
		builder.setNodeSize(add, new NodeSize(260, 220));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		GraphLayout layout = new GraphLayout(session.positions(), session.sizes());
		GraphViewportState viewport = new GraphViewportState();
		NodeWidget widget = new NodeWidget(
			session.node(add),
			session.nodeType(session.node(add)),
			session,
			layout,
			viewport,
			GraphEditorUiConfig.defaultConfig(),
			new FixedBodyComponent(),
			ResizePolicy.allSides()
		);

		assertEquals(260, widget.width());
		assertEquals(220, widget.height());
		assertNotNull(widget.bodyBounds());
		assertTrue(widget.bodyBounds().height() >= 90);
		assertTrue(widget.minWidth() >= 196);
		assertTrue(widget.minHeight() > 0);
	}

	@Test
	void canvasRoutesFocusAndKeyboardToBodyComponent() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		TrackingBodyComponent component = new TrackingBodyComponent();
		NodeComponentRegistry components = new NodeComponentRegistry()
			.register(new NodeComponentDefinition(BuiltinNodeTypes.ADD.id(), () -> component, ResizePolicy.allSides()));
		GraphCanvasComponent canvas = new GraphCanvasComponent(session, new GraphInteractionController(), GraphEditorUiConfig::defaultConfig, components);
		canvas.init(null, new GraphEditorBounds(0, 0, 800, 480));

		NodeWidget widget = new NodeWidget(
			session.node(add),
			session.nodeType(session.node(add)),
			session,
			new GraphLayout(session.positions(), session.sizes()),
			canvas.viewport(),
			GraphEditorUiConfig.defaultConfig(),
			component,
			ResizePolicy.allSides()
		);
		double clickX = canvas.viewport().toScreenX(widget.bodyBounds().x() + 12);
		double clickY = canvas.viewport().toScreenY(widget.bodyBounds().y() + 12);

		canvas.prepareForClick(clickX, clickY, 0);
		assertTrue(canvas.mouseClicked(clickX, clickY, 0));
		assertTrue(canvas.keyPressed(GLFW.GLFW_KEY_A, 0, 0));
		canvas.prepareForClick(clickX + 400, clickY + 200, 0);

		assertEquals(1, component.clickCount);
		assertEquals(1, component.keyPressCount);
		assertEquals(1, component.blurCount);
	}

	@Test
	void resizeHandleUpdatesLayoutSize() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		TrackingBodyComponent component = new TrackingBodyComponent();
		NodeComponentRegistry components = new NodeComponentRegistry()
			.register(new NodeComponentDefinition(BuiltinNodeTypes.ADD.id(), () -> component, ResizePolicy.allSides()));
		GraphInteractionController controller = new GraphInteractionController();
		GraphCanvasComponent canvas = new GraphCanvasComponent(session, controller, GraphEditorUiConfig::defaultConfig, components);
		canvas.init(null, new GraphEditorBounds(0, 0, 800, 480));

		NodeWidget widget = new NodeWidget(
			session.node(add),
			session.nodeType(session.node(add)),
			session,
			new GraphLayout(session.positions(), session.sizes()),
			canvas.viewport(),
			GraphEditorUiConfig.defaultConfig(),
			component,
			ResizePolicy.allSides()
		);
		double handleX = canvas.viewport().toScreenX(widget.x() + widget.width() - 1);
		double handleY = canvas.viewport().toScreenY(widget.y() + widget.height() - 1);

		assertNotNull(canvas.resizeTargetAtLocal(handleX, handleY));
		assertTrue(controller.mouseClicked(canvas, handleX, handleY, 0));
		assertTrue(controller.mouseDragged(canvas, handleX + 36, handleY + 24, 0, 36, 24));
		assertTrue(controller.mouseReleased(canvas, handleX + 36, handleY + 24, 0));

		NodeSize resized = session.sizes().get(add);
		assertNotNull(resized);
		assertEquals(widget.width() + 36, resized.width());
		assertEquals(widget.height() + 24, resized.height());
	}

	@Test
	void rerouteCornerDragKeepsFixedPortAndCanSwitchOrientationMultipleTimes() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId reroute = builder.addNode(BuiltinNodeTypes.REROUTE, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		GraphEditorSession session = session(registry, portTypes, builder.buildDocument());
		GraphInteractionController controller = new GraphInteractionController();
		GraphCanvasComponent canvas = new GraphCanvasComponent(session, controller, GraphEditorUiConfig::defaultConfig, new NodeComponentRegistry());
		canvas.init(null, new GraphEditorBounds(0, 0, 800, 480));

		NodeWidget widget = new NodeWidget(
			session.node(reroute),
			session.nodeType(session.node(reroute)),
			session,
			new GraphLayout(session.positions(), session.sizes()),
			canvas.viewport()
		);
		assertNotNull(widget);
		NodeWidget.PortWidget fixedPort = GraphCanvasComponent.findPortWidget(widget, BuiltinNodeTypes.VALUE_PORT, com.github.squi2rel.mcng.core.PortDirection.INPUT);
		assertNotNull(fixedPort);
		int fixedPortX = fixedPort.centerX();
		int fixedPortY = fixedPort.centerY();
		double handleX = canvas.viewport().toScreenX(widget.x() + widget.width() - 2);
		double handleY = canvas.viewport().toScreenY(widget.y() + 1);

		assertNotNull(canvas.resizeTargetAtLocal(handleX, handleY));
		assertTrue(controller.mouseClicked(canvas, handleX, handleY, 0));
		assertTrue(controller.mouseDragged(canvas, canvas.viewport().toScreenX(fixedPortX + 10), canvas.viewport().toScreenY(fixedPortY + 90), 0, 0, 0));
		assertEquals(RerouteOrientation.TOP_TO_BOTTOM, session.rerouteOrientation(reroute));

		NodeWidget verticalWidget = new NodeWidget(
			session.node(reroute),
			session.nodeType(session.node(reroute)),
			session,
			new GraphLayout(session.positions(), session.sizes()),
			canvas.viewport()
		);
		NodeWidget.PortWidget fixedPortAfterVertical = GraphCanvasComponent.findPortWidget(verticalWidget, BuiltinNodeTypes.VALUE_PORT, com.github.squi2rel.mcng.core.PortDirection.INPUT);
		assertNotNull(fixedPortAfterVertical);
		assertEquals(fixedPortX, fixedPortAfterVertical.centerX());
		assertEquals(fixedPortY, fixedPortAfterVertical.centerY());

		assertTrue(controller.mouseDragged(canvas, canvas.viewport().toScreenX(fixedPortX + 120), canvas.viewport().toScreenY(fixedPortY + 10), 0, 0, 0));
		assertEquals(RerouteOrientation.LEFT_TO_RIGHT, session.rerouteOrientation(reroute));
		assertTrue(controller.mouseReleased(canvas, canvas.viewport().toScreenX(fixedPortX + 120), canvas.viewport().toScreenY(fixedPortY + 10), 0));

		NodeWidget finalWidget = new NodeWidget(
			session.node(reroute),
			session.nodeType(session.node(reroute)),
			session,
			new GraphLayout(session.positions(), session.sizes()),
			canvas.viewport()
		);
		NodeWidget.PortWidget fixedPortAfterReturn = GraphCanvasComponent.findPortWidget(finalWidget, BuiltinNodeTypes.VALUE_PORT, com.github.squi2rel.mcng.core.PortDirection.INPUT);
		assertNotNull(fixedPortAfterReturn);
		assertEquals(fixedPortX, fixedPortAfterReturn.centerX());
		assertEquals(fixedPortY, fixedPortAfterReturn.centerY());
	}

	@Test
	void imagePreviewBodyUsesHostFileChooserToUpdateConfig() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId preview = builder.addNode(BuiltinNodeTypes.IMAGE_PREVIEW, new BuiltinNodeTypes.ImagePreviewConfig(""), new NodePosition(20, 20));

		FileChoosingHost host = new FileChoosingHost("/tmp/demo.png");
		GraphEditorSession session = new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), builder.buildDocument(), host);
		ImagePreviewNodeBodyComponent component = new ImagePreviewNodeBodyComponent();
		NodeWidget widget = new NodeWidget(
			session.node(preview),
			session.nodeType(session.node(preview)),
			session,
			new GraphLayout(session.positions(), session.sizes()),
			new GraphViewportState(),
			GraphEditorUiConfig.defaultConfig(),
			component,
			ResizePolicy.allSides()
		);
		NodeWidget.Bounds bodyBounds = widget.bodyBounds();
		int previewHeight = Math.max(30, bodyBounds.height() - 52);
		double localX = (bodyBounds.width() - 44 - 6 - 58) + 10;
		double localY = previewHeight + 4 + 18 + 4 + 9;

		NodeInteractionResult result = component.mouseClicked(
			new NodeBodyInputContext(bodyBounds, session.node(preview), session.nodeType(session.node(preview)), session, session.i18n(), GraphEditorUiConfig.defaultConfig(), GraphEditorTheme.defaultTheme(), 1.0),
			localX,
			localY,
			0
		);

		assertTrue(result.handled());
		assertEquals("/tmp/demo.png", session.node(preview).config().get("filePath").getAsString());
		assertNotNull(host.request);
		assertEquals(List.of("png", "jpg", "jpeg", "bmp", "tga"), host.request.extensions());
	}

	@Test
	void bodyInputContextUsesHostTranslator() {
		NodeTypeRegistry registry = registry();
		PortTypeRegistry portTypes = MCNGPortTypes.createRegistry();
		GraphBuilder builder = new GraphBuilder(registry, portTypes);
		NodeId add = builder.addNode(BuiltinNodeTypes.ADD, BuiltinNodeTypes.EmptyConfig.INSTANCE, new NodePosition(20, 20));

		TranslatingHost host = new TranslatingHost(Map.of("custom.test", "translated"));
		GraphEditorSession session = new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), builder.buildDocument(), host);
		NodeBodyInputContext context = new NodeBodyInputContext(
			new NodeWidget.Bounds(0, 0, 120, 60),
			session.node(add),
			session.nodeType(session.node(add)),
			session,
			session.i18n(),
			GraphEditorUiConfig.defaultConfig(),
			GraphEditorTheme.defaultTheme(),
			1.0
		);

		assertEquals("translated", context.translate("custom.test", "fallback"));
	}

	private static GraphEditorSession session(NodeTypeRegistry registry, PortTypeRegistry portTypes, GraphDocument document) {
		return new GraphEditorSession(registry, portTypes, new GraphJsonCodec(), document, new TestHost());
	}

	private static NodeTypeRegistry registry() {
		NodeTypeRegistry registry = new NodeTypeRegistry();
		BuiltinNodeTypes.registerAll(registry);
		return registry;
	}

	private static final class FixedBodyComponent implements NodeBodyComponent {
		@Override
		public NodeBodyMeasurement measure(NodeBodyMeasureContext context) {
			return new NodeBodyMeasurement(true, 120, 90, 140, 90);
		}
	}

	private static final class TrackingBodyComponent implements NodeBodyComponent {
		private int clickCount;
		private int keyPressCount;
		private int blurCount;

		@Override
		public NodeBodyMeasurement measure(NodeBodyMeasureContext context) {
			return new NodeBodyMeasurement(true, 120, 60, 140, 60);
		}

		@Override
		public NodeInteractionResult mouseClicked(NodeBodyInputContext context, double localMouseX, double localMouseY, int button) {
			clickCount++;
			return NodeInteractionResult.focusHandled();
		}

		@Override
		public boolean keyPressed(NodeBodyInputContext context, int keyCode, int scanCode, int modifiers) {
			keyPressCount++;
			return true;
		}

		@Override
		public void blur() {
			blurCount++;
		}
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

	private static final class FileChoosingHost implements GraphEditorHost {
		private final String chosenPath;
		private GraphFileDialogRequest request;

		private FileChoosingHost(String chosenPath) {
			this.chosenPath = chosenPath;
		}

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

		@Override
		public boolean supportsFileDialogs() {
			return true;
		}

		@Override
		public Optional<String> chooseFile(GraphFileDialogRequest request) {
			this.request = request;
			return Optional.of(chosenPath);
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
