package com.github.squi2rel.mcng.fabric.client;

import com.github.squi2rel.mcng.core.NodePosition;
import com.github.squi2rel.mcng.core.NodeType;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

public final class GraphEditorComponent {
	private static final int TOP_BAR_HEIGHT = 28;
	private static final int TOP_BAR_PADDING = 8;
	private static final double CONTEXT_MENU_DRAG_THRESHOLD = 4.0D;

	private final GraphEditorSession session;
	private final NodePaletteRegistry paletteRegistry;
	private final NodeComponentRegistry componentRegistry;
	private final GraphCanvasComponent canvas;
	private final NodePaletteComponent palette;
	private final GraphContextMenuComponent contextMenu;

	private GraphEditorUiConfig uiConfig;
	private TextRenderer textRenderer;
	private GraphEditorBounds bounds = new GraphEditorBounds(0, 0, 0, 0);
	private boolean secondaryPointerDown;
	private boolean secondaryPointerDragged;
	private double secondaryPointerStartX;
	private double secondaryPointerStartY;

	public GraphEditorComponent(GraphEditorSession session, NodePaletteRegistry paletteRegistry, GraphEditorUiConfig uiConfig) {
		this(session, paletteRegistry, new NodeComponentRegistry(), uiConfig);
	}

	public GraphEditorComponent(GraphEditorSession session, NodePaletteRegistry paletteRegistry, NodeComponentRegistry componentRegistry, GraphEditorUiConfig uiConfig) {
		this.session = session;
		this.paletteRegistry = paletteRegistry;
		this.componentRegistry = componentRegistry;
		this.uiConfig = uiConfig;
		this.canvas = new GraphCanvasComponent(session, new GraphInteractionController(), this::uiConfig, componentRegistry);
		this.palette = new NodePaletteComponent(
			() -> NodePaletteCatalog.buildSections(session, paletteRegistry),
			session::resolvedRegistry,
			() -> componentRegistry,
			session.portTypes(),
			this::uiConfig,
			session::i18n,
			session::readClipboard,
			session::copyToClipboard
		);
		this.contextMenu = new GraphContextMenuComponent(this::uiConfig, session::i18n);
	}

	public void init(TextRenderer textRenderer, GraphEditorBounds bounds) {
		this.textRenderer = textRenderer;
		this.bounds = bounds;
		canvas.init(textRenderer, canvasBounds());
		palette.init(textRenderer, bounds);
	}

	public void close() {
		canvas.close();
		GraphCursorManager.reset();
	}

	public void setBounds(GraphEditorBounds bounds) {
		this.bounds = bounds;
		canvas.setBounds(canvasBounds());
		palette.setBounds(bounds);
	}

	public GraphEditorSession session() {
		return session;
	}

	public GraphViewportState viewport() {
		return canvas.viewport();
	}

	public GraphEditorUiConfig uiConfig() {
		return uiConfig;
	}

	public void setUiConfig(GraphEditorUiConfig uiConfig) {
		this.uiConfig = uiConfig;
	}

	public boolean isPaletteOpen() {
		return palette.isOpen();
	}

	public int paletteSidebarRight() {
		return palette.sidebarRight();
	}

	public GraphEditorBounds bounds() {
		return bounds;
	}

