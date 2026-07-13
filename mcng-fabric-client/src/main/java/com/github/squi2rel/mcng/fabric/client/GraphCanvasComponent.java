package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.EdgeDefinition;
import com.github.squi2rel.mcng.core.GraphLayout;
import com.github.squi2rel.mcng.core.NodeConfigValues;
import com.github.squi2rel.mcng.core.NodeEditorControl;
import com.github.squi2rel.mcng.core.NodeId;
import com.github.squi2rel.mcng.core.NodeInstance;
import com.github.squi2rel.mcng.core.NumericTypes;
import com.github.squi2rel.mcng.core.PortChannel;
import com.github.squi2rel.mcng.core.PortDirection;
import com.github.squi2rel.mcng.core.PortId;
import com.github.squi2rel.mcng.fabric.client.GraphEditorSession.PendingConnection;
import com.github.squi2rel.mcng.fabric.client.GraphInteractionController.SelectionBox;
import com.github.squi2rel.mcng.fabric.client.NodeWidget.InlineHit;
import com.github.squi2rel.mcng.fabric.client.NodeWidget.PortWidget;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class GraphCanvasComponent {
	private final GraphEditorSession session;
	private final GraphViewportState viewport = new GraphViewportState();
	private final GraphInteractionController controller;
	private final Supplier<GraphEditorUiConfig> uiConfigSupplier;
	private final NodeComponentRegistry componentRegistry;
	private final Map<NodeId, NodeBodyComponent> bodyComponents = new LinkedHashMap<>();
	private TextRenderer textRenderer;
	private ActiveTextEdit activeTextEdit;
	private NodeId focusedBodyNodeId;
	private CapturedBodyInteraction capturedBodyInteraction;
	private NodeId lastPrimaryClickNodeId;
	private long lastPrimaryClickAt;
	private GraphEditorBounds bounds = new GraphEditorBounds(0, 0, 0, 0);

	public GraphCanvasComponent(GraphEditorSession session, GraphInteractionController controller, Supplier<GraphEditorUiConfig> uiConfigSupplier) {
		this(session, controller, uiConfigSupplier, new NodeComponentRegistry());
	}

	public GraphCanvasComponent(GraphEditorSession session, GraphInteractionController controller, Supplier<GraphEditorUiConfig> uiConfigSupplier, NodeComponentRegistry componentRegistry) {
		this.session = session;
		this.controller = controller;
		this.uiConfigSupplier = uiConfigSupplier;
		this.componentRegistry = componentRegistry;
		viewport.reset();
	}

	public void init(TextRenderer textRenderer, GraphEditorBounds bounds) {
		this.textRenderer = textRenderer;
		setBounds(bounds);
	}

	public void close() {
		if (capturedBodyInteraction != null) {
			session.endCompositeEdit();
			capturedBodyInteraction = null;
		}
		blurActiveBodyComponent();
		for (NodeBodyComponent component : bodyComponents.values()) {
			component.close();
		}
		bodyComponents.clear();
	}

	public void setBounds(GraphEditorBounds bounds) {
		this.bounds = bounds;
	}

	public GraphEditorSession session() {
		return session;
	}

	public GraphViewportState viewport() {
		return viewport;
	}

	public GraphEditorBounds bounds() {
		return bounds;
	}

	public boolean isTextEditing() {
		return activeTextEdit != null;
	}

	public boolean contains(double mouseX, double mouseY) {
		return bounds.contains(mouseX, mouseY);
	}

	public void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
		this.textRenderer = textRenderer;
		GraphEditorUiConfig uiConfig = uiConfigSupplier.get();
		GraphEditorTheme theme = uiConfig.theme();
		renderGrid(context, theme);

		context.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
		try {
			List<NodeWidget> widgets = widgets();
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(bounds.x(), bounds.y());
			context.getMatrices().translate((float) viewport.offsetX(), (float) viewport.offsetY());
			context.getMatrices().scale((float) viewport.zoom(), (float) viewport.zoom());
			try {
				for (EdgeDefinition edge : session.edges()) {
					NodeWidget fromNode = widgets.stream().filter(widget -> widget.node().id().equals(edge.fromNodeId())).findFirst().orElse(null);
					NodeWidget toNode = widgets.stream().filter(widget -> widget.node().id().equals(edge.toNodeId())).findFirst().orElse(null);
					if (fromNode == null || toNode == null) {
						continue;
					}

					PortWidget fromPort = findPortWidget(fromNode, edge.fromPortId(), PortDirection.OUTPUT);
					PortWidget toPort = findPortWidget(toNode, edge.toPortId(), PortDirection.INPUT);
					if (fromPort != null && toPort != null) {
						EdgeRenderer.drawEdge(
							context,
							fromPort.centerX(),
							fromPort.centerY(),
							toPort.centerX(),
							toPort.centerY(),
							portColor(fromPort, theme),
							portColor(toPort, theme),
							uiConfig.edgeStyle(),
							fromPort.side(),
							toPort.side()
						);
					}
				}

				PendingConnection pending = session.pendingConnection();
				if (pending != null) {
					PortWidget pendingPort = widgets.stream()
						.flatMap(widget -> widget.ports().stream())
						.filter(port -> port.nodeId().equals(pending.nodeId())
							&& port.definition().id().equals(pending.portId())
							&& port.definition().direction() == pending.direction())
						.findFirst()
						.orElse(null);
					if (pendingPort != null) {
						int color = EditorStyleRenderer.brighten(portColor(pendingPort, theme), 0.22f);
						PendingEdgeGeometry geometry = pendingEdgeGeometry(
							pendingPort,
							(int) Math.round(toWorldX(mouseX)),
							(int) Math.round(toWorldY(mouseY))
						);
						EdgeRenderer.drawEdge(
							context,
							geometry.startX(),
							geometry.startY(),
							geometry.endX(),
							geometry.endY(),
							color,
							color,
							uiConfig.edgeStyle(),
							geometry.startSide(),
							geometry.endSide()
						);
					}
				}

				for (NodeWidget widget : widgets) {
					NodeWidgetRenderer.render(
						context,
						textRenderer,
						widget,
						uiConfig,
						theme,
						session.isSelected(widget.node().id()),
						session.isExecuting(widget.node().id()),
						session.hasError(widget.node().id()),
						port -> portColor(port, theme),
						port -> session.pendingConnection() != null
							&& session.pendingConnection().nodeId().equals(port.nodeId())
							&& session.pendingConnection().portId().equals(port.definition().id()),
						(nodeId, portId) -> activeTextEdit != null && activeTextEdit.matchesPort(nodeId, portId),
						(nodeId, key) -> activeTextEdit != null && activeTextEdit.matchesControl(nodeId, key)
					);
				}
				SelectionBox selectionBox = controller.selectionBox();
				if (selectionBox != null) {
					context.fill((int) selectionBox.minX(), (int) selectionBox.minY(), (int) selectionBox.maxX(), (int) selectionBox.maxY(), 0x223B82F6);
					EditorStyleRenderer.drawBorder(context, (int) selectionBox.minX(), (int) selectionBox.minY(), (int) Math.max(1, selectionBox.maxX() - selectionBox.minX()), (int) Math.max(1, selectionBox.maxY() - selectionBox.minY()), 0xFF6EA8FF);
				}
			} finally {
				context.getMatrices().popMatrix();
			}
		} finally {
			context.disableScissor();
		}

		renderActiveTextEditor(context, textRenderer, theme, uiConfig);
	}

	public void prepareForClick(double mouseX, double mouseY, int button) {
		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		if (button == 0 && activeTextEdit != null && !activeTextEdit.bounds().contains(worldX, worldY)) {
			commitActiveTextEdit();
		}
		if (button == 0 && focusedBodyNodeId != null) {
			NodeWidget focusedWidget = widgetById(focusedBodyNodeId);
			if (focusedWidget == null || !focusedWidget.hasInteractiveBody() || !focusedWidget.bodyBounds().contains(worldX, worldY)) {
				blurActiveBodyComponent();
			}
		}
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		if (button == 0 && activeTextEdit != null && activeTextEdit.bounds().contains(worldX, toWorldY(mouseY))) {
			activeTextEdit.handlePointerDown(textRenderer, worldX, System.currentTimeMillis());
			return true;
		}

		if (button == 0) {
			NodeWidget clickedNode = nodeAt(mouseX, mouseY);
			long now = System.currentTimeMillis();
			if (!shiftDown()
				&& clickedNode != null
				&& session.canEnterDefinition(clickedNode.node().id())
				&& clickedNode.node().id().equals(lastPrimaryClickNodeId)
				&& now - lastPrimaryClickAt <= 250L) {
				if (session.enterDefinition(clickedNode.node().id())) {
					viewport.reset();
					lastPrimaryClickNodeId = null;
					lastPrimaryClickAt = 0L;
					return true;
				}
			}
			lastPrimaryClickNodeId = clickedNode != null ? clickedNode.node().id() : null;
			lastPrimaryClickAt = now;

			InlineHit inlineHit = inlineHitAt(mouseX, mouseY);
			if (inlineHit != null) {
				handleInlineHit(inlineHit, worldX);
				return true;
			}
			if (handleBodyClick(worldX, worldY, button)) {
				return true;
			}
		} else if (handleBodyClick(worldX, worldY, button)) {
			return true;
		}

		return controller.mouseClicked(this, localX(mouseX), localY(mouseY), button);
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (activeTextEdit != null && button == 0) {
			activeTextEdit.handlePointerDrag(textRenderer, toWorldX(mouseX));
			return true;
		}
		if (capturedBodyInteraction != null && capturedBodyInteraction.button() == button) {
			NodeWidget widget = widgetById(capturedBodyInteraction.nodeId());
			if (widget != null && widget.hasInteractiveBody()) {
				double worldX = toWorldX(mouseX);
				double worldY = toWorldY(mouseY);
				NodeInteractionResult result = widget.bodyComponent().mouseDragged(
					bodyInputContext(widget),
					worldX - widget.bodyBounds().x(),
					worldY - widget.bodyBounds().y(),
					button,
					deltaX / viewport.zoom(),
					deltaY / viewport.zoom()
				);
				applyBodyInteractionResult(widget, result, button);
				return true;
			}
			session.endCompositeEdit();
			capturedBodyInteraction = null;
		}
		return controller.mouseDragged(this, localX(mouseX), localY(mouseY), button, deltaX, deltaY);
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (activeTextEdit != null && button == 0) {
			activeTextEdit.finishPointerDrag();
			return true;
		}
		if (capturedBodyInteraction != null && capturedBodyInteraction.button() == button) {
			NodeWidget widget = widgetById(capturedBodyInteraction.nodeId());
			capturedBodyInteraction = null;
			if (widget != null && widget.hasInteractiveBody()) {
				double worldX = toWorldX(mouseX);
				double worldY = toWorldY(mouseY);
				NodeInteractionResult result = widget.bodyComponent().mouseReleased(
					bodyInputContext(widget),
					worldX - widget.bodyBounds().x(),
					worldY - widget.bodyBounds().y(),
					button
				);
				applyBodyInteractionResult(widget, result, button);
			}
			session.endCompositeEdit();
			return true;
		}
		return controller.mouseReleased(this, localX(mouseX), localY(mouseY), button);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (activeTextEdit != null) {
			return true;
		}
		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		NodeWidget bodyWidget = bodyWidgetAtWorld(worldX, worldY);
		if (bodyWidget != null) {
			session.beginCompositeEdit();
			NodeInteractionResult result = bodyWidget.bodyComponent().mouseScrolled(
				bodyInputContext(bodyWidget),
				worldX - bodyWidget.bodyBounds().x(),
				worldY - bodyWidget.bodyBounds().y(),
				horizontalAmount,
				verticalAmount
			);
			boolean handled = applyBodyInteractionResult(bodyWidget, result, -1);
			session.endCompositeEdit();
			if (handled) {
				return true;
			}
		}
		return controller.mouseScrolled(this, localX(mouseX), localY(mouseY), horizontalAmount, verticalAmount);
	}

	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (activeTextEdit != null) {
			boolean controlDown = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
			boolean shiftDown = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
			if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
				cancelActiveTextEdit();
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				commitActiveTextEdit();
				return true;
			}
			if (controlDown) {
				switch (keyCode) {
					case GLFW.GLFW_KEY_Z -> {
						boolean changed = shiftDown ? activeTextEdit.state().redo() : activeTextEdit.state().undo();
						if (changed) {
							activeTextEdit.ensureCursorVisible(textRenderer);
						}
						return true;
					}
					case GLFW.GLFW_KEY_A -> {
						activeTextEdit.state().selectAll();
						activeTextEdit.ensureCursorVisible(textRenderer);
						return true;
					}
					case GLFW.GLFW_KEY_C -> {
						session.copyToClipboard(activeTextEdit.state().selectedText());
						return true;
					}
					case GLFW.GLFW_KEY_X -> {
						session.copyToClipboard(activeTextEdit.state().selectedText());
						activeTextEdit.state().insert("");
						activeTextEdit.ensureCursorVisible(textRenderer);
						return true;
					}
					case GLFW.GLFW_KEY_V -> {
						activeTextEdit.state().insert(session.readClipboard());
						activeTextEdit.ensureCursorVisible(textRenderer);
						return true;
					}
					default -> {
					}
				}
			}

			switch (keyCode) {
				case GLFW.GLFW_KEY_LEFT -> activeTextEdit.state().moveLeft(controlDown, shiftDown);
				case GLFW.GLFW_KEY_RIGHT -> activeTextEdit.state().moveRight(controlDown, shiftDown);
				case GLFW.GLFW_KEY_HOME -> activeTextEdit.state().moveHome(shiftDown);
				case GLFW.GLFW_KEY_END -> activeTextEdit.state().moveEnd(shiftDown);
				case GLFW.GLFW_KEY_BACKSPACE -> activeTextEdit.state().backspace(controlDown);
				case GLFW.GLFW_KEY_DELETE -> activeTextEdit.state().delete(controlDown);
				default -> {
					return false;
				}
			}
			activeTextEdit.ensureCursorVisible(textRenderer);
			return true;
		}
		NodeWidget focusedWidget = focusedBodyNodeId == null ? null : widgetById(focusedBodyNodeId);
		if (focusedWidget != null && focusedWidget.hasInteractiveBody()) {
			return focusedWidget.bodyComponent().keyPressed(bodyInputContext(focusedWidget), keyCode, scanCode, modifiers);
		}
		return false;
	}

	public boolean charTyped(char chr, int modifiers) {
		if (activeTextEdit != null) {
			if ((modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) != 0 || Character.isISOControl(chr)) {
				return false;
			}
			activeTextEdit.state().insert(String.valueOf(chr));
			activeTextEdit.ensureCursorVisible(textRenderer);
			return true;
		}
		NodeWidget focusedWidget = focusedBodyNodeId == null ? null : widgetById(focusedBodyNodeId);
		if (focusedWidget != null && focusedWidget.hasInteractiveBody()) {
			return focusedWidget.bodyComponent().charTyped(bodyInputContext(focusedWidget), chr, modifiers);
		}
		return false;
	}

	public void commitInlineEditor() {
		commitActiveTextEdit();
	}

	public NodeWidget nodeAt(double mouseX, double mouseY) {
		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		return nodeAtWorld(worldX, worldY);
	}

	public NodeWidget nodeAtLocal(double localX, double localY) {
		double worldX = viewport.toWorldX(localX);
		double worldY = viewport.toWorldY(localY);
		return nodeAtWorld(worldX, worldY);
	}

	private NodeWidget nodeAtWorld(double worldX, double worldY) {
		List<NodeWidget> widgets = widgets();
		for (int index = widgets.size() - 1; index >= 0; index--) {
			NodeWidget widget = widgets.get(index);
			if (widget.contains(worldX, worldY)) {
				return widget;
			}
		}
		return null;
	}

	public PortWidget portAt(double mouseX, double mouseY) {
		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		return portAtWorld(worldX, worldY);
	}

	public PortWidget portAtLocal(double localX, double localY) {
		double worldX = viewport.toWorldX(localX);
		double worldY = viewport.toWorldY(localY);
		return portAtWorld(worldX, worldY);
	}

	private PortWidget portAtWorld(double worldX, double worldY) {
		NodeWidget widget = nodeAtWorld(worldX, worldY);
		return widget == null ? null : widget.findPortAt(worldX, worldY);
	}

	public ResizeTarget resizeTargetAtLocal(double localX, double localY) {
		double worldX = viewport.toWorldX(localX);
		double worldY = viewport.toWorldY(localY);
		return resizeTargetAtWorld(worldX, worldY);
	}

	public ResizeDirection currentResizeDirection(double mouseX, double mouseY) {
		ResizeDirection activeDirection = controller.activeResizeDirection();
		if (activeDirection != null) {
			return activeDirection;
		}
		if (!contains(mouseX, mouseY)) {
			return null;
		}
		ResizeTarget target = resizeTargetAtLocal(localX(mouseX), localY(mouseY));
		return target == null ? null : target.direction();
	}

	public GraphCursorManager.CursorKind cursorKindAt(double mouseX, double mouseY) {
		ResizeDirection activeDirection = controller.activeResizeDirection();
		if (activeDirection != null) {
			return GraphCursorManager.forResizeDirection(activeDirection);
		}
		if (!contains(mouseX, mouseY)) {
			return GraphCursorManager.CursorKind.DEFAULT;
		}

		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		if (activeTextEdit != null && activeTextEdit.bounds().contains(worldX, worldY)) {
			return GraphCursorManager.CursorKind.TEXT;
		}

		List<NodeWidget> widgets = widgets();
		for (int index = widgets.size() - 1; index >= 0; index--) {
			NodeWidget widget = widgets.get(index);
			if (!widget.contains(worldX, worldY)) {
				continue;
			}

			NodeWidget.ResizeHandle resizeHandle = widget.findResizeHandleAt(worldX, worldY);
			if (resizeHandle != null) {
				return GraphCursorManager.forResizeDirection(resizeHandle.direction());
			}

			if (widget.findPortAt(worldX, worldY) != null) {
				return GraphCursorManager.CursorKind.DEFAULT;
			}

			InlineHit inlineHit = widget.findInlineHitAt(worldX, worldY);
			if (inlineHit != null) {
				return switch (inlineHit) {
					case InlineHit.PortTextFieldHit ignored -> GraphCursorManager.CursorKind.TEXT;
					case InlineHit.ControlTextFieldHit ignored -> GraphCursorManager.CursorKind.TEXT;
					case InlineHit.PortBooleanHit ignored -> GraphCursorManager.CursorKind.DEFAULT;
					case InlineHit.ControlBooleanHit ignored -> GraphCursorManager.CursorKind.DEFAULT;
					case InlineHit.ControlCycleHit ignored -> GraphCursorManager.CursorKind.DEFAULT;
				};
			}

			if (widget.hasInteractiveBody() && widget.bodyBounds().contains(worldX, worldY)) {
				return GraphCursorManager.CursorKind.DEFAULT;
			}

			return GraphCursorManager.CursorKind.GRAB;
		}
		return GraphCursorManager.CursorKind.DEFAULT;
	}

	private ResizeTarget resizeTargetAtWorld(double worldX, double worldY) {
		List<NodeWidget> widgets = widgets();
		for (int index = widgets.size() - 1; index >= 0; index--) {
			NodeWidget widget = widgets.get(index);
			if (!widget.contains(worldX, worldY)) {
				continue;
			}
			NodeWidget.ResizeHandle handle = widget.findResizeHandleAt(worldX, worldY);
			if (handle != null) {
				return new ResizeTarget(widget, handle.direction());
			}
		}
		return null;
	}

	public List<NodeId> nodesInRect(double minX, double minY, double maxX, double maxY) {
		List<NodeId> nodeIds = new ArrayList<>();
		for (NodeWidget widget : widgets()) {
			if (widget.x() <= maxX && widget.x() + widget.width() >= minX && widget.y() <= maxY && widget.y() + widget.height() >= minY) {
				nodeIds.add(widget.node().id());
			}
		}
		return nodeIds;
	}

	private void renderGrid(DrawContext context, GraphEditorTheme theme) {
		context.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), theme.canvasBackgroundColor());
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(bounds.x(), bounds.y());
		try {
			double step = Math.max(8, 24 * viewport.zoom());
			double startX = viewport.offsetX() % step;
			double startY = viewport.offsetY() % step;
			for (double x = startX; x < bounds.width(); x += step) {
				context.fill((int) x, 0, (int) x + 1, bounds.height(), theme.gridColor());
			}
			for (double y = startY; y < bounds.height(); y += step) {
				context.fill(0, (int) y, bounds.width(), (int) y + 1, theme.gridColor());
			}
		} finally {
			context.getMatrices().popMatrix();
		}
	}

	private void renderActiveTextEditor(DrawContext context, TextRenderer textRenderer, GraphEditorTheme theme, GraphEditorUiConfig uiConfig) {
		if (activeTextEdit == null) {
			return;
		}
		context.enableScissor(bounds.x(), bounds.y(), bounds.right(), bounds.bottom());
		try {
			context.getMatrices().pushMatrix();
			try {
				context.getMatrices().translate(bounds.x(), bounds.y());
				context.getMatrices().translate((float) viewport.offsetX(), (float) viewport.offsetY());
				context.getMatrices().scale((float) viewport.zoom(), (float) viewport.zoom());
				GraphTextInputRenderer.renderFrame(context, activeTextEdit.bounds(), theme, uiConfig);
			} finally {
				context.getMatrices().popMatrix();
			}

			NodeWidget.Bounds screenBounds = screenBounds(activeTextEdit.bounds());
			int scissorLeft = Math.max(bounds.x(), screenBounds.x() + GraphTextInputRenderer.CONTENT_PADDING_X);
			int scissorTop = Math.max(bounds.y(), screenBounds.y() + GraphTextInputRenderer.CONTENT_PADDING_Y);
			int scissorRight = Math.min(bounds.right(), screenBounds.x() + screenBounds.width() - GraphTextInputRenderer.CONTENT_PADDING_X);
			int scissorBottom = Math.min(bounds.bottom(), screenBounds.y() + screenBounds.height() - GraphTextInputRenderer.CONTENT_PADDING_Y);
			context.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);
			try {
				context.getMatrices().pushMatrix();
				try {
					context.getMatrices().translate(bounds.x(), bounds.y());
					context.getMatrices().translate((float) viewport.offsetX(), (float) viewport.offsetY());
					context.getMatrices().scale((float) viewport.zoom(), (float) viewport.zoom());
					GraphTextInputRenderer.renderContent(context, textRenderer, activeTextEdit.bounds(), activeTextEdit.state(), theme, true);
				} finally {
					context.getMatrices().popMatrix();
				}
			} finally {
				context.disableScissor();
			}
		} finally {
			context.disableScissor();
		}
	}

	private void handleInlineHit(InlineHit inlineHit, double worldX) {
		switch (inlineHit) {
			case InlineHit.PortTextFieldHit portTextFieldHit -> {
				session.selectNode(portTextFieldHit.nodeId());
				NodeInstance node = session.node(portTextFieldHit.nodeId());
				var port = session.nodeType(node).inputs(node).stream().filter(candidate -> candidate.id().equals(portTextFieldHit.portId())).findFirst().orElseThrow();
				String value = portTextFieldHit.numeric()
					? NodeConfigValues.readInlineInputText(node.config(), port)
					: String.valueOf(NodeConfigValues.readInlineInputValue(node.config(), port));
				beginTextEdit(ActiveTextEdit.forPort(portTextFieldHit, value));
				activeTextEdit.handlePointerDown(textRenderer, worldX, System.currentTimeMillis());
			}
			case InlineHit.PortBooleanHit portBooleanHit -> {
				session.selectNode(portBooleanHit.nodeId());
				NodeInstance node = session.node(portBooleanHit.nodeId());
				var port = session.nodeType(node).inputs(node).stream().filter(candidate -> candidate.id().equals(portBooleanHit.portId())).findFirst().orElseThrow();
				boolean current = Boolean.TRUE.equals(NodeConfigValues.readInlineInputValue(node.config(), port));
				session.updateInlineInput(node.id(), port.id(), new JsonPrimitive(!current));
			}
			case InlineHit.ControlTextFieldHit controlTextFieldHit -> {
				session.selectNode(controlTextFieldHit.nodeId());
				NodeInstance node = session.node(controlTextFieldHit.nodeId());
				String value = session.nodeType(node).editorControls().stream()
					.filter(candidate -> candidate.key().equals(controlTextFieldHit.key()))
					.findFirst()
					.map(control -> switch (control) {
						case NodeEditorControl.TextControl textControl -> NodeConfigValues.readTextControlValue(node.config(), textControl);
						case NodeEditorControl.NumericTextControl numericTextControl -> NodeConfigValues.readNumericTextControlValue(node.config(), numericTextControl);
						case NodeEditorControl.BooleanControl ignored -> throw new IllegalStateException("Boolean control is not text-editable");
						case NodeEditorControl.CycleControl ignored -> throw new IllegalStateException("Cycle control is not text-editable");
					})
					.orElseThrow();
				beginTextEdit(ActiveTextEdit.forControl(controlTextFieldHit, value));
				activeTextEdit.handlePointerDown(textRenderer, worldX, System.currentTimeMillis());
			}
			case InlineHit.ControlBooleanHit controlBooleanHit -> {
				session.selectNode(controlBooleanHit.nodeId());
				NodeInstance node = session.node(controlBooleanHit.nodeId());
				var control = session.nodeType(node).editorControls().stream()
					.filter(candidate -> candidate instanceof NodeEditorControl.BooleanControl booleanControl && booleanControl.key().equals(controlBooleanHit.key()))
					.map(NodeEditorControl.BooleanControl.class::cast)
					.findFirst()
					.orElseThrow();
				boolean current = NodeConfigValues.readBooleanControlValue(node.config(), control);
				session.updateControlValue(node.id(), control.key(), new JsonPrimitive(!current));
			}
			case InlineHit.ControlCycleHit controlCycleHit -> {
				session.selectNode(controlCycleHit.nodeId());
				NodeInstance node = session.node(controlCycleHit.nodeId());
				var control = session.nodeType(node).editorControls().stream()
					.filter(candidate -> candidate instanceof NodeEditorControl.CycleControl cycleControl && cycleControl.key().equals(controlCycleHit.key()))
					.map(NodeEditorControl.CycleControl.class::cast)
					.findFirst()
					.orElseThrow();
				String current = NodeConfigValues.readCycleControlValue(node.config(), control);
				List<NodeEditorControl.Option> options = control.options();
				int currentIndex = 0;
				for (int index = 0; index < options.size(); index++) {
					if (options.get(index).id().equals(current)) {
						currentIndex = index;
						break;
					}
				}
				int nextIndex = Math.floorMod(currentIndex + controlCycleHit.direction(), options.size());
				session.updateControlValue(node.id(), control.key(), new JsonPrimitive(options.get(nextIndex).id()));
			}
		}
	}

	private void beginTextEdit(ActiveTextEdit edit) {
		if (textRenderer == null) {
			return;
		}
		blurActiveBodyComponent();
		activeTextEdit = edit;
		activeTextEdit.ensureCursorVisible(textRenderer);
	}

	private void commitActiveTextEdit() {
		if (activeTextEdit == null) {
			return;
		}
		String text = activeTextEdit.state().text();
		if (activeTextEdit.numeric()) {
			try {
				String normalized = NumericTypes.parseLiteral(text).text();
				if (activeTextEdit.portId() != null) {
					session.updateInlineInput(activeTextEdit.nodeId(), activeTextEdit.portId(), new JsonPrimitive(normalized));
				} else {
					session.updateControlValue(activeTextEdit.nodeId(), activeTextEdit.key(), new JsonPrimitive(normalized));
				}
				clearActiveTextEdit();
			} catch (IllegalArgumentException exception) {
				session.showMessage(session.translate("mcng.ui.canvas.invalid_number", "Invalid number: %s", text));
			}
			return;
		}

		if (activeTextEdit.portId() != null) {
			session.updateInlineInput(activeTextEdit.nodeId(), activeTextEdit.portId(), new JsonPrimitive(text));
		} else {
			session.updateControlValue(activeTextEdit.nodeId(), activeTextEdit.key(), new JsonPrimitive(text));
		}
		clearActiveTextEdit();
	}

	private void cancelActiveTextEdit() {
		clearActiveTextEdit();
	}

	private void clearActiveTextEdit() {
		activeTextEdit = null;
	}

	private boolean handleBodyClick(double worldX, double worldY, int button) {
		NodeWidget widget = bodyWidgetAtWorld(worldX, worldY);
		if (widget == null) {
			return false;
		}
		if (!session.isSelected(widget.node().id()) && !GraphInputModifiers.shiftDown()) {
			session.selectNode(widget.node().id());
		}
		session.beginCompositeEdit();
		NodeInteractionResult result = widget.bodyComponent().mouseClicked(
			bodyInputContext(widget),
			worldX - widget.bodyBounds().x(),
			worldY - widget.bodyBounds().y(),
			button
		);
		boolean keepCompositeOpen = result != null && result.handled() && result.capturePointer();
		boolean handled = applyBodyInteractionResult(widget, result, button);
		if (!keepCompositeOpen) {
			session.endCompositeEdit();
		}
		return handled;
	}

	private boolean applyBodyInteractionResult(NodeWidget widget, NodeInteractionResult result, int button) {
		if (result == null || !result.handled()) {
			return false;
		}
		if (result.requestFocus()) {
			setFocusedBodyNode(widget.node().id());
		}
		if (result.capturePointer()) {
			capturedBodyInteraction = new CapturedBodyInteraction(widget.node().id(), button);
		} else if (capturedBodyInteraction != null && capturedBodyInteraction.nodeId().equals(widget.node().id()) && capturedBodyInteraction.button() == button) {
			capturedBodyInteraction = null;
			session.endCompositeEdit();
		}
		return true;
	}

	private NodeWidget bodyWidgetAtWorld(double worldX, double worldY) {
		List<NodeWidget> widgets = widgets();
		for (int index = widgets.size() - 1; index >= 0; index--) {
			NodeWidget widget = widgets.get(index);
			if (widget.hasInteractiveBody() && widget.bodyBounds().contains(worldX, worldY)) {
				return widget;
			}
		}
		return null;
	}

	private NodeWidget widgetById(NodeId nodeId) {
		for (NodeWidget widget : widgets()) {
			if (widget.node().id().equals(nodeId)) {
				return widget;
			}
		}
		return null;
	}

	private NodeBodyInputContext bodyInputContext(NodeWidget widget) {
		GraphEditorUiConfig uiConfig = uiConfigSupplier.get();
		return new NodeBodyInputContext(widget.bodyBounds(), widget.node(), widget.nodeType(), session, session.i18n(), uiConfig, uiConfig.theme(), widget.zoom());
	}

	private void setFocusedBodyNode(NodeId nodeId) {
		if (nodeId.equals(focusedBodyNodeId)) {
			return;
		}
		blurActiveBodyComponent();
		focusedBodyNodeId = nodeId;
	}

	private void blurActiveBodyComponent() {
		if (focusedBodyNodeId == null) {
			return;
		}
		NodeBodyComponent component = bodyComponents.get(focusedBodyNodeId);
		focusedBodyNodeId = null;
		if (capturedBodyInteraction != null) {
			session.endCompositeEdit();
		}
		capturedBodyInteraction = null;
		if (component != null) {
			component.blur();
		}
	}

	private InlineHit inlineHitAt(double mouseX, double mouseY) {
		double worldX = toWorldX(mouseX);
		double worldY = toWorldY(mouseY);
		List<NodeWidget> widgets = widgets();
		for (int index = widgets.size() - 1; index >= 0; index--) {
			NodeWidget widget = widgets.get(index);
			if (!widget.contains(worldX, worldY)) {
				continue;
			}
			InlineHit hit = widget.findInlineHitAt(worldX, worldY);
			if (hit != null) {
				return hit;
			}
		}
		return null;
	}

	private List<NodeWidget> widgets() {
		GraphLayout layout = new GraphLayout(session.positions(), session.sizes());
		cleanupBodyComponents();
		GraphEditorUiConfig uiConfig = uiConfigSupplier.get();
		List<NodeWidget> widgets = new ArrayList<>();
		for (NodeInstance node : session.nodes()) {
			NodeComponentDefinition definition = componentRegistry.find(node.typeId()).orElse(null);
			NodeBodyComponent component = definition == null ? null : bodyComponents.computeIfAbsent(node.id(), ignored -> definition.factory().create());
			ResizePolicy resizePolicy = definition == null ? ResizePolicy.none() : definition.resizePolicy();
			widgets.add(new NodeWidget(node, session.nodeType(node), session, layout, viewport, uiConfig, component, resizePolicy));
		}
		return widgets;
	}

	private void cleanupBodyComponents() {
		List<NodeId> visibleNodeIds = session.nodes().stream().map(NodeInstance::id).toList();
		List<NodeId> removed = bodyComponents.keySet().stream()
			.filter(nodeId -> !visibleNodeIds.contains(nodeId))
			.toList();
		for (NodeId nodeId : removed) {
			NodeBodyComponent component = bodyComponents.remove(nodeId);
			if (component != null) {
				component.close();
			}
		}
		if (focusedBodyNodeId != null && !visibleNodeIds.contains(focusedBodyNodeId)) {
			focusedBodyNodeId = null;
		}
		if (capturedBodyInteraction != null && !visibleNodeIds.contains(capturedBodyInteraction.nodeId())) {
			session.endCompositeEdit();
			capturedBodyInteraction = null;
		}
	}

	private int portColor(PortWidget port, GraphEditorTheme theme) {
		if (session.effectivePortChannel(port.nodeId(), port.definition().id(), port.definition().direction()) == PortChannel.CONTROL) {
			return theme.controlFlowColor();
		}
		return session.portTypes().colorOf(session.effectivePortType(port.nodeId(), port.definition().id(), port.definition().direction()));
	}

	static PendingEdgeGeometry pendingEdgeGeometry(PortWidget pendingPort, int mouseX, int mouseY) {
		if (pendingPort.definition().direction() == PortDirection.OUTPUT) {
			return new PendingEdgeGeometry(
				pendingPort.centerX(),
				pendingPort.centerY(),
				mouseX,
				mouseY,
				pendingPort.side(),
				targetSideForFreeEndpoint(pendingPort.centerX(), pendingPort.centerY(), mouseX, mouseY)
			);
		}
		return new PendingEdgeGeometry(
			mouseX,
			mouseY,
			pendingPort.centerX(),
			pendingPort.centerY(),
			sourceSideForFreeEndpoint(mouseX, mouseY, pendingPort.centerX(), pendingPort.centerY()),
			pendingPort.side()
		);
	}

	private static NodeWidget.PortSide sourceSideForFreeEndpoint(int sourceX, int sourceY, int targetX, int targetY) {
		return sideToward(sourceX, sourceY, targetX, targetY);
	}

	private static NodeWidget.PortSide targetSideForFreeEndpoint(int sourceX, int sourceY, int targetX, int targetY) {
		return sideToward(targetX, targetY, sourceX, sourceY);
	}

	private static NodeWidget.PortSide sideToward(int fromX, int fromY, int toX, int toY) {
		int dx = toX - fromX;
		int dy = toY - fromY;
		if (Math.abs(dx) >= Math.abs(dy)) {
			return dx >= 0 ? NodeWidget.PortSide.RIGHT : NodeWidget.PortSide.LEFT;
		}
		return dy >= 0 ? NodeWidget.PortSide.BOTTOM : NodeWidget.PortSide.TOP;
	}

	private static boolean shiftDown() {
		return GraphInputModifiers.shiftDown();
	}

	private NodeWidget.Bounds screenBounds(NodeWidget.Bounds bounds) {
		int width = Math.max(8, (int) Math.round(bounds.width() * viewport.zoom()));
		int height = Math.max(10, (int) Math.round(bounds.height() * viewport.zoom()));
		return new NodeWidget.Bounds(this.bounds.x() + (int) Math.round(viewport.toScreenX(bounds.x())), this.bounds.y() + (int) Math.round(viewport.toScreenY(bounds.y())), width, height);
	}

	private double localX(double screenX) {
		return screenX - bounds.x();
	}

	private double localY(double screenY) {
		return screenY - bounds.y();
	}

	private double toWorldX(double screenX) {
		return viewport.toWorldX(localX(screenX));
	}

	private double toWorldY(double screenY) {
		return viewport.toWorldY(localY(screenY));
	}

	static PortWidget findPortWidget(NodeWidget widget, PortId portId, PortDirection direction) {
		return widget.ports().stream()
			.filter(port -> port.definition().id().equals(portId) && port.definition().direction() == direction)
			.findFirst()
			.orElse(null);
	}

	record PendingEdgeGeometry(
		int startX,
		int startY,
		int endX,
		int endY,
		NodeWidget.PortSide startSide,
		NodeWidget.PortSide endSide
	) {
	}

	record ResizeTarget(NodeWidget widget, ResizeDirection direction) {
	}

	private record CapturedBodyInteraction(NodeId nodeId, int button) {
	}

	private static final class ActiveTextEdit {
		private static final long DOUBLE_CLICK_WINDOW_MS = 250L;

		private final NodeId nodeId;
		private final PortId portId;
		private final String key;
		private final NodeWidget.Bounds bounds;
		private final boolean numeric;
		private final GraphTextInputState state;
		private boolean draggingPointer;
		private long lastPointerDownAt;
		private int lastPointerIndex;

		private ActiveTextEdit(NodeId nodeId, PortId portId, String key, NodeWidget.Bounds bounds, boolean numeric, String originalValue) {
			this.nodeId = nodeId;
			this.portId = portId;
			this.key = key;
			this.bounds = bounds;
			this.numeric = numeric;
			this.state = new GraphTextInputState(originalValue);
		}

		private static ActiveTextEdit forPort(InlineHit.PortTextFieldHit hit, String originalValue) {
			return new ActiveTextEdit(hit.nodeId(), hit.portId(), null, hit.bounds(), hit.numeric(), originalValue);
		}

		private static ActiveTextEdit forControl(InlineHit.ControlTextFieldHit hit, String originalValue) {
			return new ActiveTextEdit(hit.nodeId(), null, hit.key(), hit.bounds(), hit.numeric(), originalValue);
		}

		private NodeWidget.Bounds bounds() {
			return bounds;
		}

		private boolean numeric() {
			return numeric;
		}

		private NodeId nodeId() {
			return nodeId;
		}

		private PortId portId() {
			return portId;
		}

		private String key() {
			return key;
		}

		private GraphTextInputState state() {
			return state;
		}

		private void handlePointerDown(TextRenderer textRenderer, double worldX, long timeMs) {
			int index = indexForWorldX(textRenderer, worldX);
			if ((timeMs - lastPointerDownAt) <= DOUBLE_CLICK_WINDOW_MS && Math.abs(index - lastPointerIndex) <= 1) {
				state.selectWordAt(index);
				draggingPointer = false;
			} else {
				state.setCursor(index, false);
				draggingPointer = true;
			}
			lastPointerDownAt = timeMs;
			lastPointerIndex = index;
			ensureCursorVisible(textRenderer);
		}

		private void handlePointerDrag(TextRenderer textRenderer, double worldX) {
			if (!draggingPointer) {
				return;
			}
			state.setCursor(indexForWorldX(textRenderer, worldX), true);
			ensureCursorVisible(textRenderer);
		}

		private void finishPointerDrag() {
			draggingPointer = false;
		}

		private void ensureCursorVisible(TextRenderer textRenderer) {
			state.ensureCursorVisible(textRenderer, Math.max(1, bounds.width() - 8));
		}

		private boolean matchesPort(NodeId nodeId, PortId portId) {
			return this.nodeId.equals(nodeId) && this.portId != null && this.portId.equals(portId);
		}

		private boolean matchesControl(NodeId nodeId, String key) {
			return this.nodeId.equals(nodeId) && this.key != null && this.key.equals(key);
		}

		private int indexForWorldX(TextRenderer textRenderer, double worldX) {
			double localX = worldX - (bounds.x() + 4) + state.scrollX();
			return state.indexForX(textRenderer, localX);
		}
	}
}