	public void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY, float delta) {
		this.textRenderer = textRenderer;
		canvas.render(context, textRenderer, mouseX, mouseY);
		renderTopBar(context);
		palette.render(context, textRenderer, mouseX, mouseY, delta);
		contextMenu.render(context, textRenderer);
		palette.renderDragPreview(context, textRenderer);
		updateCursor(mouseX, mouseY);
	}

	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!bounds.contains(mouseX, mouseY)) {
			if (contextMenu.isOpen()) {
				contextMenu.close();
				return true;
			}
			return false;
		}
		canvas.prepareForClick(mouseX, mouseY, button);
		if (contextMenu.isOpen()) {
			if (contextMenu.blocksCanvasAt(mouseX, mouseY)) {
				GraphContextMenuComponent.MenuAction action = contextMenu.mouseClicked(mouseX, mouseY, button);
				if (action != null) {
					performContextMenuAction(action);
				}
				return true;
			}
			contextMenu.close();
			if (button != 1) {
				return true;
			}
		}
		if (!palette.blocksCanvasAt(mouseX, mouseY)) {
			palette.blurSearch();
		}
		if (button == 0 && clickBreadcrumb(mouseX, mouseY)) {
			canvas.viewport().reset();
			return true;
		}
		if (palette.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (palette.blocksCanvasAt(mouseX, mouseY)) {
			return true;
		}
		if (!canvas.contains(mouseX, mouseY)) {
			return false;
		}
		if (button == 1) {
			if (session.cancelPendingConnection()) {
				contextMenu.close();
				return true;
			}
			canvas.commitInlineEditor();
			if (canvas.portAt(mouseX, mouseY) != null) {
				return canvas.mouseClicked(mouseX, mouseY, button);
			}
			beginSecondaryPointer(mouseX, mouseY);
			return true;
		}
		return canvas.mouseClicked(mouseX, mouseY, button);
	}

	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (secondaryPointerDown && button == 1) {
			if (!secondaryPointerDragged && exceededSecondaryDragThreshold(mouseX, mouseY)) {
				secondaryPointerDragged = true;
			}
			if (secondaryPointerDragged) {
				canvas.viewport().pan(deltaX, deltaY);
			}
			return true;
		}
		if (contextMenu.isOpen()) {
			return true;
		}
		if (palette.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) {
			return true;
		}
		if (palette.blocksCanvasAt(mouseX, mouseY)) {
			return true;
		}
		return canvas.contains(mouseX, mouseY) && canvas.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (secondaryPointerDown && button == 1) {
			boolean dragged = secondaryPointerDragged;
			clearSecondaryPointer();
			if (!dragged) {
				openContextMenuAt(mouseX, mouseY);
			}
			return true;
		}
		if (contextMenu.isOpen()) {
			return true;
		}
		NodePaletteInteractionResult result = palette.mouseReleased(mouseX, mouseY, button);
		if (result.action() instanceof NodePaletteAction.CreateAtCenter createAtCenter) {
			addNodeAtVisibleCenter(createAtCenter.nodeTypeId());
			return true;
		}
		if (result.action() instanceof NodePaletteAction.CreateAtPointer createAtPointer) {
			addNodeAtScreenPosition(createAtPointer.nodeTypeId(), createAtPointer.screenX(), createAtPointer.screenY());
			return true;
		}
		if (result.handled()) {
			return true;
		}
		return canvas.contains(mouseX, mouseY) && canvas.mouseReleased(mouseX, mouseY, button);
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!bounds.contains(mouseX, mouseY)) {
			return false;
		}
		if (contextMenu.isOpen()) {
			return true;
		}
		if (palette.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
			return true;
		}
		boolean paletteBlocks = palette.blocksCanvasAt(mouseX, mouseY);
		if (paletteBlocks) {
			return true;
		}
		return canvas.contains(mouseX, mouseY) && canvas.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (contextMenu.isOpen() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
			contextMenu.close();
			return true;
		}
		if (palette.isSearchFocused()) {
			palette.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
		if (canvas.isTextEditing()) {
			canvas.keyPressed(keyCode, scanCode, modifiers);
			return true;
		}
		if (isUndoShortcut(keyCode, modifiers)) {
			return session.undo();
		}
		if (isRedoShortcut(keyCode, modifiers)) {
			return session.redo();
		}
		if (keyCode == GLFW.GLFW_KEY_TAB) {
			canvas.commitInlineEditor();
			contextMenu.close();
			palette.toggle();
			return true;
		}
		if (canvas.keyPressed(keyCode, scanCode, modifiers)) {
			return true;
		}
		return palette.keyPressed(keyCode, scanCode, modifiers);
	}

	public boolean charTyped(char chr, int modifiers) {
		if (palette.isSearchFocused()) {
			palette.charTyped(chr, modifiers);
			return true;
		}
		if (canvas.charTyped(chr, modifiers)) {
			return true;
		}
		return palette.charTyped(chr, modifiers);
	}

	private void renderTopBar(DrawContext context) {
		GraphEditorTheme theme = uiConfig.theme();
		EditorStyleRenderer.drawBox(context, bounds.x(), bounds.y(), bounds.width(), TOP_BAR_HEIGHT, theme.panelBackgroundColor(), theme.panelBorderColor(), uiConfig);
		renderBreadcrumbs(context, theme);
	}

	private void renderBreadcrumbs(DrawContext context, GraphEditorTheme theme) {
		int x = bounds.x() + TOGGLE_AREA_WIDTH();
		int y = bounds.y() + TOP_BAR_PADDING;
		var breadcrumbs = session.breadcrumbs();
		for (int index = 0; index < breadcrumbs.size(); index++) {
			GraphEditorSession.Breadcrumb breadcrumb = breadcrumbs.get(index);
			context.drawText(textRenderer, breadcrumb.label(), x, y, theme.accentColor(), false);
			x += textRenderer.getWidth(breadcrumb.label());
			if (index < breadcrumbs.size() - 1) {
				context.drawText(textRenderer, " / ", x, y, theme.secondaryTextColor(), false);
				x += textRenderer.getWidth(" / ");
			}
		}
	}

	private boolean clickBreadcrumb(double mouseX, double mouseY) {
		int x = bounds.x() + TOGGLE_AREA_WIDTH();
		int y = bounds.y() + TOP_BAR_PADDING + 2;
		for (GraphEditorSession.Breadcrumb breadcrumb : session.breadcrumbs()) {
			int width = textRenderer.getWidth(breadcrumb.label());
			if (mouseX >= x && mouseX <= x + width && mouseY >= y - 2 && mouseY <= y + 10) {
				return session.exitToBreadcrumb(breadcrumb.definitionId());
			}
			x += width + textRenderer.getWidth(" / ");
		}
		return false;
	}

	private void openContextMenuAt(double mouseX, double mouseY) {
		NodeWidget node = canvas.nodeAt(mouseX, mouseY);
		if (node != null) {
			if (!session.isSelected(node.node().id())) {
				session.selectNode(node.node().id());
			}
			contextMenu.openNodeMenu(mouseX, mouseY, bounds, textRenderer);
		} else {
			contextMenu.openCanvasMenu(mouseX, mouseY, bounds, textRenderer, session.hasLocalClipboard());
		}
	}

	private void performContextMenuAction(GraphContextMenuComponent.MenuAction action) {
		switch (action) {
			case COPY -> session.copySelectionToLocalClipboard();
			case CUT -> {
				if (session.copySelectionToLocalClipboard()) {
					session.removeSelectedNodes();
				}
			}
			case DELETE -> session.removeSelectedNodes();
			case PASTE -> session.pasteLocalClipboard(canvas.viewport().toWorldX(contextMenu.anchorScreenX() - canvas.bounds().x()) - 80, canvas.viewport().toWorldY(contextMenu.anchorScreenY() - canvas.bounds().y()) - 40);
		}
	}

	private void addNodeAtVisibleCenter(String nodeTypeId) {
		GraphEditorBounds canvasBounds = canvasBounds();
		double visibleLeft = palette.isOpen() ? palette.sidebarRight() + 10.0 : canvasBounds.x();
		double centerX = visibleLeft + ((canvasBounds.right() - visibleLeft) / 2.0);
		double centerY = canvasBounds.y() + (canvasBounds.height() / 2.0);
		addNodeAtScreenPosition(nodeTypeId, centerX, centerY);
	}

	private void addNodeAtScreenPosition(String nodeTypeId, double screenX, double screenY) {
		NodeType<?> nodeType = session.resolvedRegistry().getOrThrow(nodeTypeId);
		NodePosition position = placementPosition(canvas.viewport(), canvas.bounds(), nodeType, uiConfig, componentRegistry, screenX, screenY);
		session.addNode(nodeTypeId, position.x(), position.y());
	}

	private static boolean isUndoShortcut(int keyCode, int modifiers) {
		return keyCode == GLFW.GLFW_KEY_Z
			&& (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
			&& (modifiers & GLFW.GLFW_MOD_SHIFT) == 0;
	}

	private static boolean isRedoShortcut(int keyCode, int modifiers) {
		return keyCode == GLFW.GLFW_KEY_Z
			&& (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
			&& (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
	}

	static NodePosition placementPosition(
		GraphViewportState viewport,
		GraphEditorBounds canvasBounds,
		NodeType<?> nodeType,
		double screenX,
		double screenY
	) {
		return placementPosition(viewport, canvasBounds, nodeType, GraphEditorUiConfig.defaultConfig(), new NodeComponentRegistry(), screenX, screenY);
	}

	static NodePosition placementPosition(
		GraphViewportState viewport,
		GraphEditorBounds canvasBounds,
		NodeType<?> nodeType,
		GraphEditorUiConfig uiConfig,
		NodeComponentRegistry componentRegistry,
		double screenX,
		double screenY
	) {
		NodeComponentDefinition definition = componentRegistry.find(nodeType.id()).orElse(null);
		NodeBodyComponent component = definition == null ? null : definition.factory().create();
		ResizePolicy resizePolicy = definition == null ? ResizePolicy.none() : definition.resizePolicy();
		NodeWidget preview = NodeWidget.preview(nodeType, uiConfig, component, resizePolicy, (int) Math.round(screenX), (int) Math.round(screenY));
		double worldX = viewport.toWorldX(preview.x() - canvasBounds.x());
		double worldY = viewport.toWorldY(preview.y() - canvasBounds.y());
		return new NodePosition(worldX, worldY);
	}

	private void beginSecondaryPointer(double mouseX, double mouseY) {
		secondaryPointerDown = true;
		secondaryPointerDragged = false;
		secondaryPointerStartX = mouseX;
		secondaryPointerStartY = mouseY;
	}

	private void clearSecondaryPointer() {
		secondaryPointerDown = false;
		secondaryPointerDragged = false;
	}

	private void updateCursor(double mouseX, double mouseY) {
		if (contextMenu.isOpen() || !bounds.contains(mouseX, mouseY)) {
			GraphCursorManager.reset();
			return;
		}
		if (palette.blocksCanvasAt(mouseX, mouseY)) {
			GraphCursorManager.apply(palette.cursorKindAt(mouseX, mouseY));
			return;
		}
		if (canvas.contains(mouseX, mouseY)) {
			GraphCursorManager.apply(canvas.cursorKindAt(mouseX, mouseY));
			return;
		}
		GraphCursorManager.reset();
	}

	private boolean exceededSecondaryDragThreshold(double mouseX, double mouseY) {
		double dx = mouseX - secondaryPointerStartX;
		double dy = mouseY - secondaryPointerStartY;
		return (dx * dx) + (dy * dy) >= (CONTEXT_MENU_DRAG_THRESHOLD * CONTEXT_MENU_DRAG_THRESHOLD);
	}

	private GraphEditorBounds canvasBounds() {
		return new GraphEditorBounds(bounds.x(), bounds.y() + TOP_BAR_HEIGHT, bounds.width(), Math.max(0, bounds.height() - TOP_BAR_HEIGHT));
	}

	private int TOGGLE_AREA_WIDTH() {
		return 112;
	}
}
